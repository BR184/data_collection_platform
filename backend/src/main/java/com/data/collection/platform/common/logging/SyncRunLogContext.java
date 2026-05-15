package com.data.collection.platform.common.logging;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;

public final class SyncRunLogContext {
  private SyncRunLogContext() {
  }

  public static Scope openRun(Long runId, String traceId, GitlabSyncConfig config, String runType, String scopeValue) {
    Scope scope = new Scope();
    scope.put("traceId", traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
    scope.put("runId", runId == null ? "" : String.valueOf(runId));
    scope.put("scope", scopeValue == null ? "" : scopeValue);
    scope.put("gitlabUrl", resolveGitlabUrl(config));
    scope.put("runType", runType == null ? "" : runType);
    return scope;
  }

  public static Scope openConfig(GitlabSyncConfig config, String taskType) {
    return openConfig(config, taskType, "");
  }

  public static Scope openConfig(GitlabSyncConfig config, String taskType, String scopeValue) {
    Scope scope = new Scope();
    scope.put("traceId", UUID.randomUUID().toString());
    scope.put("runId", "");
    scope.put("scope", scopeValue == null ? "" : scopeValue);
    scope.put("gitlabUrl", resolveGitlabUrl(config));
    scope.put("runType", taskType == null ? "" : taskType);
    return scope;
  }

  public static Scope action(String action) {
    Scope scope = new Scope();
    scope.put("action", action == null ? "" : action);
    return scope;
  }

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

  public static final class Scope implements AutoCloseable {
    private final List<String> keys = new ArrayList<>();

    private void put(String key, String value) {
      MDC.put(key, value == null ? "" : value);
      keys.add(key);
    }

    @Override
    public void close() {
      for (int index = keys.size() - 1; index >= 0; index--) {
        MDC.remove(keys.get(index));
      }
    }
  }
}
