package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitlabSourceScanSqlBuilderTest {
  private GitlabSourceScanSqlBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new GitlabSourceScanSqlBuilder();
  }

  @Test
  void shouldQuoteSourceTableNameForFullScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "id", "Updated At", false);

    String sql = builder.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"Issue Events\"");
  }

  @Test
  void shouldBuildPrimaryKeyCursorSqlForFullScans() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", null, true);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "text", false, 3)));

    String sql = builder.buildFullCursorScanSql(option, schema, "1\u001F101\u001FIssue", 100);

    assertThat(sql)
        .contains("select cursor_rows.\"label_id\", cursor_rows.\"target_id\", cursor_rows.\"target_type\"")
        .contains("concat_ws(chr(31), source_rows.\"label_id\"::text, source_rows.\"target_id\"::text, source_rows.\"target_type\"::text) as pk_signature")
        .contains("from \"public\".\"label_links\" source_rows")
        .contains("where cursor_rows.pk_signature > '1\u001F101\u001FIssue'")
        .contains("order by cursor_rows.pk_signature asc")
        .contains("limit 100");
  }

  @Test
  void shouldBuildCursorBatchScanSqlWithUpdatedAtAndPrimaryKeyCursor() {
    TableWhitelistOption option =
        new TableWhitelistOption("Issue Events", "Issue Events", "Issue ID", "Updated At", false);

    String sql = builder.buildCursorBatchScanSql(
        option,
        LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        LocalDateTime.of(2026, 1, 2, 3, 5, 6),
        "101",
        200);

    assertThat(sql)
        .isEqualTo("select * from \"public\".\"Issue Events\" where \"Updated At\" >= timestamp '2026-01-02 03:04:05' "
            + "and (\"Updated At\" > timestamp '2026-01-02 03:05:06' or (\"Updated At\" = timestamp '2026-01-02 03:05:06' "
            + "and \"Issue ID\" > '101')) order by \"Updated At\" asc, \"Issue ID\" asc limit 200");
  }

  @Test
  void shouldBuildExistingPrimaryKeysSqlForCompositeKeys() {
    TableWhitelistOption option =
        new TableWhitelistOption("label_links", "Label links", "label_id,target_id,target_type", null, true);

    String sql = builder.buildExistingPrimaryKeysSql(
        option,
        List.of("label_id", "target_id", "target_type"),
        List.of(
            Map.of("label_id", "1", "target_id", "101", "target_type", "Issue"),
            Map.of("label_id", "2", "target_id", "102", "target_type", "MergeRequest")));

    assertThat(sql)
        .isEqualTo("select \"label_id\"::text as \"label_id\", \"target_id\"::text as \"target_id\", \"target_type\"::text as \"target_type\"\n"
            + "  from \"public\".\"label_links\"\n"
            + " where (\"label_id\"::text = '1' and \"target_id\"::text = '101' and \"target_type\"::text = 'Issue') "
            + "or (\"label_id\"::text = '2' and \"target_id\"::text = '102' and \"target_type\"::text = 'MergeRequest')");
  }

  @Test
  void shouldBuildShardProbeSqlFromPrimaryKeyAndSourceColumns() {
    TableWhitelistOption option =
        new TableWhitelistOption("issues", "Issues", "id", "updated_at", true);
    SourceTableSchema schema = new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));

    String sql = builder.buildTableShardProbeSql(option, schema, 2);

    assertThat(sql)
        .contains("substring(md5(concat_ws(chr(31), \"id\"::text)), 1, 2) as shard_key")
        .contains("md5(jsonb_build_array(to_jsonb(\"id\"), to_jsonb(\"title\"), to_jsonb(\"updated_at\"))::text) as row_hash")
        .contains("md5(coalesce(string_agg(row_hash, ',' order by pk_signature), '')) as checksum")
        .contains("from \"public\".\"issues\"");
  }

  @Test
  void shouldEscapeQuotesInSourceIdentifiers() {
    TableWhitelistOption option =
        new TableWhitelistOption("issue\"events", "issue\"events", "id", "updated_at", false);

    String sql = builder.buildFullTableScanSql(option);

    assertThat(sql).isEqualTo("select * from \"public\".\"issue\"\"events\"");
  }
}
