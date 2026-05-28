package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import org.junit.jupiter.api.Test;

class GitlabSourceConnectionSettingsTest {
  @Test
  void shouldBuildJdbcUrlWithDefaultsAndTimeouts() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(45);
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(properties);
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);

    String url = settings.buildJdbcUrl(config);

    assertThat(url)
        .isEqualTo("jdbc:postgresql://10.0.0.8:5432/gitlabhq_production?connectTimeout=45&socketTimeout=45&tcpKeepAlive=true");
  }

  @Test
  void shouldUseOneSecondAsMinimumExternalQueryTimeout() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(0);
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(properties);

    assertThat(settings.resolveExternalQueryTimeoutSeconds()).isEqualTo(1);
  }

  @Test
  void shouldBuildStableDirectDataSourceKeyFromNormalizedConnectionFields() {
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(new GitlabMirrorProperties());
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("  gitlabhq_production  ");
    config.setDbUsername("  gitlab_readonly  ");
    config.setDbPassword("secret");

    String key = settings.directDataSourceKey(config);

    assertThat(key)
        .isEqualTo("10.0.0.8:5432/gitlabhq_production:gitlab_readonly:"
            + Integer.toHexString("secret".hashCode()));
  }

  @Test
  void shouldBuildDockerPsqlScriptWithEscapedDatabaseName() {
    GitlabSourceConnectionSettings settings = new GitlabSourceConnectionSettings(new GitlabMirrorProperties());
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbName("gitlab\"prod");

    String script = settings.buildDockerPsqlScript(config, "select 1");

    assertThat(script)
        .contains("gitlab-psql -d \"gitlab\\\"prod\" -At <<'SQL'")
        .contains("select 1;")
        .contains("SQL");
  }
}
