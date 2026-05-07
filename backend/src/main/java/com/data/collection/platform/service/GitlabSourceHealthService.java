package com.data.collection.platform.service;

import com.data.collection.platform.entity.GitlabSourceHealthResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.GitlabSyncLog;
import com.data.collection.platform.entity.GitlabSyncTask;
import com.data.collection.platform.entity.SyncStatus;
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

  private String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }
}
