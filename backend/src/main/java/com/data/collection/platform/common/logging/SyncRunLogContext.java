package com.data.collection.platform.common.logging;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

public final class SyncRunLogContext {
  private SyncRunLogContext() {
  }

  /** 为一个持久化同步运行建立日志关联上下文。 */
  public static Scope openRun(SyncRun run, GitlabSyncConfig config) {
    Scope scope = new Scope();
    String externalRunId = run == null ? null : run.getRunId();
    scope.put(
        "traceId",
        externalRunId == null || externalRunId.isBlank()
            ? UUID.randomUUID().toString()
            : externalRunId);
    scope.put("runId", externalRunId);
    scope.put("runDbId", run == null ? null : stringValue(run.getId()));
    scope.put("configId", run == null ? configId(config) : stringValue(run.getConfigId()));
    scope.put(
        "sourceInstance",
        run == null ? sourceInstance(config) : normalizeSourceInstance(run.getSourceInstance()));
    scope.put("runType", run == null || run.getRunType() == null ? null : run.getRunType().name());
    scope.put("scope", run == null ? null : run.getExclusiveScope());
    scope.put("gitlabUrl", resolveGitlabUrl(config));
    return scope;
  }

  /** 为一次配置级操作建立日志关联上下文。 */
  public static Scope openConfig(GitlabSyncConfig config, String taskType) {
    return openConfig(config, taskType, "");
  }

  /** 为一次带互斥范围的配置级操作建立日志关联上下文。 */
  public static Scope openConfig(GitlabSyncConfig config, String taskType, String scopeValue) {
    Scope scope = new Scope();
    scope.put("traceId", UUID.randomUUID().toString());
    scope.put("runId", "");
    scope.put("runDbId", "");
    scope.put("configId", configId(config));
    scope.put("sourceInstance", sourceInstance(config));
    scope.put("scope", scopeValue == null ? "" : scopeValue);
    scope.put("gitlabUrl", resolveGitlabUrl(config));
    scope.put("runType", taskType == null ? "" : taskType);
    return scope;
  }

  /** 为当前表分页任务补充日志关联字段。 */
  public static Scope openTask(SyncRunTableTask task) {
    Scope scope = new Scope();
    scope.put("taskId", task == null ? null : stringValue(task.getId()));
    scope.put("sourceTable", task == null ? null : task.getSourceTable());
    scope.put("configId", task == null ? null : stringValue(task.getConfigId()));
    scope.put(
        "sourceInstance",
        task == null ? null : normalizeSourceInstance(task.getSourceInstance()));
    return scope;
  }

  /** 为当前日志范围补充动作名称。 */
  public static Scope action(String action) {
    Scope scope = new Scope();
    scope.put("action", action == null ? "" : action);
    return scope;
  }

  /** 为当前日志范围补充业务对象编号。 */
  public static Scope object(String objectId) {
    Scope scope = new Scope();
    scope.put("objectId", objectId == null ? "" : objectId);
    return scope;
  }

  private static String resolveGitlabUrl(GitlabSyncConfig config) {
    if (config == null || config.getSourceMode() == null) {
      return "";
    }
    if (config.getSourceMode() == SourceMode.DOCKER) {
      String container = config.getDockerContainerName() == null ? "" : config.getDockerContainerName().trim();
      String dbName = config.getDbName() == null || config.getDbName().isBlank() ? "gitlabhq_production" : config.getDbName().trim();
      return "docker://" + container + "/" + dbName;
    }
    String host = config.getDbHost() == null ? "" : config.getDbHost().trim();
    Integer port = config.getDbPort() == null ? 5432 : config.getDbPort();
    String dbName = config.getDbName() == null || config.getDbName().isBlank() ? "gitlabhq_production" : config.getDbName().trim();
    return "postgresql://" + host + ":" + port + "/" + dbName;
  }

  private static String configId(GitlabSyncConfig config) {
    return config == null ? "" : stringValue(config.getId());
  }

  private static String sourceInstance(GitlabSyncConfig config) {
    return config == null ? "default" : normalizeSourceInstance(config.getSourceInstance());
  }

  private static String normalizeSourceInstance(String sourceInstance) {
    return sourceInstance == null || sourceInstance.isBlank() ? "default" : sourceInstance.trim();
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  public static final class Scope implements AutoCloseable {
    private final Map<String, String> previousValues = new LinkedHashMap<>();

    private void put(String key, String value) {
      previousValues.putIfAbsent(key, MDC.get(key));
      MDC.put(key, value == null ? "" : value);
    }

    @Override
    public void close() {
      previousValues.forEach(
          (key, previousValue) -> {
            if (previousValue == null) {
              MDC.remove(key);
            } else {
              MDC.put(key, previousValue);
            }
          });
    }
  }
}
