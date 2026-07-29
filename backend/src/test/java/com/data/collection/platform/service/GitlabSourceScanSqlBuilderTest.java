package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSourceScanSqlBuilderTest {
  private GitlabSourceScanSqlBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new GitlabSourceScanSqlBuilder(new JsonUtils(new ObjectMapper()));
  }

  @Test
  void shouldQuoteSourceTableNameForFullScans() {
    TableWhitelistOption option = option("Issue Events", "id", "Updated At", SourceCursorStrategy.PRIMARY_KEY_KEYSET);

    String sql = builder.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldBuildTypedPrimaryKeyCursorSqlForFullScans() {
    TableWhitelistOption option = option(
        "label_links", "label_id,target_id,target_type", null, SourceCursorStrategy.NONE);
    SourceTableSchema schema = labelLinkSchema();

    String sql = builder.buildFullCursorScanSql(
        option, schema, "[\"1\",\"101\",\"Issue\"]", 100);

    assertThat(sql)
        .contains("(\"label_id\", \"target_id\", \"target_type\") > ("
            + "'1'::bigint, '101'::bigint, 'Issue'::text)")
        .contains("order by \"label_id\" asc, \"target_id\" asc, \"target_type\" asc")
        .contains("limit 100")
        .doesNotContain("concat_ws", "md5", "::text asc");
  }

  @Test
  void shouldBuildTimestampKeysetSqlInsideFixedWindow() {
    TableWhitelistOption option = option(
        "issues", "id", "updated_at", SourceCursorStrategy.TIMESTAMP_KEYSET);
    SourceTableSchema schema = issueSchema();

    String sql = builder.buildCursorBatchScanSql(
        option,
        schema,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        LocalDateTime.of(2026, 1, 2, 4, 0),
        LocalDateTime.of(2026, 1, 2, 3, 5, 6),
        "[\"101\"]",
        200);

    assertThat(sql)
        .contains("\"updated_at\" > timestamp '2026-01-02 03:04:05.000000'")
        .contains("\"updated_at\" <= timestamp '2026-01-02 04:00:00.000000'")
        .contains("(\"updated_at\", \"id\") > (timestamp '2026-01-02 03:05:06.000000', '101'::bigint)")
        .contains("order by \"updated_at\" asc, \"id\" asc limit 200");
  }

  @Test
  void shouldBuildPrimaryKeyKeysetSqlInsideFixedWindow() {
    TableWhitelistOption option = option(
        "resource_label_events", "id", "created_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET);
    SourceTableSchema schema = new SourceTableSchema(
        "resource_label_events",
        List.of("id"),
        "created_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("created_at", "timestamp without time zone", false, 2)));

    String sql = builder.buildCursorBatchScanSql(
        option,
        schema,
        LocalDateTime.of(2026, 1, 1, 0, 0),
        LocalDateTime.of(2026, 1, 2, 0, 0),
        null,
        "[\"90000\"]",
        500);

    assertThat(sql)
        .contains("\"created_at\" > timestamp '2026-01-01 00:00:00.000000'")
        .contains("\"created_at\" <= timestamp '2026-01-02 00:00:00.000000'")
        .contains("and (\"id\") > ('90000'::bigint)")
        .contains("order by \"id\" asc limit 500")
        .doesNotContain("md5", "concat_ws", "offset");
  }

  @Test
  void shouldBuildIndexFriendlyExistingPrimaryKeysSqlForCompositeKeys() {
    TableWhitelistOption option = option(
        "label_links", "label_id,target_id,target_type", null, SourceCursorStrategy.NONE);

    String sql = builder.buildExistingPrimaryKeysSql(
        option,
        List.of("label_id", "target_id", "target_type"),
        List.of(Map.of("label_id", "1", "target_id", "101", "target_type", "Issue")));

    assertThat(sql)
        .contains("where (\"label_id\" = '1' and \"target_id\" = '101' and \"target_type\" = 'Issue')")
        .doesNotContain("\"label_id\"::text =", "\"target_id\"::text =");
  }

  @Test
  void shouldEscapeQuotesInSourceIdentifiers() {
    TableWhitelistOption option = option(
        "issue\"events", "id", "updated_at", SourceCursorStrategy.PRIMARY_KEY_KEYSET);

    String sql = builder.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"issue\"\"events\"");
  }

  private TableWhitelistOption option(
      String tableName,
      String primaryKey,
      String updatedAtColumn,
      SourceCursorStrategy cursorStrategy) {
    return new TableWhitelistOption(
        tableName, tableName, primaryKey, updatedAtColumn, cursorStrategy, true);
  }

  private SourceTableSchema issueSchema() {
    return new SourceTableSchema(
        "issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 2)));
  }

  private SourceTableSchema labelLinkSchema() {
    return new SourceTableSchema(
        "label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "text", false, 3)));
  }
}
