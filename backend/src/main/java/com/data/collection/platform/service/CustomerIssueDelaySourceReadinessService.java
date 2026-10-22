package com.data.collection.platform.service;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 延期标签写回的执行前资格核验。
 *
 * <p>这里刻意不使用单一 {@code isReady} 或 {@code qualification} 的布尔结果作为写回许可：
 * {@code isReady} 表达“存在待提交工作”，{@code qualification} 在无状态且无投影时也可能返回健康。
 * 写回要求正证据——绑定镜像完整成功、发布状态 READY、依赖表 READY、无未结算全量意图、
 * 无未发布根、标签事件历史完整、且没有在途运行正在改写相关输入。
 */
@Service
public class CustomerIssueDelaySourceReadinessService {
  private static final List<String> ACTIVE_RUN_STATUSES =
      List.of("SUBMITTED", "QUEUED", "RUNNING", "RETRYING", "PAUSED", "CANCELLING");
  private static final FactType DELAY_FACT_TYPE = FactType.ISSUE;

  private final JdbcTemplate jdbcTemplate;
  private final SyncFactPublicationStateService publicationStateService;
  private final CustomerIssueDelayPreWritebackSyncService preWritebackSyncService;

  public CustomerIssueDelaySourceReadinessService(
      JdbcTemplate jdbcTemplate,
      SyncFactPublicationStateService publicationStateService,
      CustomerIssueDelayPreWritebackSyncService preWritebackSyncService) {
    this.jdbcTemplate = jdbcTemplate;
    this.publicationStateService = publicationStateService;
    this.preWritebackSyncService = preWritebackSyncService;
  }

  /**
   * 核验绑定镜像是否完整成功。
   *
   * <p>{@code SUCCESS} 且所需表都有成功任务才算完整；{@code PARTIAL_SUCCESS}、取消或失败都不会放行。
   *
   * @param config 当前数据源配置
   * @param runId 本轮绑定的镜像运行号
   * @param requiredTables 本轮必须覆盖的前置刷新表
   * @return 核验结果；{@code terminal=false} 表示运行仍在进行，可以继续等待
   */
  public MirrorCheck checkMirrorRun(
      GitlabSyncConfig config, Long runId, List<String> requiredTables) {
    if (config == null || config.getId() == null || runId == null) {
      return new MirrorCheck(false, true, "本轮未绑定前置镜像运行");
    }
    List<RunRow> runs =
        jdbcTemplate.query(
            """
            select id, config_id, source_instance, run_type, status
              from sync_runs
             where id = ?
            """,
            (rs, rowNum) ->
                new RunRow(
                    rs.getLong("id"),
                    rs.getLong("config_id"),
                    rs.getString("source_instance"),
                    rs.getString("run_type"),
                    rs.getString("status")),
            runId);
    if (runs.isEmpty()) {
      return new MirrorCheck(false, true, "绑定的前置镜像运行不存在");
    }
    RunRow run = runs.getFirst();
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    if (run.configId() != config.getId()
        || !GitlabSourceInstanceSupport.normalizeSourceInstance(run.sourceInstance())
            .equals(sourceInstance)) {
      return new MirrorCheck(false, true, "绑定的前置镜像运行不属于当前数据源");
    }
    if (!isTerminal(run.status())) {
      return new MirrorCheck(false, false, "前置镜像尚未终态：" + run.status());
    }
    if (!SyncRunStatus.SUCCESS.name().equals(run.status())) {
      return new MirrorCheck(false, true, "前置镜像终态非 SUCCESS：" + run.status());
    }
    List<String> failedTables =
        jdbcTemplate.query(
            """
            select distinct task.source_table
              from sync_run_table_tasks task
             where task.run_id = ?
               and task.status <> 'SUCCESS'
             order by task.source_table
            """,
            (rs, rowNum) -> rs.getString("source_table"),
            runId);
    if (!failedTables.isEmpty()) {
      return new MirrorCheck(false, true, "前置镜像存在未成功表任务：" + failedTables);
    }
    List<String> missingTables =
        jdbcTemplate.query(
            """
            select required.table_name
              from (select unnest(?::text[]) as table_name) required
             where not exists (
               select 1
                 from sync_run_table_tasks task
                where task.run_id = ?
                  and task.source_table = required.table_name
                  and task.status = 'SUCCESS')
             order by required.table_name
            """,
            (rs, rowNum) -> rs.getString("table_name"),
            requiredTables.toArray(String[]::new),
            runId);
    if (!missingTables.isEmpty()) {
      return new MirrorCheck(false, true, "前置镜像未覆盖必需表：" + missingTables);
    }
    return new MirrorCheck(true, true, null);
  }

