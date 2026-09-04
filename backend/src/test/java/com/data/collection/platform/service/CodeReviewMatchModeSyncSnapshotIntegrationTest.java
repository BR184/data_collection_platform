package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 兼容模式 MySQL 代码走查同步的落地语义验证：raw 行按 (table_name, row_key) 增量合并；
 * 代码走查表通过装载表原子换名接管（无唯一键、同一 MR 允许多条走查记录），换名后
 * 新表自带全部性能索引且装载表/退役表不残留。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeReviewMatchModeSyncSnapshotIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private JdbcTemplate jdbcTemplate;
  private CodeReviewMatchModeSyncService service;

  @BeforeAll
  void setUp() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    when(jsonUtils.toJson(any())).thenReturn("[]");
    service =
        new CodeReviewMatchModeSyncService(
            mock(CodeReviewMatchModeConfigService.class),
            mock(CodeReviewMatchModeSwitchService.class),
            jdbcTemplate,
            jsonUtils,
            mock(TransactionTemplate.class));
  }

  @Test
  void snapshotSwapReplacesCodeReviewRowsAndKeepsIndexes() {
    service.createTempTables(true);
    jdbcTemplate.update(
        """
        insert into code_review_match_mode_records_loading (
          source_instance, project_id, merge_request_id, merge_request_iid, title, synced_at
        ) values
          ('cc', 1, 101, 1, '走查甲', current_timestamp),
          ('cc', 1, 102, 2, '走查乙', current_timestamp)
        """);

    service.replaceSnapshots(true, summary("cc.spider_crowncad_data", 2));

    assertThat(count("code_review_match_mode_records")).isEqualTo(2);
    assertThat(tableExists("code_review_match_mode_records_loading")).isFalse();
    assertThat(tableExists("code_review_match_mode_records_retiring")).isFalse();
    long indexCount = jdbcTemplate.queryForObject(
        "select count(*) from pg_indexes where tablename = 'code_review_match_mode_records'",
        Long.class);
    assertThat(indexCount).isGreaterThanOrEqualTo(3);
  }

  @Test
  void rawRowsMergeUpsertsChangedKeysAndDeletesMissingKeys() throws InterruptedException {
    jdbcTemplate.update(
        """
        insert into legacy_mysql_imported_rows (table_name, row_key, raw_payload, synced_at)
        values ('cc.t1', 'k1', '{"v":1}'::jsonb, current_timestamp),
               ('cc.t1', 'k9', '{"v":1}'::jsonb, current_timestamp)
        """);
    Map<String, Object> firstRoundK1 = rawRow("k1");

    Thread.sleep(20);
    service.createTempTables(false);
    jdbcTemplate.update(
        """
        insert into legacy_mysql_imported_rows_loading (table_name, row_key, raw_payload, synced_at)
        values ('cc.t1', 'k1', '{"v":1}'::jsonb, current_timestamp),
               ('cc.t1', 'k2', '{"v":2}'::jsonb, current_timestamp)
        """);
    service.replaceSnapshots(false, summary("cc.t1", 2));

    assertThat(rawRow("k1").get("synced_at")).isEqualTo(firstRoundK1.get("synced_at"));
    assertThat(rawRow("k2")).isNotNull();
    assertThat(rawRow("k9")).isNull();
  }

  private CodeReviewMatchModeSyncService.ImportSummary summary(String tableName, int count) {
    CodeReviewMatchModeSyncService.ImportSummary summary =
        new CodeReviewMatchModeSyncService.ImportSummary();
    summary.add(tableName, count, List.of());
    return summary;
  }

  private Map<String, Object> rawRow(String rowKey) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select synced_at from legacy_mysql_imported_rows where table_name = 'cc.t1' and row_key = ?",
            rowKey);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private long count(String tableName) {
    return jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
  }

  private boolean tableExists(String tableName) {
    Boolean exists = jdbcTemplate.queryForObject(
        "select to_regclass(?) is not null", Boolean.class, tableName);
    return Boolean.TRUE.equals(exists);
  }
}
