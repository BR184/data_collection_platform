package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSourceMetadataDiagnosticsResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GitlabSourceSchemaDiscoveryServiceTest {
  private final GitlabSourceMetadataSupport metadataSupport = new GitlabSourceMetadataSupport();

  @Test
  void shouldDiscoverTablesFromPrimaryKeysAndUpdatedAtColumns() {
    GitlabSourceSchemaDiscoveryService service =
        new GitlabSourceSchemaDiscoveryService((config, sql) -> {
          if (sql.contains("string_agg(a.attname")) {
            return List.of(
                row("table_name", "issues", "primary_key", "id"),
                row("table_name", "merge_requests", "primary_key", "id"));
          }
          return List.of(
              columnRow("issues", "id", "bigint", false, 1),
              columnRow("issues", "updated_at", "timestamp without time zone", true, 2),
              columnRow("merge_requests", "id", "bigint", false, 1),
              columnRow("merge_requests", "created_at", "timestamp without time zone", true, 2));
        }, metadataSupport);

    List<TableWhitelistOption> tables = service.discoverTables(
        new GitlabSyncConfig(),
        Map.of("issues", "Issues"),
        List.of("merge_requests"));

    assertThat(tables)
        .containsExactly(
            new TableWhitelistOption("issues", "Issues", "id", "updated_at", false),
            new TableWhitelistOption("merge_requests", "merge_requests", "id", "created_at", true));
  }

  @Test
  void shouldDiscoverSingleTableSchemaAndEscapeTableName() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    GitlabSourceSchemaDiscoveryService service =
        new GitlabSourceSchemaDiscoveryService((config, sql) -> {
          executedSql.set(sql);
          return List.of(
              columnRow(null, "id", "bigint", false, 1),
              columnRow(null, "title", "text", true, 2));
        }, metadataSupport);
    TableWhitelistOption option = new TableWhitelistOption("issue's", "Issue's", "id", null, false);

    SourceTableSchema schema = service.discoverTableSchema(new GitlabSyncConfig(), option);

    assertThat(executedSql.get()).contains("c.relname = 'issue''s'");
    assertThat(schema.tableName()).isEqualTo("issue's");
    assertThat(schema.primaryKeys()).containsExactly("id");
    assertThat(schema.columns()).extracting("columnName").containsExactly("id", "title");
  }

  @Test
  void shouldBuildMetadataDiagnosticsWithoutTriggeringSync() {
    GitlabSourceSchemaDiscoveryService service =
        new GitlabSourceSchemaDiscoveryService((config, sql) -> {
          if (sql.contains("string_agg(a.attname")) {
            return List.of(row("table_name", "issues", "primary_key", "id"));
          }
          return List.of(
              columnRow("audit_events", "event_name", "text", false, 1),
              columnRow("issues", "id", "bigint", false, 1),
              columnRow("issues", "updated_at", "timestamp without time zone", true, 2));
        }, metadataSupport);

    GitlabSourceMetadataDiagnosticsResponse diagnostics = service.inspectSourceMetadata(
        new GitlabSyncConfig(),
        List.of(new TableWhitelistOption("issues", "Issues", "id", "updated_at", true)));

    assertThat(diagnostics.metadataOk()).isTrue();
    assertThat(diagnostics.sourceTableCount()).isEqualTo(2);
    assertThat(diagnostics.primaryKeyTableCount()).isEqualTo(1);
    assertThat(diagnostics.missingPrimaryKeyTableCount()).isEqualTo(1);
    assertThat(diagnostics.missingUpdatedAtTableCount()).isEqualTo(1);
    assertThat(diagnostics.sourceTables())
        .anySatisfy(table -> {
          assertThat(table.tableName()).isEqualTo("issues");
          assertThat(table.rowStrategy()).isEqualTo("INCREMENTAL");
          assertThat(table.recommended()).isTrue();
          assertThat(table.schemaFingerprint()).hasSize(16);
        })
        .anySatisfy(table -> {
          assertThat(table.tableName()).isEqualTo("audit_events");
          assertThat(table.primaryKey()).isNull();
          assertThat(table.rowStrategy()).isEqualTo("FULL_ONLY");
        });
  }

  private static Map<String, Object> columnRow(
      String tableName,
      String columnName,
      String formattedType,
      boolean nullable,
      int ordinalPosition) {
    Map<String, Object> row = new LinkedHashMap<>();
    if (tableName != null) {
      row.put("table_name", tableName);
    }
    row.put("column_name", columnName);
    row.put("formatted_type", formattedType);
    row.put("nullable", nullable);
    row.put("ordinal_position", ordinalPosition);
    return row;
  }

  private static Map<String, Object> row(Object... values) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) {
      row.put(String.valueOf(values[i]), values[i + 1]);
    }
    return row;
  }
}
