package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.GitlabFactDependencyCatalog;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化来源依赖代际，并决定事实族是否允许产生事实副作用。 */
@Service
public class SyncFactPublicationStateService {
  private final JdbcTemplate jdbcTemplate;
  private final SyncRunAuthoritativeScopeRepository scopeRepository;

  public SyncFactPublicationStateService(
      JdbcTemplate jdbcTemplate, SyncRunAuthoritativeScopeRepository scopeRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.scopeRepository = scopeRepository;
  }

  /**
   * 在镜像运行终态后提交该运行对事实依赖的就绪结果。
   *
   * @param config 当前数据源配置
   * @param runId 已终态镜像运行编号
   * @param runStatus 镜像运行终态
   * @param fullSuccessful 是否为成功全量镜像或全量补偿
   * @return 当前来源是否存在可消费的事实发布意图
   */
  @Transactional
  public boolean recordMirrorCompletion(
      GitlabSyncConfig config,
      long runId,
      SyncRunStatus runStatus,
      boolean fullSuccessful) {
    if (config == null || config.getId() == null || runId <= 0L) {
      return false;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    Set<String> selectedTables = scopeRepository.selectedSourceTables(runId);
    boolean hasIntent = false;
    for (FactType factType : GitlabFactDependencyCatalog.supportedFactTypes(config)) {
      List<String> dependencies = requiredTables(config, factType);
      for (String table : dependencies) {
        if (!selectedTables.contains(table)) {
          continue;
        }
        boolean blocked = dependencyBlocked(runId, table, runStatus);
        upsertDependency(
            config.getId(), sourceInstance, factType, table, runId, blocked);
      }
      boolean ready = familyReady(config.getId(), sourceInstance, factType, dependencies);
      boolean fullRequested = fullSuccessful && ready;
      upsertFamily(
          config.getId(),
          sourceInstance,
          factType,
          runId,
          ready,
          fullRequested,
          ready ? null : "事实来源依赖尚未形成完整可读代际");
      hasIntent |= ready && (fullRequested || hasPendingTargets(sourceInstance, factType));
    }
    return hasIntent;
  }

  /** 判断指定事实族当前是否允许读取 ODS 并提交事实。 */
  public boolean isReady(String sourceInstance, FactType factType) {
    if (!labelEventHistoryVerified(sourceInstance, factType)) {
      return false;
    }
    Boolean ready = jdbcTemplate.queryForObject(
        """
        select exists(
          select 1 from source_fact_publication_states state
           where state.source_instance = ? and state.fact_type = ? and state.readiness_status = 'READY'
             and (state.full_publication_requested or exists(
               select 1 from fact_change_heads head
                where head.source_instance = state.source_instance
                  and head.fact_type = state.fact_type
                  and head.published_version < head.latest_change_version))
        )
        """,
        Boolean.class,
        sourceInstance,
        factType.name());
    return Boolean.TRUE.equals(ready);
  }

  /** 判断 ISSUE 事实读取依赖的标签事件历史是否已经完成全量核验。 */
  public boolean isIssueSourceHistoryComplete(String sourceInstance) {
    return labelEventHistoryVerified(sourceInstance, FactType.ISSUE);
  }

  /**
   * 读取来源的对外可读资格。
   *
   * <p>与 {@link #isReady} 的“有没有待提交工作”含义不同：这里回答“当前能否把已提交事实当作完整来源对外输出”。
   * 依赖未就绪（镜像失败或依赖表未形成代际）、全量重建已请求但尚未结算、以及仍有未发布到最新变化版本的目标，
   * 都属于不完整发布窗口，此时不得对外产出新的 READY 结果。
   *
   * <p>来源尚无任何发布记录时，只有“该来源从未产生事实投影”才算可证明的完整（它不贡献任何数据）；
   * 已经有事实投影却没有发布记录，属于完整性无从证实的未知状态，一律拒绝，不得把未知当作可读，
   * 也不得因为测试夹具里存在这样的数据就放行。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实族
   * @return 资格结果；不可读时 {@code reason} 为可直接展示的原因
   */
  public SourceQualification qualification(String sourceInstance, FactType factType) {
    SourceQualificationRow row =
        jdbcTemplate.queryForObject(
            """
            select count(*) as state_count,
                   count(*) filter (where readiness_status <> 'READY') as not_ready_count,
                   coalesce(bool_or(full_publication_requested), false) as full_publication_pending,
                   min(error_message) filter (where readiness_status <> 'READY') as error_message
              from source_fact_publication_states
             where source_instance = ? and fact_type = ?
            """,
            (resultSet, rowNumber) ->
                new SourceQualificationRow(
                    resultSet.getLong("state_count"),
                    resultSet.getLong("not_ready_count"),
                    resultSet.getBoolean("full_publication_pending"),
                    resultSet.getString("error_message")),
            sourceInstance,
            factType.name());
    if (row == null || row.stateCount() == 0L) {
      if (hasPublishedProjections(sourceInstance, factType)) {
        return SourceQualification.refused(
            "来源 "
                + sourceInstance
                + " 已有事实投影但没有发布代际记录，无法确认完整性，暂不产出统计结果");
      }
      return SourceQualification.healthy();
    }
    if (row.notReadyCount() > 0L) {
      String detail = row.errorMessage() == null ? "事实来源依赖未就绪" : row.errorMessage();
      return SourceQualification.refused("来源 " + sourceInstance + " 当前不可读：" + detail);
    }
    if (row.fullPublicationPending()) {
      return SourceQualification.refused(
          "来源 " + sourceInstance + " 的全量事实重建尚未结算，暂不产出统计结果");
    }
    long unpublished = countUnpublishedTargets(sourceInstance, factType);
    if (unpublished > 0L) {
      return SourceQualification.degraded(unpublished);
    }
    if (!labelEventHistoryVerified(sourceInstance, factType)) {
      return SourceQualification.refused(
          "来源 "
              + GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance)
              + " 的 resource_label_events 尚未完成完整全量核验，暂不产出统计结果");
    }
    return SourceQualification.healthy();
  }

