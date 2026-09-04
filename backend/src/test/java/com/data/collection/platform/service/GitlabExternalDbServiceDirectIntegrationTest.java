package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ResourceLock("default-time-zone")
class GitlabExternalDbServiceDirectIntegrationTest {
  private static TimeZone originalTimeZone;

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("gitlabhq_production")
          .withUsername("gitlab")
          .withPassword("secret");

  private final GitlabExternalDbService service = newService();

  private static GitlabExternalDbService newService() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    com.data.collection.platform.common.JsonUtils jsonUtils =
        new com.data.collection.platform.common.JsonUtils(new ObjectMapper());
    GitlabSourceConnectionSettings connectionSettings = new GitlabSourceConnectionSettings(properties);
    GitlabSourceQueryRetryPolicy retryPolicy = new GitlabSourceQueryRetryPolicy(properties);
    GitlabDirectJdbcExecutor directJdbcExecutor =
        new GitlabDirectJdbcExecutor(
            connectionSettings,
            retryPolicy,
            new GitlabJdbcValueNormalizer(),
            new com.data.collection.platform.service.sync.SyncThreadBudgetResolver(properties));
    GitlabDockerPsqlExecutor dockerPsqlExecutor =
        new GitlabDockerPsqlExecutor(properties, connectionSettings, retryPolicy, new ObjectMapper());
    GitlabSourceQueryDispatcher queryDispatcher =
        new GitlabSourceQueryDispatcher(directJdbcExecutor, dockerPsqlExecutor);
    GitlabSourceMetadataSupport metadataSupport = new GitlabSourceMetadataSupport();
    return new GitlabExternalDbService(
        new GitlabSourceScanSqlBuilder(jsonUtils),
        new GitlabPrimaryKeyExistenceQueryBuilder(),
        new GitlabAuthoritativeScopeQueryBuilder(),
        directJdbcExecutor,
        queryDispatcher,
        new GitlabSourceSchemaDiscoveryService(queryDispatcher::query, metadataSupport),
        metadataSupport);
  }

  @BeforeAll
  static void setUpSchema() throws Exception {
    originalTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")));
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table issues (
            id bigint primary key,
            title text not null,
            updated_at timestamp not null,
            wall_time timestamp,
            instant_time timestamptz
          )
          """);
      statement.execute(
          """
          insert into issues(id, title, updated_at, wall_time, instant_time) values
            (101,
             'first issue',
             timestamp '2026-01-01 09:00:00',
             timestamp '2026-08-06 03:20:22.840104',
             timestamptz '2026-08-06 11:20:22.840104+08:00'),
            (202, 'second issue', timestamp '2026-01-01 11:00:00', null, null)
          """);
      statement.execute(
          """
          create table audit_events (
            event_name text not null,
            payload text
          )
          """);
      statement.execute("create role gitlab_readonly login password 'readonly_secret'");
      statement.execute("grant connect on database gitlabhq_production to gitlab_readonly");
      statement.execute("grant usage on schema public to gitlab_readonly");
      statement.execute("grant select on all tables in schema public to gitlab_readonly");
    }
  }

  @AfterAll
  static void restoreTimeZone() {
    TimeZone.setDefault(originalTimeZone);
  }

  @Test
  void directModeShouldDiscoverPrimaryKeysForReadOnlyUsers() {
    GitlabSyncConfig config = directConfig("gitlab_readonly", "readonly_secret");

    assertThat(service.discoverTables(config, java.util.Map.of(), List.of("issues")))
        .anySatisfy(
            option -> {
              assertThat(option.tableName()).isEqualTo("issues");
              assertThat(option.primaryKey()).isEqualTo("id");
              assertThat(option.updatedAtColumn()).isEqualTo("updated_at");
            });
  }

  @Test
  void directModeShouldDiagnoseReadOnlyMetadataAccess() {
    GitlabSyncConfig config = directConfig("gitlab_readonly", "readonly_secret");
    TableWhitelistOption issues = new TableWhitelistOption(
        "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true);

    GitlabSourceMetadataDiagnosticsResponse diagnostics = service.inspectSourceMetadata(config, List.of(issues));

    assertThat(diagnostics.metadataOk()).isTrue();
    assertThat(diagnostics.sourceTableCount()).isEqualTo(2);
    assertThat(diagnostics.primaryKeyTableCount()).isEqualTo(1);
    assertThat(diagnostics.missingPrimaryKeyTableCount()).isEqualTo(1);
    assertThat(diagnostics.missingUpdatedAtTableCount()).isEqualTo(1);
    assertThat(diagnostics.sourceTables())
        .anySatisfy(table -> {
          assertThat(table.tableName()).isEqualTo("issues");
          assertThat(table.rowStrategy()).isEqualTo("INCREMENTAL");
          assertThat(table.schemaFingerprint()).hasSize(16);
        })
        .anySatisfy(table -> {
          assertThat(table.tableName()).isEqualTo("audit_events");
          assertThat(table.primaryKey()).isNull();
          assertThat(table.rowStrategy()).isEqualTo("FULL_ONLY");
        });
  }

  @Test
  void directModeShouldQueryGitlabPostgresViaJdbc() {
    GitlabSyncConfig config = directConfig();
    TableWhitelistOption issues = new TableWhitelistOption(
        "issues", "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET, true);

    service.testConnection(config);

    assertThat(service.discoverTables(config, java.util.Map.of(), List.of("issues")))
        .anySatisfy(
            option -> {
              assertThat(option.tableName()).isEqualTo("issues");
              assertThat(option.primaryKey()).isEqualTo("id");
              assertThat(option.updatedAtColumn()).isEqualTo("updated_at");
              assertThat(option.recommended()).isTrue();
            });
    assertThat(service.fullTableScan(config, issues)).hasSize(2);
    assertThat(service.incrementalScan(config, issues, LocalDateTime.of(2025, 1, 1, 0, 0))).hasSize(2);
    assertThat(service.preciseScan(config, issues, java.util.Map.of("id", 202L)))
        .singleElement()
        .satisfies(row -> assertThat(row.get("title")).isEqualTo("second issue"));
  }

  @Test
  void test_direct_mode_preserves_wall_time_and_normalizes_instant_to_utc() {
    TableWhitelistOption issues =
        new TableWhitelistOption(
            "issues",
            "issues",
            "id",
            "updated_at",
            SourceCursorStrategy.TIMESTAMP_KEYSET,
            false);

    assertThat(service.preciseScan(directConfig(), issues, java.util.Map.of("id", 101L)))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("wall_time"))
                  .isEqualTo(LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000));
              assertThat(row.get("instant_time"))
                  .isEqualTo(LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000));
            });
  }

  private GitlabSyncConfig directConfig() {
    return directConfig(POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private GitlabSyncConfig directConfig(String username, String password) {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost(POSTGRES.getHost());
    config.setDbPort(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
    config.setDbName(POSTGRES.getDatabaseName());
    config.setDbUsername(username);
    config.setDbPassword(password);
    return config;
  }
}
