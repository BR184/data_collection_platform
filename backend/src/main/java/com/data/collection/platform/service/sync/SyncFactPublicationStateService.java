package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.GitlabFactDependencyCatalog;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.Arrays;
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
   * 在首个事实写入批次之前登记各事实族"全量重建未结算"意图。
   *
   * <p>完整性不能只靠"有未发布根"判断：半次全量的根可能恰好都已发布到最新变化版本，此时
   * {@code countUnpublishedTargets} 为 0，来源看起来已经收敛。{@code full_publication_requested} 记录的是
   * "有一次全量重建尚未结算"，只在完整重建成功结算后清除；取消或失败都不清除，因此半次全量永远不会被当作
   * 完整产出对外发布。
   *
   * <p>已有发布记录的事实族只置标记、保留原有 {@code readiness_status}；尚无发布记录的事实族按依赖未就绪
   * 登记，因为该来源还没有任何可证明完整的产出，其当前事实本来就不许作为完整来源输出。
   * 未知镜像运行号显式保留为 {@code null}，数据库只允许 BLOCKED 且有未结算全量意图的记录暂缺此身份；
   * 后续镜像终态沿正常入口补齐真实运行号，不能拿事实重建运行号冒充镜像证据。
   *
   * @param config 当前数据源配置
   * @return 登记的事实族数量
   */
  @Transactional
  public int requestFullPublication(GitlabSyncConfig config) {
    if (config == null || config.getId() == null) {
      return 0;
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    int registered = 0;
    for (FactType factType : GitlabFactDependencyCatalog.supportedFactTypes(config)) {
      registered +=
          jdbcTemplate.update(
              """
              insert into source_fact_publication_states(
                  config_id, source_instance, fact_type, latest_mirror_run_id, readiness_status,
                  full_publication_requested, full_request_revision, error_message, updated_at)
              values (?, ?, ?, null, 'BLOCKED', true, 1, '全量事实重建已请求但尚未结算', current_timestamp)
              on conflict (config_id, source_instance, fact_type) do update
                set full_publication_requested = true,
                    full_request_revision = source_fact_publication_states.full_request_revision + 1,
                    updated_at = current_timestamp
              """,
              config.getId(),
              sourceInstance,
              factType.name());
    }
    return registered;
  }

  /** 来源已有未结算的完整重建意图时，后续成功镜像必须恢复全量事实任务，不能降成无根增量。 */
  public boolean hasFullPublicationIntent(GitlabSyncConfig config) {
    if (config == null || config.getId() == null) return false;
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
        select exists(select 1 from source_fact_publication_states
          where config_id = ? and source_instance = ? and full_publication_requested)
        """, Boolean.class, config.getId(), GitlabSourceInstanceSupport.sourceInstanceOf(config)));
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
   * 依赖未就绪（镜像失败或依赖表未形成代际）属于完整性无从证实，两种能力都不可用。存在未发布根、全量重建
   * 已请求但尚未结算、标签事件历史未完成全量核验，都属于"已知未完整窗口"：当前事实不可作为完整来源输出，
   * 但更早保存的完整产出仍允许返回。
   *
   * <p>来源尚无任何发布记录时，只有“该来源从未产生事实投影”才算可证明的完整（它不贡献任何数据）；
   * 已经有事实投影却没有发布记录，属于完整性无从证实的未知状态，一律拒绝，不得把未知当作可读，
   * 也不得因为测试夹具里存在这样的数据就放行。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实族
   * @return 资格结果；当前事实不可读时 {@code reason} 为可直接展示的原因
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
    long unpublished = countUnpublishedTargets(sourceInstance, factType);
    java.util.List<String> unsettledReasons = new java.util.ArrayList<>();
    if (row.fullPublicationPending()) {
      unsettledReasons.add("全量事实重建尚未结算");
    }
    if (unpublished > 0L) {
      unsettledReasons.add("尚未收敛到最新变化版本（仍有 " + unpublished + " 项待更新）");
    }
    if (!labelEventHistoryVerified(sourceInstance, factType)) {
      unsettledReasons.add(
          GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance)
              + " 的 resource_label_events 尚未完成完整全量核验");
    }
    if (!unsettledReasons.isEmpty()) {
      return SourceQualification.settling(
          unpublished,
          "来源 "
              + GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance)
              + " "
              + String.join("；", unsettledReasons)
              + "，当前事实不可作为完整来源对外输出");
    }
    return SourceQualification.healthy();
  }

  /**
   * 判断该来源是否具备建立初始完整版本的正向证据。
   *
   * <p>与 {@link #qualification} 不同：这里要求**存在**可证明的完整基线证据，而不是“未发现拒绝条件”。必须同时满足：
   * 事实族已有 READY 且无未结算全量意图的发布状态、依赖清单已登记且全部就绪、变化头已收敛（无未发布根）、
   * 没有在飞事实构建任务。按每个配置的完整依赖目录逐项核验，缺项也拒绝；本查询本身不锁定输入，
   * 接管方必须在稳定输入栅栏内调用。仅库中存在事实行、却没有任何依赖或发布证据时一律不成立，
   * 因此不能把“未知基线”当作完整来源采纳；该来源是否属于“首次无完整产出”由调用方按业务边界判定。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实族
   * @return 是否具备建立初始完整版本的正向证据
   */
  public boolean hasCompleteInitialVersionEvidence(String sourceInstance, FactType factType) {
    String instance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    String type = factType.name();
    Boolean evidenced =
        jdbcTemplate.queryForObject(
            """
            select
              exists(select 1 from source_fact_publication_states state
                      where state.source_instance = ? and state.fact_type = ?
                        and state.readiness_status = 'READY'
                        and not state.full_publication_requested)
              and exists(select 1 from source_fact_dependency_states dep
                          where dep.source_instance = ? and dep.fact_type = ?
                            and dep.readiness_status = 'READY')
              and not exists(select 1 from source_fact_dependency_states dep
                              where dep.source_instance = ? and dep.fact_type = ?
                                and dep.readiness_status <> 'READY')
              and not exists(select 1 from fact_change_heads head
                              where head.source_instance = ? and head.fact_type = ?
                                and head.published_version < head.latest_change_version)
              and not exists(select 1 from fact_build_tasks task
                              where task.source_instance = ? and task.fact_type = ?
                                and task.status not in ('SUCCESS', 'FAILED', 'CANCELLED', 'SKIPPED'))
            """,
            Boolean.class,
            instance,
            type,
            instance,
            type,
            instance,
            type,
            instance,
            type,
            instance,
            type);
    if (!Boolean.TRUE.equals(evidenced)) {
      return false;
    }
    return canBuildReadCandidate(instance, factType);
  }

  /**
   * 候选输入资格：当前来源状态与全部配置必需依赖都有完整证据，并通过附加标签核验。
   * 与 isReady 的区别是无需存在待发布头或全量意图，旁路重算也必须使用本来源自己的完整基线。
   */
  public boolean canBuildReadCandidate(String sourceInstance, FactType factType) {
    String instance = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    String type = factType.name();
    Boolean ready = jdbcTemplate.queryForObject("""
        select exists(select 1 from source_fact_publication_states where source_instance = ? and fact_type = ?)
          and not exists(select 1 from source_fact_publication_states
            where source_instance = ? and fact_type = ? and readiness_status <> 'READY')
        """, Boolean.class, instance, type, instance, type);
    if (!Boolean.TRUE.equals(ready) || !labelEventHistoryVerified(instance, factType)) {
      return false;
    }
    List<GitlabSyncConfig> configs = jdbcTemplate.query(
        """
        select distinct config.id, config.whitelist_mode,
               array(select jsonb_array_elements_text(
                   coalesce(nullif(config.whitelist_tables, ''), '[]')::jsonb)) as configured_tables
          from source_fact_publication_states state
          join gitlab_sync_configs config on config.id = state.config_id
         where state.source_instance = ? and state.fact_type = ?
        """,
        (rs, rowNumber) -> {
          GitlabSyncConfig config = new GitlabSyncConfig();
          config.setId(rs.getLong("id"));
          config.setWhitelistMode(WhitelistMode.valueOf(rs.getString("whitelist_mode")));
          config.setWhitelistTables(Arrays.asList((String[]) rs.getArray("configured_tables").getArray()));
          return config;
        }, instance, type);
    return !configs.isEmpty() && configs.stream().allMatch(config ->
        GitlabFactDependencyCatalog.supportedFactTypes(config).contains(factType)
            && familyReady(config.getId(), instance, factType,
                GitlabFactDependencyCatalog.require(factType).requiredTables(config)));
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
   * 在完整读版本发布事务内清除所捕获的全量意图；heads 由固定输入清单逐根结算。
   *
   * <p>全量意图按修订号 CAS 清除：只有当前意图修订仍等于候选捕获的修订时才清除，较新的全量请求不会被
   * 较旧的全量结算误清。
   *
   * @param capturedFullRequestRevision 候选开启时捕获的全量意图修订号
   * @return CAS 匹配的来源状态行数；较新意图存在时为 0，且较新意图保持在位
   */
  @Transactional
  public int settleFullPublicationIntent(
      String sourceInstance, FactType factType, long capturedFullRequestRevision) {
    return jdbcTemplate.update(
        """
        update source_fact_publication_states
           set full_publication_requested = false,
               updated_at = current_timestamp
         where source_instance = ? and fact_type = ?
           and full_request_revision = ?
        """, sourceInstance, factType.name(), capturedFullRequestRevision);
  }

  /**
   * 冻结本次候选的来源完整代际证明。
   *
   * <p>证据必须来自真实生产边界，而不是墙上时钟或请求时刻：包含该事实族的必需依赖表及其最近完整镜像运行、
   * 来源发布状态与全量意图修订、变化头水位与覆盖上界。候选据此判定输入是否仍是当时的完整来源，重启或重试
   * 不得拼接不同来源输入。
   *
   * @param sourceInstance 来源实例
   * @param factType 事实类型
   * @param coveredChangeVersion 本候选覆盖的变化水位上界
   * @return 可直接写入 {@code read_publications.source_seal} 的 JSON 文本
   */
  public String sourceSeal(String sourceInstance, FactType factType, long coveredChangeVersion) {
    String normalizedSource = GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance);
    String factTypeName = factType.name();
    String seal =
        jdbcTemplate.queryForObject(
            """
            select jsonb_build_object(
                'sourceInstance', ?,
                'factType', ?,
                'coveredChangeVersion', ?,
                'configId', (select coalesce(max(state.config_id), 0)
                               from source_fact_publication_states state
                              where state.source_instance = ? and state.fact_type = ?),
                'publicationState', (select jsonb_build_object(
                                        'readiness', state.readiness_status,
                                        'fullRequested', state.full_publication_requested,
                                        'fullRequestRevision', state.full_request_revision,
                                        'latestMirrorRunId', state.latest_mirror_run_id)
                                      from source_fact_publication_states state
                                     where state.source_instance = ? and state.fact_type = ?),
                'dependencies', (select coalesce(jsonb_agg(jsonb_build_object(
                                    'sourceTable', dep.source_table,
                                    'readiness', dep.readiness_status,
                                    'latestMirrorRunId', dep.latest_mirror_run_id)
                                  order by dep.source_table), '[]'::jsonb)
                                  from source_fact_dependency_states dep
                                 where dep.source_instance = ? and dep.fact_type = ?),
                'headWatermark', (select coalesce(max(head.latest_change_version), 0)
                                    from fact_change_heads head
                                   where head.source_instance = ? and head.fact_type = ?)
            )::text
            """,
            String.class,
            normalizedSource,
            factTypeName,
            coveredChangeVersion,
            normalizedSource,
            factTypeName,
            normalizedSource,
            factTypeName,
            normalizedSource,
            factTypeName,
            normalizedSource,
            factTypeName);
    return seal == null ? "{}" : seal;
  }

  /** 当前来源全量意图修订号；无请求时为 0。 */
  public long currentFullRequestRevision(String sourceInstance, FactType factType) {
    Long revision =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(full_request_revision), 0)
              from source_fact_publication_states
             where source_instance = ? and fact_type = ?
            """,
            Long.class,
            sourceInstance,
            factType.name());
    return revision == null ? 0L : revision;
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
   * <p>拆成两种能力，调用者按自身是否需要读当前事实来要求：{@code currentFactReadable} 表示可以从当前事实
   * 读取完整产出；{@code savedSnapshotReadable} 表示完整性证据允许返回已经保存的完整产出。已知的未完整窗口
   * （有未发布根、全量重建未结算、标签事件历史未完成核验）属于"前者为假、后者为真"：当前事实不可信，但更早
   * 保存的完整产出仍然可用。完整性无从证实（依赖未就绪、有事实投影却没有发布记录）则两者皆假，必须拒绝，
   * 不能把未知当作可读。
   */
  public record SourceQualification(
      boolean currentFactReadable,
      boolean savedSnapshotReadable,
      long pendingUpdates,
      String reason) {
    public SourceQualification {
      if (currentFactReadable && !savedSnapshotReadable) {
        throw new IllegalArgumentException("可读当前事实的来源必须同时可读已保存产出");
      }
      if (!currentFactReadable && (reason == null || reason.isBlank())) {
        throw new IllegalArgumentException("当前事实不可读的来源必须给出原因");
      }
      if (pendingUpdates < 0L) {
        throw new IllegalArgumentException("待更新数量不得为负");
      }
    }

    /** 可从当前事实读取完整产出。 */
    public static SourceQualification healthy() {
      return new SourceQualification(true, true, 0L, null);
    }

    /** 已知未完整窗口：当前事实不可完整读取，但已保存的完整产出仍可返回。 */
    public static SourceQualification settling(long pendingUpdates, String reason) {
      return new SourceQualification(false, true, pendingUpdates, reason);
    }

    /** 完整性无从证实：当前事实与已保存产出都不可用。 */
    public static SourceQualification refused(String reason) {
      return new SourceQualification(false, false, 0L, reason);
    }
  }

  private record SourceQualificationRow(
      long stateCount, long notReadyCount, boolean fullPublicationPending, String errorMessage) {}
}
