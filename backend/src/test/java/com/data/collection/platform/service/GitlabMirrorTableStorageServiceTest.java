package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.MirrorPrimaryKeyBatch;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

class GitlabMirrorTableStorageServiceTest {

  @Test
  void markRowsDeletedShouldSetMirrorDeletedWithLookupCondition() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, mock(JsonUtils.class));
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_issues",
            List.of("id"),
            "updated_at",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    when(jdbcTemplate.update(org.mockito.ArgumentMatchers.anyString(), eq(99L), eq(101L))).thenReturn(1);

    int updated = service.markRowsDeleted(schema, "id", 101L, 99L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), eq(99L), eq(101L));
    assertThat(updated).isEqualTo(1);
    assertThat(sqlCaptor.getValue()).contains("update \"ods_gitlab_issues\"");
    assertThat(sqlCaptor.getValue()).contains("mirror_deleted = true");
    assertThat(sqlCaptor.getValue()).contains("where \"id\" = ?");
  }

  @Test
  void markRowsDeletedByPrimaryKeysShouldUseAllPrimaryKeyColumns() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, new JsonUtils(new ObjectMapper()));
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_label_links",
            List.of("label_id", "target_id", "target_type"),
            null,
            List.of(
                new SourceTableColumn("label_id", "bigint", false, 1),
                new SourceTableColumn("target_id", "bigint", false, 2),
                new SourceTableColumn("target_type", "character varying", false, 3)));
    List<Map<String, Object>> keys =
        List.of(Map.of("label_id", "1", "target_id", "101", "target_type", "Issue"));
    when(jdbcTemplate.update(
        ArgumentMatchers.anyString(),
        ArgumentMatchers.any(),
        ArgumentMatchers.any(),
        ArgumentMatchers.any(),
        ArgumentMatchers.any()))
        .thenReturn(1);

    int updated = service.markRowsDeletedByPrimaryKeys(schema, keys, 99L);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sqlCaptor.capture(), eq(99L), eq("1"), eq("101"), eq("Issue"));
    assertThat(updated).isEqualTo(1);
    assertThat(sqlCaptor.getValue()).contains("\"label_id\"::text = ?");
    assertThat(sqlCaptor.getValue()).contains("\"target_id\"::text = ?");
    assertThat(sqlCaptor.getValue()).contains("\"target_type\"::text = ?");
  }

  @Test
  void listActivePrimaryKeysShouldReturnNextCursor() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    GitlabMirrorTableStorageService service =
        new GitlabMirrorTableStorageService(jdbcTemplate, new JsonUtils(new ObjectMapper()));
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_issues",
            List.of("id"),
            "updated_at",
            List.of(new SourceTableColumn("id", "bigint", false, 1)));
    when(jdbcTemplate.queryForList(ArgumentMatchers.anyString(), eq("101"), eq(10)))
        .thenReturn(List.of(Map.of("id", "102")));

    MirrorPrimaryKeyBatch batch = service.listActivePrimaryKeys(schema, "[\"101\"]", 10);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq("101"), eq(10));
    assertThat(batch.keys()).containsExactly(Map.of("id", "102"));
    assertThat(batch.nextCursor()).isEqualTo("[\"102\"]");
    assertThat(sqlCaptor.getValue()).contains("\"id\"::text > ?");
    assertThat(sqlCaptor.getValue()).contains("order by \"id\"::text asc");
  }

  @Test
  void forceUpsertShouldBypassUpdatedAtConflictGuard() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    JsonUtils jsonUtils = mock(JsonUtils.class);
    GitlabMirrorTableStorageService service = new GitlabMirrorTableStorageService(jdbcTemplate, jsonUtils);
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2),
                new SourceTableColumn("title", "text", true, 3)));
    List<Map<String, Object>> rows =
        List.of(Map.of("id", 101L, "updated_at", "2026-05-21 10:00:00", "title", "source"));
    when(jsonUtils.toJson(rows.get(0))).thenReturn("{\"id\":101}");
    when(jdbcTemplate.batchUpdate(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class)))
        .thenReturn(new int[] {1});

    service.upsertBatch(schema, rows, 99L, true);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).batchUpdate(
        sqlCaptor.capture(),
        ArgumentMatchers.any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
    assertThat(sqlCaptor.getValue()).doesNotContain("where excluded.\"updated_at\"");
  }
}