  /**
   * 评估来源事实是否已收敛且具备写回正证据。
   *
   * @param config 当前数据源配置
   * @param mirrorRunId 本轮绑定的前置镜像运行号
   * @param requiredTables 本轮必须覆盖的前置刷新表
   * @return 评估结果；{@code reason} 可直接作为等待原因
   */
  public Readiness assess(
      GitlabSyncConfig config, Long mirrorRunId, List<String> requiredTables) {
    if (config == null || config.getId() == null) {
      return new Readiness(false, "数据源配置不存在", 0L, 0L);
    }
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    MirrorCheck mirror = checkMirrorRun(config, mirrorRunId, requiredTables);
    if (!mirror.complete()) {
      return new Readiness(false, mirror.reason(), currentFactVersion(sourceInstance), 0L);
    }
    long factVersion = currentFactVersion(sourceInstance);
    long unpublished = publicationStateService.countUnpublishedTargets(sourceInstance, DELAY_FACT_TYPE);
    List<String> blockers = positiveEvidenceBlockers(config, sourceInstance);
    if (hasActiveRunsRewritingInputs(config, sourceInstance)) {
      blockers.add("存在正在改写延期相关输入的同步或事实运行");
    }
    if (!blockers.isEmpty()) {
      return new Readiness(false, String.join("；", blockers), factVersion, unpublished);
    }
    return new Readiness(true, null, factVersion, unpublished);
  }

  /**
   * 核验本轮授权是否仍然有效：事实版本未变化、来源仍收敛、没有在途改写。
   *
   * @param config 当前数据源配置
   * @param progress 当前来源进度
   * @param requiredTables 本轮必须覆盖的前置刷新表
   * @return 授权核验结果
   */
  public AuthorizationCheck validateRoundAuthorization(
      GitlabSyncConfig config,
      CustomerIssueDelayClosureProgress progress,
      List<String> requiredTables) {
    if (progress == null) {
      return new AuthorizationCheck(false, "来源没有写回进度", 0L);
    }
    if (progress.stage() != CustomerIssueDelayClosureStage.ENQUEUE
        && progress.stage() != CustomerIssueDelayClosureStage.IDLE) {
      return new AuthorizationCheck(false, "本轮尚未取得写回授权", 0L);
    }
    if (!progress.authorized()) {
      return new AuthorizationCheck(false, "本轮写回授权已撤销", 0L);
    }
    Readiness readiness = assess(config, progress.mirrorRunId(), requiredTables);
    if (!readiness.ready()) {
      return new AuthorizationCheck(false, readiness.reason(), readiness.factVersion());
    }
    if (readiness.factVersion() != progress.authorizedFactVersion()) {
      return new AuthorizationCheck(
          false,
          "相关事实版本已从 " + progress.authorizedFactVersion() + " 变为 " + readiness.factVersion(),
          readiness.factVersion());
    }
    return new AuthorizationCheck(true, null, readiness.factVersion());
  }

