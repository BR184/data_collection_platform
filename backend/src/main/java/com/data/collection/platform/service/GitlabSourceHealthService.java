package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
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
  private final GitlabSyncTaskService taskService;
  private final GitlabSyncLogService logService;
  private final JdbcTemplate jdbcTemplate;

  public GitlabSourceHealthService(
      GitlabConfigService configService,
      GitlabSyncTaskService taskService,
      GitlabSyncLogService logService,
      JdbcTemplate jdbcTemplate) {
    this.configService = configService;
    this.taskService = taskService;
    this.logService = logService;
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
    GitlabSyncTask task = taskService.findDisplayTask(config.getId());
    GitlabSyncLog latestLog = logService.findLatest(config.getId());
    List<String> registeredMirrorTables = registeredMirrorTables(config.getId());
    int existingMirrorTables = countExistingTables(registeredMirrorTables);
    LocalDateTime latestMergeRequestFactUpdatedAt =
        latestFactUpdatedAt("merge_request_fact", sourceInstance);
    LocalDateTime latestIssueFactUpdatedAt = latestFactUpdatedAt("issue_fact", sourceInstance);
    LocalDateTime latestIntegrationTestFactUpdatedAt =
        latestFactUpdatedAt("integration_test_fact", sourceInstance);
    LocalDateTime latestFactUpdatedAt = latestOf(
        latestMergeRequestFactUpdatedAt,
        latestIssueFactUpdatedAt,
        latestIntegrationTestFactUpdatedAt);
    boolean mergeRequestFactLagging =
        isFactLayerLagging(latestLog, latestMergeRequestFactUpdatedAt, existingMirrorTables);
    boolean issueFactLagging =
        isFactLayerLagging(latestLog, latestIssueFactUpdatedAt, existingMirrorTables);
    boolean integrationTestFactLagging =
        isFactLayerLagging(latestLog, latestIntegrationTestFactUpdatedAt, existingMirrorTables);
    boolean factLayerLagging = mergeRequestFactLagging || issueFactLagging || integrationTestFactLagging;
    return new GitlabSourceHealthResponse(
        config.getId(),
        config.getName(),
        sourceInstance,
        config.isEnabled(),
        task == null ? SyncStatus.IDLE : task.getStatus(),
        task == null ? "" : task.getFinishedReason(),
        task == null ? null : task.getStartedAt(),
        latestLog == null ? null : latestLog.getStatus(),
        latestLog == null ? "" : latestLog.getMessage(),
        latestLog == null ? null : latestLog.getFinishedAt(),
        registeredMirrorTables.size(),
        existingMirrorTables,
        factLayerLagging,
        factLayerLagging ? factLayerMessage(
            mergeRequestFactLagging,
            issueFactLagging,
            integrationTestFactLagging) : "",
        latestFactUpdatedAt,
        mergeRequestFactLagging,
        issueFactLagging,
        integrationTestFactLagging,
        countFacts("merge_request_fact", sourceInstance),
        countFacts("issue_fact", sourceInstance),
        countFacts("integration_test_fact", sourceInstance),
        missingRequiredMirrorTables(sourceInstance));
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
      Long total =
          jdbcTemplate.queryForObject(
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

  private LocalDateTime latestOf(LocalDateTime... values) {
    LocalDateTime latest = null;
    for (LocalDateTime value : values) {
      if (value != null && (latest == null || value.isAfter(latest))) {
        latest = value;
      }
    }
    return latest;
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
      GitlabSyncLog latestLog, LocalDateTime latestFactUpdatedAt, int existingMirrorTables) {
    if (latestLog == null
        || latestLog.getStatus() != SyncStatus.SUCCESS
        || latestLog.getFinishedAt() == null
        || existingMirrorTables <= 0) {
      return false;
    }
    if (latestFactUpdatedAt == null) {
      return true;
    }
    return latestFactUpdatedAt.isBefore(latestLog.getFinishedAt().minusSeconds(5));
  }

  private String factLayerMessage(
      boolean mergeRequestFactLagging,
      boolean issueFactLagging,
      boolean integrationTestFactLagging) {
    List<String> laggingDomains = new ArrayList<>();
    if (mergeRequestFactLagging) {
      laggingDomains.add("\u4ee3\u7801\u8d70\u67e5\u4e8b\u5b9e");
    }
    if (issueFactLagging) {
      laggingDomains.add("\u8bae\u9898\u4e8b\u5b9e");
    }
    if (integrationTestFactLagging) {
      laggingDomains.add("\u96c6\u6210\u6d4b\u8bd5\u4e8b\u5b9e");
    }
    return "\u955c\u50cf\u5df2\u66f4\u65b0\uff0c\u4f46"
        + String.join("\u3001", laggingDomains)
        + "\u5c1a\u672a\u5237\u65b0\u5230\u6700\u65b0\u540c\u6b65\u65f6\u95f4\uff0c"
        + "\u9875\u9762\u7edf\u8ba1\u53ef\u80fd\u4ecd\u662f\u65e7\u6570\u636e";
  }

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
