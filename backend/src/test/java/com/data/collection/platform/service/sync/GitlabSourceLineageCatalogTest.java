package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabSourceLineageCatalogTest {

  @Test
  void test_issue_parent_declares_all_bounded_child_collections() {
    List<Map<String, Object>> scopes =
        GitlabSourceLineageCatalog.relationsForParent("issues").stream()
            .map(relation -> relation.scopeForParentRow(Map.of("id", 101L)))
            .toList();

    assertThat(scopes)
        .containsExactly(
            Map.of("issue_id", 101L),
            Map.of("issue_id", 101L),
            Map.of("noteable_id", 101L, "noteable_type", "Issue"),
            Map.of("target_id", 101L, "target_type", "Issue"));
  }

  @Test
  void test_note_parent_scope_preserves_polymorphic_type() {
    assertThat(GitlabSourceLineageCatalog.relationsForParent("notes"))
        .extracting(
            relation ->
                relation.scopeForParentRow(
                    Map.of("noteable_id", 202L, "noteable_type", "MergeRequest")))
        .containsExactly(
            Map.of(),
            Map.of("noteable_id", 202L, "noteable_type", "MergeRequest"));
  }

  @Test
  void test_root_entity_and_complete_note_scopes_are_authoritative() {
    assertThat(
            GitlabSourceLineageCatalog.isAuthoritativeTarget(
                "issues", Map.of("id", "101")))
        .isTrue();
    assertThat(
            GitlabSourceLineageCatalog.isAuthoritativeTarget(
                "notes", Map.of("noteable_id", "101", "noteable_type", "Issue")))
        .isTrue();
    assertThat(
            GitlabSourceLineageCatalog.isAuthoritativeTarget(
                "notes", Map.of("noteable_id", "101")))
        .isFalse();
    assertThat(
            GitlabSourceLineageCatalog.isAuthoritativeTarget(
                "notes", Map.of("id", "303")))
        .isFalse();
  }

  @Test
  void test_gitlab_16_label_event_columns_derive_type_isolated_scopes() {
    List<GitlabSourceLineageCatalog.Relation> relations =
        GitlabSourceLineageCatalog.relationsForParent("resource_label_events");

    assertThat(relations)
        .extracting(relation -> relation.scopeForParentRow(Map.of("issue_id", 101L)))
        .containsExactly(
            Map.of("target_id", 101L, "target_type", "Issue"),
            Map.of());
    assertThat(relations)
        .extracting(
            relation -> relation.scopeForParentRow(Map.of("merge_request_id", 202L)))
        .containsExactly(
            Map.of(),
            Map.of("target_id", 202L, "target_type", "MergeRequest"));
  }
}
