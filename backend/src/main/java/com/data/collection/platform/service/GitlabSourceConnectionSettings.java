package com.data.collection.platform.service;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;

class GitlabSourceConnectionSettings {
  private final GitlabMirrorProperties properties;

  GitlabSourceConnectionSettings(GitlabMirrorProperties properties) {
    this.properties = properties;
  }

  int resolveExternalQueryTimeoutSeconds() {
    return Math.max(1, properties.getExternalQueryTimeoutSeconds());
  }

  String buildJdbcUrl(GitlabSyncConfig config) {
    int timeoutSeconds = resolveExternalQueryTimeoutSeconds();
    return "jdbc:postgresql://%s:%d/%s?connectTimeout=%d&socketTimeout=%d&tcpKeepAlive=true".formatted(
        config.getDbHost(),
        config.getDbPort(),
        normalizeDbName(config),
        timeoutSeconds,
        timeoutSeconds);
  }

  String directDataSourceKey(GitlabSyncConfig config) {
    return "%s:%d/%s:%s:%s".formatted(
        config.getDbHost(),
        config.getDbPort(),
        normalizeDbName(config),
        normalizeDbUser(config),
        Integer.toHexString(java.util.Objects.toString(config.getDbPassword(), "").hashCode()));
  }

  String normalizeDbName(GitlabSyncConfig config) {
    return config.getDbName() == null || config.getDbName().isBlank()
        ? "gitlabhq_production"
        : config.getDbName().trim();
  }

  String normalizeDbUser(GitlabSyncConfig config) {
    return config.getDbUsername() == null || config.getDbUsername().isBlank()
        ? "gitlab"
        : config.getDbUsername().trim();
  }

  String buildDockerPsqlScript(GitlabSyncConfig config, String sql) {
    return """
        gitlab-psql -d "%s" -At <<'SQL'
        %s;
        SQL
        """.formatted(
        sanitizeShell(normalizeDbName(config)),
        sql);
  }

  private String sanitizeShell(String text) {
    return text.replace("\"", "\\\"");
  }
}
