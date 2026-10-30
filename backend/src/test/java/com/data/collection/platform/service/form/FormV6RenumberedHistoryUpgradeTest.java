package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * M01 旧编号历史：本分支曾把退役权限迁移登记为 {@code 20261022.03}，主线同版本是闭包进度迁移。
 *
 * <p>合并后的迁移集必须对这类库显式失败，不能靠 {@code repair}、改写已执行行或静默跳过来“升级”。
 */
@Testcontainers(disabledWithoutDocker = true)
class FormV6RenumberedHistoryUpgradeTest {

  private static final String PRE_RENUMBERED_HEAD = "20261022.02";

  @Container
  private final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @Test
  void mergedMigrateRejectsOldRenumberedHistoryWithoutRewritingIt() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    MigrateResult before = flyway(dataSource, PRE_RENUMBERED_HEAD).migrate();
    int oldRetireChecksum = retireMigrationChecksum(flyway(dataSource, null));
    jdbc.update(
        "insert into flyway_schema_history"
            + " (installed_rank, version, description, type, script, checksum, installed_by,"
            + " execution_time, success) values (?, '20261022.03',"
            + " 'retire legacy form permissions', 'SQL',"
            + " 'V20261022_03__retire_legacy_form_permissions.sql', ?, 'old-form-db', 0, true)",
        before.migrationsExecuted + 1, oldRetireChecksum);

    assertThatThrownBy(() -> flyway(dataSource, null).migrate())
        .hasMessageContaining("checksum");

    assertThat(jdbc.queryForObject(
        "select description from flyway_schema_history where version = '20261022.03'",
        String.class)).isEqualTo("retire legacy form permissions");
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version like '2026%' and version >"
            + " '20261022.03'", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select to_regclass('form_record_locations')", String.class))
        .isNull();
  }

  /** 旧库登记的 20261022.03 内容与当前 20261022.04 相同，用其校验和还原历史事实。 */
  private static int retireMigrationChecksum(Flyway flyway) {
    for (MigrationInfo info : flyway.info().all()) {
      if (info.getVersion() != null
          && "20261022.04".equals(info.getVersion().getVersion())
          && info.getChecksum() != null) {
        return info.getChecksum();
      }
    }
    throw new IllegalStateException("未解析到退役权限迁移 20261022.04");
  }

  private Flyway flyway(DriverManagerDataSource dataSource, String target) {
    org.flywaydb.core.api.configuration.FluentConfiguration configuration =
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    if (target != null) {
      configuration = configuration.target(target);
    }
    return configuration.load();
  }
}
