package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class GitlabMirrorTableStorageServiceTest {

  @Test
  void test_composite_primary_key_delete_uses_typed_values_and_reports_before_image() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, jsonUtils);
    SourceTableSchema schema = labelLinkSchema();
    Map<String, Object> key =
        Map.of("label_id", "1", "target_id", "101", "target_type", "Issue");
    MirrorRowChange deleted = new MirrorRowChange(key, Map.of());
    when(jdbcTemplate.<MirrorRowChange>query(
            anyString(),
            ArgumentMatchers.<RowMapper<MirrorRowChange>>any(),
            eq(99L),
            eq("1"),
            eq("101"),
            eq("Issue")))
        .thenReturn(List.of(deleted));

    MirrorMutationResult result =
        service.markRowsDeletedByPrimaryKeys(schema, List.of(key), 99L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .query(
            sqlCaptor.capture(),
            ArgumentMatchers.<RowMapper<MirrorRowChange>>any(),
            eq(99L),
            eq("1"),
            eq("101"),
            eq("Issue"));
    assertThat(result.sourceRows()).isOne();
    assertThat(result.changes()).containsExactly(deleted);
    assertThat(result.unchangedRows()).isZero();
    assertThat(sqlCaptor.getValue())
        .contains("(values (?::bigint, ?::bigint, ?::character varying))")
        .contains("target.\"label_id\" = source_keys.\"label_id\"")
        .contains("target.\"target_type\" = source_keys.\"target_type\"");
  }

  @Test
  void test_active_primary_key_scan_uses_typed_keyset_cursor() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, new JsonUtils(new ObjectMapper()));
    SourceTableSchema schema = issueSchema();
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq("101"), eq(10)))
        .thenReturn(List.of(Map.of("id", "102")));

    MirrorPrimaryKeyBatch batch = service.listActivePrimaryKeys(schema, "[\"101\"]", 10);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq("101"), eq(10));
    assertThat(batch.keys()).containsExactly(Map.of("id", "102"));
    assertThat(batch.nextCursor()).isEqualTo("[\"102\"]");
    assertThat(sqlCaptor.getValue())
        .contains("(\"id\") > (?::bigint)")
        .contains("order by \"id\" asc")
        .doesNotContain("\"id\"::text");
  }

  @Test
  void test_find_max_active_primary_key_uses_native_order_and_encodes_cursor() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, new JsonUtils(new ObjectMapper()));
    SourceTableSchema schema = issueSchema();
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString()))
        .thenReturn(List.of(Map.of("id", 102L)));

    String cursor = service.findMaxActivePrimaryKeyCursor(schema);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForList(sqlCaptor.capture());
    assertThat(sqlCaptor.getValue())
        .contains("where mirror_deleted = false")
        .contains("order by \"id\" desc")
        .contains("limit 1");
    assertThat(cursor).isEqualTo("[\"102\"]");
  }

  @Test
  void test_force_apply_bypasses_updated_at_guard_and_returns_changed_rows() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, jsonUtils);
    SourceTableSchema schema = issueSchemaWithTitle();
    Map<String, Object> row =
        Map.of("id", 101L, "updated_at", "2026-05-21 10:00:00", "title", "source");
    List<Map<String, Object>> rows = List.of(row);
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq(101L)))
        .thenReturn(List.of());
    when(jsonUtils.toJson(rows)).thenReturn("[{\"id\":101}]");
    when(jdbcTemplate.<Map<String, Object>>query(
            anyString(),
            ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
            eq(99L),
            eq("[{\"id\":101}]")))
        .thenReturn(rows);

    MirrorMutationResult result = service.applyBatch(schema, rows, 99L, true);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .query(
            sqlCaptor.capture(),
            ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
            eq(99L),
            eq("[{\"id\":101}]"));
    assertThat(result.appliedRows()).isOne();
    assertThat(result.changes().getFirst().inserted()).isTrue();
    assertThat(result.changes().getFirst().after()).containsEntry("title", "source");
    assertThat(sqlCaptor.getValue()).doesNotContain("where excluded.\"updated_at\"");
  }

  @Test
  void test_changed_row_with_nullable_column_preserves_null_without_failure() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, jsonUtils);
    SourceTableSchema schema = issueSchemaWithTitle();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 101L);
    row.put("updated_at", "2026-05-21 10:00:00");
    row.put("title", null);
    List<Map<String, Object>> rows = List.of(row);
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq(101L)))
        .thenReturn(List.of());
    when(jsonUtils.toJson(rows)).thenReturn("[{\"id\":101,\"title\":null}]");
    when(jdbcTemplate.<Map<String, Object>>query(
            anyString(),
            ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
            eq(99L),
            eq("[{\"id\":101,\"title\":null}]")))
        .thenReturn(rows);

    MirrorMutationResult result = service.applyBatch(schema, rows, 99L);

    assertThat(result.changes()).hasSize(1);
    assertThat(result.changes().getFirst().after())
        .containsEntry("id", 101L)
        .containsEntry("title", null);
    assertThatThrownBy(() -> result.changes().getFirst().after().put("title", "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void test_authoritative_empty_scope_tombstones_only_rows_in_declared_scope() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, mock(JsonUtils.class));
    SourceTableSchema schema = issueAssigneeSchema();
    Map<String, Object> existing = Map.of("issue_id", "101", "user_id", "7");
    MirrorRowChange deleted = new MirrorRowChange(existing, Map.of());
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq("101")))
        .thenReturn(List.of(existing));
    when(jdbcTemplate.<MirrorRowChange>query(
            anyString(),
            ArgumentMatchers.<RowMapper<MirrorRowChange>>any(),
            eq(501L),
            eq("101"),
            eq("7")))
        .thenReturn(List.of(deleted));

    MirrorMutationResult result =
        service.replaceAuthoritativeScope(
            schema, Map.of("issue_id", "101"), List.of(), 501L);

    assertThat(result.sourceRows()).isZero();
    assertThat(result.changes()).containsExactly(deleted);
    verify(jdbcTemplate, never())
        .query(
            ArgumentMatchers.contains("insert into"),
            ArgumentMatchers.<RowMapper<Map<String, Object>>>any(),
            ArgumentMatchers.any());
  }

  @Test
  void test_authoritative_scope_rejects_rows_from_another_parent_before_mutation() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, mock(JsonUtils.class));

    assertThatThrownBy(
            () ->
                service.replaceAuthoritativeScope(
                    issueAssigneeSchema(),
                    Map.of("issue_id", "101"),
                    List.of(Map.of("issue_id", 102L, "user_id", 7L)),
                    501L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("权威范围");
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void test_authoritative_label_scope_keeps_polymorphic_target_type_in_predicate() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, mock(JsonUtils.class));
    SourceTableSchema schema = labelLinkSchema();
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq("101"), eq("Issue")))
        .thenReturn(List.of());

    MirrorMutationResult result =
        service.replaceAuthoritativeScope(
            schema,
            Map.of("target_id", "101", "target_type", "Issue"),
            List.of(),
            501L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq("101"), eq("Issue"));
    assertThat(result.appliedRows()).isZero();
    assertThat(sqlCaptor.getValue())
        .contains("\"target_id\" = ?::bigint")
        .contains("\"target_type\" = ?::character varying");
  }

  private SourceTableSchema issueSchema() {
    return new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(new SourceTableColumn("id", "bigint", false, 1)));
  }

  private SourceTableSchema issueSchemaWithTitle() {
    return new SourceTableSchema(
        "ods_gitlab_issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("updated_at", "timestamp without time zone", true, 2),
            new SourceTableColumn("title", "text", true, 3)));
  }

  private SourceTableSchema issueAssigneeSchema() {
    return new SourceTableSchema(
        "ods_gitlab_issue_assignees",
        List.of("issue_id", "user_id"),
        null,
        List.of(
            new SourceTableColumn("issue_id", "bigint", false, 1),
            new SourceTableColumn("user_id", "bigint", false, 2)));
  }

  private SourceTableSchema labelLinkSchema() {
    return new SourceTableSchema(
        "ods_gitlab_label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "character varying", false, 3)));
  }
}
