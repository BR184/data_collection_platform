package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class GitlabExternalDbServiceDirectIntegrationTest {
  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("gitlabhq_production")
          .withUsername("gitlab")
          .withPassword("secret");

  private final GitlabExternalDbService service =
      new GitlabExternalDbService(new GitlabMirrorProperties(), new ObjectMapper());

  @BeforeAll
  static void setUpSchema() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table issues (
            id bigint primary key,
            title text not null,
            updated_at timestamp not null
          )
          """);
      statement.execute(
          """
          insert into issues(id, title, updated_at) values
            (101, 'first issue', timestamp '2026-01-01 09:00:00'),
            (202, 'second issue', timestamp '2026-01-01 11:00:00')
          """);
    }
  }

  @Test
  void directModeShouldQueryGitlabPostgresViaJdbc() {
    GitlabSyncConfig config = directConfig();
    TableWhitelistOption issues = new TableWhitelistOption("issues", "issues", "id", "updated_at", true);

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
    assertThat(service.preciseScan(config, issues, "id", 202L))
        .singleElement()
        .satisfies(row -> assertThat(row.get("title")).isEqualTo("second issue"));
  }

  private GitlabSyncConfig directConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setSourceMode(SourceMode.DIRECT);
    config.setDbHost(POSTGRES.getHost());
    config.setDbPort(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
    config.setDbName(POSTGRES.getDatabaseName());
    config.setDbUsername(POSTGRES.getUsername());
    config.setDbPassword(POSTGRES.getPassword());
    return config;
  }
}