  /** 判断来源是否已经产生过事实投影代际；有代际却没有发布记录时，完整性无从证实。 */
  private boolean hasPublishedProjections(String sourceInstance, FactType factType) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1 from fact_projection_generations
               where source_instance = ? and fact_type = ?)
            """,
            Boolean.class,
            GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
            factType.name());
    return Boolean.TRUE.equals(exists);
  }

  /**
   * 统计指定来源实例与事实族下尚未发布到最新变化版本的根数量。
   *
   * <p>权威读法是 {@code fact_change_heads} 的版本栅栏：{@code published_version} 表示该根截至该版本
   * 的变化均已发布。目标表只是按轮累积的登记日志，不参与控制面判定，因此计数成本与历史规模无关。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实族
   * @return 未发布根数量；{@code 0} 表示该事实族已登记的变化全部发布完成
   */
  public long countUnpublishedTargets(String sourceInstance, FactType factType) {
    Long unpublished =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from fact_change_heads head
             where head.source_instance = ? and head.fact_type = ?
               and head.published_version < head.latest_change_version
            """,
            Long.class,
            sourceInstance,
            factType.name());
    return unpublished == null ? 0L : unpublished;
  }

  /**
   * 返回该事实族当前已登记变化的版本上界。
   *
   * <p>全量发布必须在构建开始前用它冻结"本次构建覆盖到哪一版"：构建期间新登记的更高版本保持待发布，
   * 由后续定向刷新收敛，不会被全量结算误标为已发布。
   */
  public long publicationUpperBound(String sourceInstance, FactType factType) {
    Long bound =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(head.latest_change_version), 0)
              from fact_change_heads head
             where head.source_instance = ? and head.fact_type = ?
            """,
            Long.class,
            sourceInstance,
            factType.name());
    return bound == null ? 0L : bound;
  }

  /**
   * 在成功全量事实发布后把该事实族的版本头结算到构建覆盖的版本上界。
   *
   * @param coveredVersion 构建开始前冻结的版本上界；只有不高于它的变化才可标记为已发布
   */
  @Transactional
  public int settleAfterFullPublication(
      String sourceInstance, FactType factType, Long taskId, long coveredVersion) {
    int updated =
        jdbcTemplate.update(
            """
            update fact_change_heads head
               set published_version = greatest(
                       head.published_version, least(head.latest_change_version, ?)),
                   published_by_fact_build_task_id = ?,
                   updated_at = current_timestamp
             where head.source_instance = ?
               and head.fact_type = ?
               and head.published_version < least(head.latest_change_version, ?)
            """,
            coveredVersion,
            taskId,
            sourceInstance,
            factType.name(),
            coveredVersion);
    jdbcTemplate.update(
        """
        update source_fact_publication_states
           set full_publication_requested = false,
               updated_at = current_timestamp
         where source_instance = ? and fact_type = ?
        """,
        sourceInstance,
        factType.name());
    return updated;
  }

  /** 返回一个事实族所有来源表（含条件 MR 提交增强来源）。 */
  public List<String> requiredTables(GitlabSyncConfig config, FactType factType) {
    GitlabFactDependencyCatalog.FactDependency dependency =
        GitlabFactDependencyCatalog.require(factType);
    return dependency.requiredTables(config);
  }

  private boolean dependencyBlocked(long runId, String sourceTable, SyncRunStatus runStatus) {
    Boolean failed = jdbcTemplate.queryForObject(
        """
        select exists(
          select 1 from sync_run_table_tasks
           where run_id = ? and source_table = ?
             and status in ('FAILED', 'CANCELLED')
          union all
          select 1 from sync_run_authoritative_scopes
           where run_id = ? and child_table = ? and status = 'FAILED'
        )
        """,
        Boolean.class,
        runId,
        sourceTable,
        runId,
        sourceTable);
    return Boolean.TRUE.equals(failed) || runStatus == SyncRunStatus.FAILED;
  }

  private boolean familyReady(
      long configId, String sourceInstance, FactType factType, List<String> dependencies) {
    Integer count = jdbcTemplate.queryForObject(
        """
        select count(*)
          from (
            select unnest(?::text[]) as source_table
          ) required
         where not exists (
           select 1 from source_fact_dependency_states state
            where state.config_id = ? and state.source_instance = ?
              and state.fact_type = ? and state.source_table = required.source_table
              and state.readiness_status = 'READY')
        """,
        Integer.class,
        dependencies.toArray(String[]::new),
        configId,
        sourceInstance,
        factType.name());
    return count != null
        && count == 0
        && labelEventHistoryVerified(sourceInstance, factType);
  }

  private boolean labelEventHistoryVerified(String sourceInstance, FactType factType) {
    if (factType != FactType.ISSUE) {
      return true;
    }
    Boolean verified =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from sync_run_table_states
               where source_instance = ?
                 and source_table = 'resource_label_events'
                 and sync_enabled = true
                 and dirty_flag = false
                 and last_full_verified_at is not null)
            """,
            Boolean.class,
            GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance));
    return Boolean.TRUE.equals(verified);
  }

  private void upsertDependency(
      long configId,
      String sourceInstance,
      FactType factType,
      String sourceTable,
      long runId,
      boolean blocked) {
    jdbcTemplate.update(
        """
        insert into source_fact_dependency_states(
            config_id, source_instance, fact_type, source_table,
            latest_mirror_run_id, readiness_status, error_message, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on conflict (config_id, source_instance, fact_type, source_table) do update
          set latest_mirror_run_id = excluded.latest_mirror_run_id,
              readiness_status = excluded.readiness_status,
              error_message = excluded.error_message,
              updated_at = current_timestamp
        """,
        configId,
        sourceInstance,
        factType.name(),
        sourceTable,
        runId,
        blocked ? "BLOCKED" : "READY",
        blocked ? "镜像依赖运行失败" : null);
  }

  private void upsertFamily(
      long configId,
      String sourceInstance,
      FactType factType,
      long runId,
      boolean ready,
      boolean fullRequested,
      String errorMessage) {
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
            config_id, source_instance, fact_type, latest_mirror_run_id,
            ready_mirror_run_id, blocked_mirror_run_id, readiness_status,
            full_publication_requested, error_message, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
        on conflict (config_id, source_instance, fact_type) do update
          set latest_mirror_run_id = excluded.latest_mirror_run_id,
              ready_mirror_run_id = case when excluded.readiness_status = 'READY'
                                         then excluded.ready_mirror_run_id
                                         else source_fact_publication_states.ready_mirror_run_id end,
              blocked_mirror_run_id = case when excluded.readiness_status = 'BLOCKED'
                                            then excluded.blocked_mirror_run_id
                                            else source_fact_publication_states.blocked_mirror_run_id end,
              readiness_status = excluded.readiness_status,
              full_publication_requested = source_fact_publication_states.full_publication_requested
                  or excluded.full_publication_requested,
              error_message = excluded.error_message,
              updated_at = current_timestamp
        """,
        configId,
        sourceInstance,
        factType.name(),
        runId,
        ready ? runId : null,
        ready ? null : runId,
        ready ? "READY" : "BLOCKED",
        fullRequested,
        errorMessage);
  }

  private boolean hasPendingTargets(String sourceInstance, FactType factType) {
    return countUnpublishedTargets(sourceInstance, factType) > 0L;
  }

  /**
   * 来源对外可读资格。
   *
   * <p>三种取值：可读（可产出当前结果）、降级可读（完整性可证，但尚未收敛到最新变化版本——允许输出
   * 上一个完整发布点并显式披露待更新数量）、不可读（完整性无从证实，必须拒绝）。区分"未知完整性"
   * 与"已知暂未收敛"，使持续积压不再表现为长时间全空，同时不放宽真正的完整性拒绝。
   */
  public record SourceQualification(
      boolean readable, boolean degraded, long pendingUpdates, String reason) {
    public SourceQualification {
      if (!readable && !degraded && (reason == null || reason.isBlank())) {
        throw new IllegalArgumentException("不可读来源必须给出原因");
      }
      if (degraded && pendingUpdates <= 0L) {
        throw new IllegalArgumentException("降级可读必须给出待更新数量");
      }
    }

    /** 可产出当前结果。 */
    public static SourceQualification healthy() {
      return new SourceQualification(true, false, 0L, null);
    }

    /** 完整性可证但尚未收敛：可输出上一完整发布点，并披露待更新数量。 */
    public static SourceQualification degraded(long pendingUpdates) {
      return new SourceQualification(true, true, pendingUpdates, null);
    }

    /** 完整性无从证实：拒绝输出。 */
    public static SourceQualification refused(String reason) {
      return new SourceQualification(false, false, 0L, reason);
    }
  }

  private record SourceQualificationRow(
      long stateCount, long notReadyCount, boolean fullPublicationPending, String errorMessage) {}
}
