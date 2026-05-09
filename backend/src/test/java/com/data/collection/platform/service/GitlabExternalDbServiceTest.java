package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabExternalDbServiceTest {

  private GitlabExternalDbService service;

  @BeforeEach
  void setUp() {
    service = new GitlabExternalDbService(new GitlabMirrorProperties(), new ObjectMapper());
  }

  @Test
  void shouldPreferUpdatedAtVariantsOverCreatedAtVariants() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "CreatedAt", "UpdatedAt"));

    assertThat(resolved).isEqualTo("UpdatedAt");
  }

  @Test
  void shouldFallbackToCreatedAtVariantWhenUpdatedAtIsMissing() {
    String resolved = service.resolveUpdatedAtColumn(List.of("id", "createdAt", "name"));

    assertThat(resolved).isEqualTo("createdAt");
  }

  @Test
  void shouldRecognizeCommonSnakeCaseAndCamelCaseVariants() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updated_at"))).isEqualTo("updated_at");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "updatedAt"))).isEqualTo("updatedAt");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "gmt_modified"))).isEqualTo("gmt_modified");
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "create_time"))).isEqualTo("create_time");
  }

  @Test
  void shouldReturnNullWhenNoCandidateExists() {
    assertThat(service.resolveUpdatedAtColumn(List.of("id", "name", "description"))).isNull();
  }

  @Test
  void shouldMarkTablesWithoutUpdatedAtAsFullOnly() {
    assertThat(service.resolveRowStrategy("updated_at")).isEqualTo("INCREMENTAL");
    assertThat(service.resolveRowStrategy(null)).isEqualTo("FULL_ONLY");
    assertThat(service.resolveRowStrategy("")).isEqualTo("FULL_ONLY");
  }

  @Test
  void shouldBuildStableSchemaFingerprintForSourceMetadata() {
    SourceTableSchema schema = new SourceTableSchema(
        "issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));

    assertThat(service.buildSchemaFingerprint(schema)).isEqualTo(service.buildSchemaFingerprint(schema));
    assertThat(service.buildSchemaFingerprint(schema)).hasSize(16);
  }

  @Test
  void shouldBuildJdbcUrlWithTimeoutsAndTcpKeepAliveForUnstableNetwork() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryTimeoutSeconds(45);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setDbHost("10.0.0.8");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");

    String url = service.buildJdbcUrl(config);

    assertThat(url)
        .isEqualTo("jdbc:postgresql://10.0.0.8:5432/gitlabhq_production?connectTimeout=45&socketTimeout=45&tcpKeepAlive=true");
  }

  @Test
  void shouldQuoteSourceTableNameForFullScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldQuoteSourceTableAndTimeColumnForWindowScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildTimeWindowScanSql(option, LocalDateTime.of(2026, 1, 2, 3, 4, 5));

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"Issue Events\" where \"Updated At\" >= timestamp '2026-01-01 19:04:05'");
  }

  @Test
  void shouldBuildCursorBatchScanSqlWithUpdatedAtAndPrimaryKeyCursor() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "Issue ID", "Updated At", false);

    String sql = service.buildCursorBatchScanSql(
        option,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        LocalDateTime.of(2026, 1, 2, 3, 5, 6),
        "101",
        200);

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"Issue Events\" where \"Updated At\" >= timestamp '2026-01-01 19:04:05' "
            + "and (\"Updated At\" > timestamp '2026-01-01 19:05:06' or (\"Updated At\" = timestamp '2026-01-01 19:05:06' "
            + "and \"Issue ID\" > '101')) order by \"Updated At\" asc, \"Issue ID\" asc limit 200");
  }

  @Test
  void shouldBuildCursorBatchScanSqlWithoutCursorForFirstBatch() {
    TableWhitelistOption option =
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true);

    String sql = service.buildCursorBatchScanSql(
        option,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        null,
        null,
        0);

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"issues\" where \"updated_at\" >= timestamp '2026-01-01 19:04:05' "
            + "order by \"updated_at\" asc, \"id\" asc limit 1");
  }

  @Test
  void shouldBuildLightweightTableProbeSql() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "Issue ID", "Updated At", false);

    String sql = service.buildTableProbeSql(option);

    assertThat(sql)
        .isEqualTo("select count(*) as row_count,\n"
            + "       max(\"Updated At\") as max_updated_at,\n"
            + "       min(\"Issue ID\")::text as min_pk,\n"
            + "       max(\"Issue ID\")::text as max_pk\n"
            + "  from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldBuildLightweightTableProbeSqlWithoutUpdatedAt() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "id", null, true);

    String sql = service.buildTableProbeSql(option);

    assertThat(sql)
        .isEqualTo("select count(*) as row_count,\n"
            + "       null::timestamp as max_updated_at,\n"
            + "       min(\"id\")::text as min_pk,\n"
            + "       max(\"id\")::text as max_pk\n"
            + "  from \"public\".\"label_links\"");
  }

  @Test
  void shouldQuoteSourceTableAndLookupColumnForPreciseScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = service.buildPreciseScanSql(option, "Issue ID", 101L);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\" where \"Issue ID\" = 101");
  }

  @Test
  void shouldEscapeQuotesInSourceIdentifiers() {
    TableWhitelistOption option =
        new TableWhitelistOption("issue\"events", "issue\"events", "id", "updated_at", false);

    String sql = service.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"issue\"\"events\"");
  }

  @Test
  void shouldRetryTransientExternalQueryFailures() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    String result = service.executeExternalQueryWithRetry("test query", () -> {
      if (attempts.incrementAndGet() < 3) {
        throw new BizException("Connection reset by peer");
      }
      return "ok";
    });

    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
  }

  @Test
  void shouldUseExponentialBackoffWithJitterForExternalQueryRetries() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryDelayMs(1000);
    properties.setExternalQueryRetryMaxDelayMs(2500);
    service = new GitlabExternalDbService(properties, new ObjectMapper());

    long firstDelay = service.computeExternalQueryRetryDelayMs(1);
    long secondDelay = service.computeExternalQueryRetryDelayMs(2);
    long cappedDelay = service.computeExternalQueryRetryDelayMs(4);

    assertThat(firstDelay).isBetween(1000L, 1500L);
    assertThat(secondDelay).isBetween(2000L, 2500L);
    assertThat(cappedDelay).isBetween(2500L, 2500L);
  }

  @Test
  void shouldNotRetrySqlErrorsFromSourceDatabase() {
    GitlabMirrorProperties properties = new GitlabMirrorProperties();
    properties.setExternalQueryRetryAttempts(3);
    properties.setExternalQueryRetryDelayMs(0);
    service = new GitlabExternalDbService(properties, new ObjectMapper());
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(() -> service.executeExternalQueryWithRetry("test query", () -> {
      attempts.incrementAndGet();
      throw new BizException("ERROR: relation \"missing_table\" does not exist");
    })).isInstanceOf(BizException.class);

    assertThat(attempts).hasValue(1);
  }
}
