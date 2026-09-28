package com.data.collection.platform.service.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 验证备份执行 fencing 迁移可从既有版本升级，并保留旧运行权状态。 */
class BackupExecutionFencingMigrationTest {
  private static final String MIGRATION_FILE = "V20260924_01__backup_execution_fencing.sql";

  private static PostgreSQLContainer<?> postgres;
  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void startIsolatedDatabase() {
    postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("backup_migration_test")
            .withUsername("test")
            .withPassword("test");
    try {
      postgres.start();
    } catch (RuntimeException unavailable) {
      postgres.close();
      Assumptions.assumeTrue(false, "当前环境没有可用的隔离 PostgreSQL：" + unavailable.getMessage());
      return;
    }
    dataSource =
        new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @AfterAll
  static void stopIsolatedDatabase() {
    if (postgres != null) {
      postgres.close();
    }
  }

  @BeforeEach
  void createPreviousVersionSchema() {
    jdbcTemplate.execute("drop schema public cascade");
    jdbcTemplate.execute("create schema public");
    jdbcTemplate.execute(
        "create table backup_state (id integer primary key, active_run_id bigint, "
            + "lease_expires_at timestamptz, updated_at timestamptz not null default now())");
  }

  @Test
  void test_emptyPreviousVersionState_migrationAddsFencingSchema(@TempDir Path tempDir)
      throws IOException {
    migrateFromPreviousVersion(tempDir);

    assertThat(jdbcTemplate.queryForList(
            "select column_name from information_schema.columns "
                + "where table_schema = 'public' and table_name = 'backup_state'",
            String.class))
        .contains("execution_token", "execution_revoked", "process_id", "process_started_at");
    assertThat(jdbcTemplate.queryForObject("select count(*) from backup_state", Integer.class)).isZero();
  }

  @Test
  void test_existingActiveRun_migrationBackfillsLegacyExecutionOwner(@TempDir Path tempDir)
      throws IOException {
    jdbcTemplate.update("insert into backup_state (id, active_run_id) values (1, 123), (2, null)");

    migrateFromPreviousVersion(tempDir);

    assertThat(jdbcTemplate.queryForMap(
            "select active_run_id, execution_token, execution_revoked from backup_state where id = 1"))
        .containsEntry("active_run_id", 123L)
        .containsEntry("execution_token", "legacy-123")
        .containsEntry("execution_revoked", false);
    assertThat(jdbcTemplate.queryForMap(
            "select active_run_id, execution_token, execution_revoked from backup_state where id = 2"))
        .containsEntry("active_run_id", null)
        .containsEntry("execution_token", null)
        .containsEntry("execution_revoked", false);
    assertThatThrownBy(() -> jdbcTemplate.update(
            "update backup_state set active_run_id = 456 where id = 2"))
        .hasRootCauseInstanceOf(SQLException.class);
  }

  private static void migrateFromPreviousVersion(Path tempDir) throws IOException {
    Path migrationDirectory = Files.createDirectory(tempDir.resolve("migration"));
    Path source = Path.of("src/main/resources/db/migration", MIGRATION_FILE);
    Files.copy(source, migrationDirectory.resolve(MIGRATION_FILE));
    Flyway.configure()
        .dataSource(dataSource)
        .locations("filesystem:" + migrationDirectory.toAbsolutePath())
        .baselineOnMigrate(true)
        .baselineVersion("20260903.01")
        .load()
        .migrate();
  }
}
