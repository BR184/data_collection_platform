package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.sync.IncrementalReadMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabSourceLineageCatalogTest {

  @Test
  void test_gitlab_16_primary_keys_match_declared_source_contracts() {
    assertThat(GitlabSourceLineageCatalog.requireSource("issue_metrics").primaryKeys())
        .containsExactly("id");
    assertThat(GitlabSourceLineageCatalog.requireSource("merge_request_metrics").primaryKeys())
        .containsExactly("id");
    assertThat(GitlabSourceLineageCatalog.requireSource("merge_request_assignees").primaryKeys())
        .containsExactly("id");
    assertThat(GitlabSourceLineageCatalog.requireSource("merge_request_reviewers").primaryKeys())
        .containsExactly("id");
    assertThat(GitlabSourceLineageCatalog.requireSource("issue_assignees").primaryKeys())
        .containsExactly("issue_id", "user_id");
    assertThat(GitlabSourceLineageCatalog.requireSource("ci_builds").primaryKeys())
        .containsExactly("id", "partition_id");
  }

  @Test
  void test_all_recommended_sources_have_explicit_gitlab_16_incremental_modes() {
    assertThat(GitlabSourceLineageCatalog.sources())
        .allSatisfy(
            source -> {
              assertThat(source.incrementalReadMode()).isNotNull();
              if (source.incrementalReadMode() == IncrementalReadMode.UPDATED_AT) {
                assertThat(source.incrementalUpdatedAtColumn()).isEqualTo("updated_at");
              }
            });
    assertThat(GitlabSourceLineageCatalog.requireSource("user_details").incrementalReadMode())
        .isEqualTo(IncrementalReadMode.RECONCILE_ONLY);
    assertThat(GitlabSourceLineageCatalog.requireSource("label_links").incrementalReadMode())
        .isEqualTo(IncrementalReadMode.UPDATED_AT);
    assertThat(
            GitlabSourceLineageCatalog.requireSource("resource_label_events")
                .incrementalReadMode())
        .isEqualTo(IncrementalReadMode.MONOTONIC_PRIMARY_KEY);
  }

  @Test
  void test_label_event_schema_requires_both_type_isolated_parent_columns() {
    SourceTableSchema validSchema =
        new SourceTableSchema(
            "resource_label_events",
            List.of("id"),
            null,
            List.of(
                new SourceTableColumn("id", "bigint", false, 1),
                new SourceTableColumn("issue_id", "bigint", true, 2),
                new SourceTableColumn("merge_request_id", "bigint", true, 3)));

    GitlabSourceLineageCatalog.validatePhysicalSchema(
        "resource_label_events", validSchema);

    SourceTableSchema missingMergeRequest =
        new SourceTableSchema(
            "resource_label_events",
            List.of("id"),
            null,
            validSchema.columns().subList(0, 2));
    assertThatThrownBy(
            () ->
                GitlabSourceLineageCatalog.validatePhysicalSchema(
                    "resource_label_events", missingMergeRequest))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("merge_request_id");
  }

  @Test
  void test_recommended_sources_include_merge_request_commit_lineage() {
    assertThat(GitlabSourceLineageCatalog.recommendedTables())
        .contains("merge_request_diffs", "merge_request_diff_commits");
    assertThat(GitlabSourceLineageCatalog.requireSource("merge_request_diffs").primaryKeys())
        .containsExactly("id");
    assertThat(
            GitlabSourceLineageCatalog.requireSource("merge_request_diff_commits").primaryKeys())
        .containsExactly("merge_request_diff_id", "relative_order");
  }

  @Test
  void test_merge_request_diff_relations_define_complete_child_scopes() {
    assertThat(GitlabSourceLineageCatalog.relationsForParent("merge_requests"))
        .anySatisfy(
            relation -> {
              assertThat(relation.childTable()).isEqualTo("merge_request_diffs");
              assertThat(relation.scopeForParentRow(Map.of("id", 202L)))
                  .isEqualTo(Map.of("merge_request_id", 202L));
            });
    assertThat(GitlabSourceLineageCatalog.relationsForParent("merge_request_diffs"))
        .singleElement()
        .satisfies(
            relation -> {
              assertThat(relation.childTable()).isEqualTo("merge_request_diff_commits");
              assertThat(relation.scopeForParentRow(Map.of("id", 303L)))
                  .isEqualTo(Map.of("merge_request_diff_id", 303L));
            });
  }

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
