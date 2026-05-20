package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SyncStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GitlabSourceHealthService {
  private static final List<String> CODE_REVIEW_REQUIRED_TABLES =
      List.of("merge_requests", "merge_request_metrics", "projects", "namespaces", "users");

  private final GitlabConfigService configService;
  private final JdbcTemplate jdbcTemplate;

  public GitlabSourceHealthService(
      GitlabConfigService configService,
      JdbcTemplate jdbcTemplate) {
    this.configService = configService;
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<GitlabSourceHealthResponse> listHealth() {
    return configService.listConfigs().stream()
        .filter(config -> config.getId() != null)
        .map(this::toHealth)
        .toList();
  }

  private GitlabSourceHealthResponse toHealth(GitlabSyncConfig config) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    boolean sourceEnabled = config.getSourceEnabled() == null ? config.isEnabled() : config.getSourceEnabled();
    String blockedMessage = resolveBlockedMessage(config);
    LatestSyncView latestSync = latestSyncView(config);
    ActiveSyncView activeSync = activeSyncView(config, sourceInstance);
    if (!sourceEnabled || blockedMessage != null) {
      String healthStatus = sourceEnabled ? "BLOCKED" : "DISABLED";
      String healthMessage = sourceEnabled ? blockedMessage : "GitLab 数据源已停用";
      return new GitlabSourceHealthResponse(
          config.getId(),
          config.getName(),
          sourceInstance,
          sourceEnabled,
          healthStatus,
          healthMessage,
          SyncStatus.IDLE,
          "",
          null,
          latestSync.status(),
          latestSync.message(),
          latestSync.finishedAt(),
          0,
          0,
          false,
          "",
          null,
          false,
          false,
          false,
          0,
          0,
          0,
          List.of());
    }

    List<String> registeredMirrorTables = registeredMirrorTables(config.getId());
    int existingMirrorTables = countExistingTables(registeredMirrorTables);
    LocalDateTime latestMergeRequestFactUpdatedAt = latestFactUpdatedAt("merge_request_fact", sourceInstance);
    boolean mergeRequestFactLagging =
        isFactLayerLagging(latestSync, latestMergeRequestFactUpdatedAt, existingMirrorTables);
    boolean issueFactLagging = false;
    boolean integrationTestFactLagging = false;
    boolean factLayerLagging =
        activeSync.factRefreshActive() || mergeRequestFactLagging || issueFactLagging || integrationTestFactLagging;
    List<String> missingRequiredMirrorTables = missingRequiredMirrorTables(sourceInstance);
    String healthStatus = resolveHealthStatus(
        latestSync,
        factLayerLagging,
        registeredMirrorTables,
        existingMirrorTables,
        missingRequiredMirrorTables);
    String healthMessage = resolveHealthMessage(healthStatus, latestSync, factLayerLagging, missingRequiredMirrorTables);
    return new GitlabSourceHealthResponse(
        config.getId(),
        config.getName(),
        sourceInstance,
        sourceEnabled,
        healthStatus,
        healthMessage,
        activeSync.status(),
        activeSync.message(),
        activeSync.startedAt(),
        latestSync.status(),
        latestSync.message(),
        latestSync.finishedAt(),
        registeredMirrorTables.size(),
        existingMirrorTables,
        factLayerLagging,
        factLayerLagging
            ? factLayerMessage(activeSync.factRefreshActive(), mergeRequestFactLagging, issueFactLagging, integrationTestFactLagging)
            : "",
        latestMergeRequestFactUpdatedAt,
        mergeRequestFactLagging,
        issueFactLagging,
        integrationTestFactLagging,
        countFacts("merge_request_fact", sourceInstance),
        countFacts("issue_fact", sourceInstance),
        countFacts("integration_test_fact", sourceInstance),
        missingRequiredMirrorTables);
  }

  private String resolveBlockedMessage(GitlabSyncConfig config) {
    if (config.getSourceMode() == SourceMode.DIRECT) {
      if (isBlank(config.getDbHost())
          || config.getDbPort() == null
          || isBlank(config.getDbName())
          || isBlank(config.getDbUsername())
          || isBlank(config.getDbPassword())) {
        return "GitLab 直连数据库配置不完整";
      }
    } else if (config.getSourceMode() == SourceMode.DOCKER && isBlank(config.getDockerContainerName())) {
      return "GitLab Docker 容器名未配置";
    }
    return null;
  }

  private String resolveHealthStatus(
      LatestSyncView latestSync,
      boolean factLayerLagging,
      List<String> registeredMirrorTables,
      int existingMirrorTables,
      List<String> missingRequiredMirrorTables) {
    if (!missingRequiredMirrorTables.isEmpty()
        || existingMirrorTables < registeredMirrorTables.size()
        || factLayerLagging
        || (latestSync.status() != null && isDegradedStatus(latestSync.status()))) {
      return "DEGRADED";
    }
    return "OK";
  }

  private String resolveHealthMessage(
      String healthStatus,
      LatestSyncView latestSync,
      boolean factLayerLagging,
      List<String> missingRequiredMirrorTables) {
    if ("OK".equals(healthStatus)) {
      return "GitLab 数据源正常";
    }
    if (!missingRequiredMirrorTables.isEmpty()) {
      return "缺少必要镜像表：" + String.join(", ", missingRequiredMirrorTables);
    }
    if (factLayerLagging) {
      return "事实层落后于最近一次成功的镜像同步";
    }
    if (!isBlank(latestSync.message())) {
      return latestSync.message();
    }
    return "GitLab 数据源状态异常";
  }

  private boolean isDegradedStatus(SyncStatus status) {
    return status == SyncStatus.FAILED
        || status == SyncStatus.TIMEOUT
        || status == SyncStatus.PARTIAL_SUCCESS;
  }

  private ActiveSyncView activeSyncView(GitlabSyncConfig config, String sourceInstance) {
    try {
      List<ActiveSyncView> rows =
          jdbcTemplate.query(
              """
              select run_type, status, run_id, started_at, created_at
                from sync_runs
               where config_id = ?
                 and source_instance = ?
                 and status in ('SUBMITTED', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING')
               order by case when status in ('RUNNING', 'CANCELLING') then 0 else 1 end, created_at asc, id asc
               limit 1
              """,
              (rs, rowNum) -> {
                String runType = rs.getString("run_type");
                String status = rs.getString("status");
                String runId = rs.getString("run_id");
                LocalDateTime startedAt = rs.getTimestamp("started_at") == null
                    ? null
                    : rs.getTimestamp("started_at").toLocalDateTime();
                boolean factRefreshActive = "FACT_REFRESH".equals(runType);
                return new ActiveSyncView(
                    toApiStatus(status),
                    factRefreshActive
                        ? "事实刷新正在执行，状态：" + status + "，运行：" + runId
                        : "镜像同步正在执行，状态：" + status + "，运行：" + runId,
                    startedAt,
                    factRefreshActive);
              },
              config.getId(),
              sourceInstance);
      return rows.isEmpty() ? ActiveSyncView.idle() : rows.getFirst();
    } catch (DataAccessException error) {
      log.debug("Failed to load active sync run for source health, configId={}", config.getId(), error);
      return ActiveSyncView.idle();
    }
  }

  private SyncStatus toApiStatus(String status) {
    if (status == null) {
      return SyncStatus.IDLE;
    }
    return switch (status) {
      case "SUBMITTED", "QUEUED" -> SyncStatus.QUEUED;
      case "RUNNING" -> SyncStatus.RUNNING;
      case "RETRYING" -> SyncStatus.RETRYING;
      case "CANCELLING" -> SyncStatus.CANCELLING;
      case "SUCCESS" -> SyncStatus.SUCCESS;
      case "PARTIAL_SUCCESS" -> SyncStatus.PARTIAL_SUCCESS;
      case "FAILED" -> SyncStatus.FAILED;
      case "CANCELLED" -> SyncStatus.CANCELLED;
      case "TIMEOUT" -> SyncStatus.TIMEOUT;
      default -> SyncStatus.IDLE;
    };
  }

  private List<String> registeredMirrorTables(Long configId) {
    try {
      return jdbcTemplate.queryForList(
          """
          select mirror_table_name
            from sys_table_registry
           where config_id = ?
           order by mirror_table_name
          """,
          String.class,
          configId);
    } catch (DataAccessException error) {
      log.debug("Failed to load mirror registry for source health, configId={}", configId, error);
      return List.of();
    }
  }

  private int countExistingTables(List<String> tableNames) {
    int total = 0;
    for (String tableName : tableNames) {
      if (tableExists(tableName)) {
        total += 1;
      }
    }
    return total;
  }

  private List<String> missingRequiredMirrorTables(String sourceInstance) {
    List<String> missing = new ArrayList<>();
    for (String sourceTable : CODE_REVIEW_REQUIRED_TABLES) {
      String mirrorTable = GitlabSourceInstanceSupport.buildMirrorTableName(sourceTable, sourceInstance);
      if (!tableExists(mirrorTable)) {
        missing.add(mirrorTable);
      }
    }
    return missing;
  }

  private boolean tableExists(String tableName) {
    try {
      Boolean exists = jdbcTemplate.queryForObject("select to_regclass(?) is not null", Boolean.class, tableName);
      return Boolean.TRUE.equals(exists);
    } catch (DataAccessException error) {
      log.debug("Failed to check table existence, tableName={}", tableName, error);
      return false;
    }
  }

  private long countFacts(String tableName, String sourceInstance) {
    if (!tableExists(tableName)) {
      return 0L;
    }
    try {
      Long total = jdbcTemplate.queryForObject(
          "select count(*) from "
              + quoteIdentifier(tableName)
              + " where lower(coalesce(source_instance, 'default')) = ? and deleted = false",
          Long.class,
          sourceInstance);
      return total == null ? 0L : total;
    } catch (DataAccessException error) {
      log.debug("Failed to count fact rows for source health, tableName={}", tableName, error);
      return 0L;
    }
  }

  private LocalDateTime latestFactUpdatedAt(String tableName, String sourceInstance) {
    if (!tableExists(tableName)) {
      return null;
    }
    try {
      return jdbcTemplate.queryForObject(
          "select max(updated_at) from "
              + quoteIdentifier(tableName)
              + " where lower(coalesce(source_instance, 'default')) = ? and deleted = false",
          LocalDateTime.class,
          sourceInstance);
    } catch (DataAccessException error) {
      log.debug("Failed to load latest fact updated time for source health, tableName={}", tableName, error);
      return null;
    }
  }

  private boolean isFactLayerLagging(
      LatestSyncView latestSync, LocalDateTime latestFactUpdatedAt, int existingMirrorTables) {
    if (latestSync.status() != SyncStatus.SUCCESS
        || latestSync.finishedAt() == null
        || existingMirrorTables <= 0) {
      return false;
    }
    if (latestFactUpdatedAt == null) {
      return true;
    }
    return latestFactUpdatedAt.isBefore(latestSync.finishedAt().minusSeconds(5));
  }

  private LatestSyncView latestSyncView(GitlabSyncConfig config) {
    LocalDateTime incremental = config.getLastIncrementalSyncAt();
    LocalDateTime full = config.getLastFullSyncAt();
    LocalDateTime latest = max(incremental, full);
    if (latest == null) {
      return new LatestSyncView(null, "切换期间已清理旧版同步日志", null);
    }
    return new LatestSyncView(SyncStatus.SUCCESS, "最近一次持久化同步时间", latest);
  }

  private String factLayerMessage(
      boolean factRefreshActive,
      boolean mergeRequestFactLagging,
      boolean issueFactLagging,
      boolean integrationTestFactLagging) {
    if (factRefreshActive) {
      return "事实刷新正在排队或执行";
    }
    List<String> laggingDomains = new ArrayList<>();
    if (mergeRequestFactLagging) {
      laggingDomains.add("合并请求事实表");
    }
    if (issueFactLagging) {
      laggingDomains.add("议题事实表");
    }
    if (integrationTestFactLagging) {
      laggingDomains.add("集成测试事实表");
    }
    return "镜像数据新于" + String.join("、", laggingDomains);
  }

  private LocalDateTime max(LocalDateTime left, LocalDateTime right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.isAfter(right) ? left : right;
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private record LatestSyncView(
      SyncStatus status,
      String message,
      LocalDateTime finishedAt) {
  }

  private record ActiveSyncView(
      SyncStatus status,
      String message,
      LocalDateTime startedAt,
      boolean factRefreshActive) {

    private static ActiveSyncView idle() {
      return new ActiveSyncView(SyncStatus.IDLE, "", null, false);
    }
  }
}
