package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.MirrorMutationResult;
import com.data.collection.platform.entity.MirrorRowChange;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.service.GitlabMirrorTableStorageService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MirrorTableWriterTest {
  @Test
  void shouldDelegateBatchWritesToStorageService() {
    GitlabMirrorTableStorageService storageService = mock(GitlabMirrorTableStorageService.class);
    MirrorTableWriter writer = new MirrorTableWriter(storageService);
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 18, 10, 0)));
    MirrorMutationResult expected =
        new MirrorMutationResult(1, List.of(new MirrorRowChange(Map.of(), rows.getFirst())), 0);
    when(storageService.applyBatch(schema, rows, 501L)).thenReturn(expected);

    MirrorMutationResult actual = writer.writeBatch(schema, rows, 501L);

    assertThat(actual).isEqualTo(expected);
    verify(storageService).applyBatch(schema, rows, 501L);
  }

  @Test
  void shouldDelegateForceBatchWritesToStorageService() {
    GitlabMirrorTableStorageService storageService = mock(GitlabMirrorTableStorageService.class);
    MirrorTableWriter writer = new MirrorTableWriter(storageService);
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issues",
            List.of("id"),
            "updated_at",
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("updated_at", "timestamp without time zone", true, 2)));
    List<Map<String, Object>> rows =
        List.of(Map.of("id", 101L, "updated_at", LocalDateTime.of(2026, 5, 18, 10, 0)));
    MirrorMutationResult expected =
        new MirrorMutationResult(1, List.of(new MirrorRowChange(Map.of(), rows.getFirst())), 0);
    when(storageService.applyBatch(schema, rows, 501L, true)).thenReturn(expected);

    MirrorMutationResult actual = writer.writeBatch(schema, rows, 501L, true);

    assertThat(actual).isEqualTo(expected);
    verify(storageService).applyBatch(schema, rows, 501L, true);
  }

  @Test
  void shouldDelegateAuthoritativeScopeReplacementToStorageService() {
    GitlabMirrorTableStorageService storageService = mock(GitlabMirrorTableStorageService.class);
    MirrorTableWriter writer = new MirrorTableWriter(storageService);
    SourceTableSchema schema =
        new SourceTableSchema(
            "ods_gitlab_alpha_issue_assignees",
            List.of("issue_id", "user_id"),
            null,
            List.of(
                new SourceTableColumn("issue_id", "bigint", false, 1),
                new SourceTableColumn("user_id", "bigint", false, 2)));
    List<Map<String, Object>> rows = List.of(Map.of("issue_id", 101L, "user_id", 8L));
    MirrorMutationResult expected =
        new MirrorMutationResult(1, List.of(new MirrorRowChange(Map.of(), rows.getFirst())), 0);
    when(storageService.replaceAuthoritativeScope(schema, Map.of("issue_id", "101"), rows, 501L))
        .thenReturn(expected);

    MirrorMutationResult actual =
        writer.replaceAuthoritativeScope(schema, Map.of("issue_id", "101"), rows, 501L);

    assertThat(actual).isEqualTo(expected);
    verify(storageService).replaceAuthoritativeScope(schema, Map.of("issue_id", "101"), rows, 501L);
  }
}