  /** 返回当前来源 ISSUE 事实的最新变化版本；无变化记录时为 0。 */
  public long currentFactVersion(String sourceInstance) {
    Long version =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(latest_change_version), 0)
              from fact_change_heads
             where source_instance = ? and fact_type = ?
            """,
            Long.class,
            GitlabSourceInstanceSupport.normalizeSourceInstance(sourceInstance),
            DELAY_FACT_TYPE.name());
    return version == null ? 0L : version;
  }

  /** 返回延期写回必须视为相关输入的来源表：前置刷新表与 ISSUE 完整依赖的并集。 */
  public List<String> relevantTables(GitlabSyncConfig config) {
    LinkedHashSet<String> tables = new LinkedHashSet<>(preWritebackSyncService.preWritebackTables());
    tables.addAll(GitlabFactDependencyCatalog.require(DELAY_FACT_TYPE).requiredTables(config));
    return List.copyOf(tables);
  }

  private List<String> positiveEvidenceBlockers(
      GitlabSyncConfig config, String sourceInstance) {
    EvidenceRow evidence =
        jdbcTemplate.queryForObject(
            """
            select
              (select count(*)
                 from source_fact_publication_states state
                where state.config_id = ? and state.source_instance = ? and state.fact_type = ?)
                as state_count,
              (select count(*)
                 from source_fact_publication_states state
                where state.config_id = ? and state.source_instance = ? and state.fact_type = ?
                  and state.readiness_status <> 'READY')
                as not_ready_count,
              (select coalesce(bool_or(state.full_publication_requested), false)
                 from source_fact_publication_states state
                where state.config_id = ? and state.source_instance = ? and state.fact_type = ?)
                as full_publication_pending,
              (select count(*)
                 from (select unnest(?::text[]) as table_name) required
                where not exists (
                  select 1
                    from source_fact_dependency_states dependency
                   where dependency.config_id = ?
                     and dependency.source_instance = ?
                     and dependency.fact_type = ?
                     and dependency.source_table = required.table_name
                     and dependency.readiness_status = 'READY'))
                as missing_dependencies
            """,
            (rs, rowNum) ->
                new EvidenceRow(
                    rs.getLong("state_count"),
                    rs.getLong("not_ready_count"),
                    rs.getBoolean("full_publication_pending"),
                    rs.getLong("missing_dependencies")),
            config.getId(),
            sourceInstance,
            DELAY_FACT_TYPE.name(),
            config.getId(),
            sourceInstance,
            DELAY_FACT_TYPE.name(),
            config.getId(),
            sourceInstance,
            DELAY_FACT_TYPE.name(),
            requiredDependencies(config),
            config.getId(),
            sourceInstance,
            DELAY_FACT_TYPE.name());
    List<String> blockers = new ArrayList<>();
    if (evidence == null || evidence.stateCount() == 0L) {
      blockers.add("来源尚无 READY 发布状态");
    }
    if (evidence != null && evidence.notReadyCount() > 0L) {
      blockers.add("事实来源依赖未就绪");
    }
    if (evidence != null && evidence.fullPublicationPending()) {
      blockers.add("全量事实重建尚未结算");
    }
    long unpublished = publicationStateService.countUnpublishedTargets(sourceInstance, DELAY_FACT_TYPE);
    if (unpublished > 0L) {
      blockers.add("仍有 " + unpublished + " 项待发布到最新变化版本");
    }
    if (!publicationStateService.isIssueSourceHistoryComplete(sourceInstance)) {
      blockers.add("标签事件历史尚未完成全量核验");
    }
    if (evidence != null && evidence.missingDependencies() > 0L) {
      blockers.add("ISSUE 依赖表未全部 READY（缺少 " + evidence.missingDependencies() + " 项）");
    }
    return blockers;
  }

  private String[] requiredDependencies(GitlabSyncConfig config) {
    Set<String> tables =
        new LinkedHashSet<>(
            GitlabFactDependencyCatalog.require(DELAY_FACT_TYPE).requiredTables(config));
    return tables.toArray(String[]::new);
  }

  private boolean hasActiveRunsRewritingInputs(
      GitlabSyncConfig config, String sourceInstance) {
    String[] relevant = relevantTables(config).toArray(String[]::new);
    Boolean active =
        jdbcTemplate.queryForObject(
            """
            select exists(
              select 1
                from sync_runs run
               where run.config_id = ?
                 and run.source_instance = ?
                 and run.status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'PAUSED', 'CANCELLING')
                 and (
                   run.run_type = 'FACT_REFRESH'
                   or exists (
                     select 1
                       from sync_run_table_tasks task
                      where task.run_id = run.id
                        and task.source_table = any(?::text[]))
                   or (
                     not exists (
                       select 1 from sync_run_table_tasks task where task.run_id = run.id)
                     and (
                       coalesce(
                         coalesce(nullif(run.payload_json, ''), '{}')::jsonb -> 'sourceTables',
                         '[]'::jsonb) = '[]'::jsonb
                       or exists (
                         select 1
                           from jsonb_array_elements_text(
                             coalesce(
                               coalesce(nullif(run.payload_json, ''), '{}')::jsonb -> 'sourceTables',
                               '[]'::jsonb)) table_name
                          where lower(table_name) = any(?::text[]))
                     )
                   )
                 ))
            """,
            Boolean.class,
            config.getId(),
            sourceInstance,
            relevant,
            relevant);
    return Boolean.TRUE.equals(active);
  }

  private boolean isTerminal(String status) {
    return status != null
        && !ACTIVE_RUN_STATUSES.contains(status);
  }

  private record RunRow(
      long id, long configId, String sourceInstance, String runType, String status) {}

  private record EvidenceRow(
      long stateCount,
      long notReadyCount,
      boolean fullPublicationPending,
      long missingDependencies) {}

  /** 绑定镜像核验结果。 */
  public record MirrorCheck(boolean complete, boolean terminal, String reason) {}

  /** 来源收敛与正证据评估结果。 */
  public record Readiness(
      boolean ready, String reason, long factVersion, long unpublishedTargets) {}

  /** 本轮授权核验结果。 */
  public record AuthorizationCheck(boolean allowed, String reason, long factVersion) {}
}
