package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitlabAuthoritativeScopeQueryBuilderTest {
  private final GitlabAuthoritativeScopeQueryBuilder builder =
      new GitlabAuthoritativeScopeQueryBuilder();

  @Test
  void test_direct_multiple_scopes_use_one_typed_values_query() {
    GitlabParameterizedQuery query =
        builder.buildDirect(
            option(),
            schema(),
            List.of(
                new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                    11L, Map.of("target_id", 101L, "target_type", "Issue")),
                new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                    12L, Map.of("target_id", 202L, "target_type", "MergeRequest"))));

    assertThat(query.sql())
        .contains("(?::bigint, ?::bigint, ?::text)")
        .contains("requested_scopes.\"__qaflex_scope_id\"")
        .contains("source.\"target_type\" = requested_scopes.\"target_type\"")
        .doesNotContain("'Issue'", "'MergeRequest'", " or ");
    assertThat(query.parameters())
        .containsExactly(11L, 101L, "Issue", 12L, 202L, "MergeRequest");
  }

  @Test
  void test_docker_multiple_scopes_stream_values_through_copy() {
    String script =
        builder.buildDockerCopyScript(
            option(),
            schema(),
            List.of(
                new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                    11L, Map.of("target_id", 101L, "target_type", "Issue\"Quoted"))));

    assertThat(script)
        .contains("create temp table requested_scopes(\"__qaflex_scope_id\" bigint")
        .contains("copy requested_scopes(\"__qaflex_scope_id\"")
        .contains("\"11\",\"101\",\"Issue\"\"Quoted\"")
        .contains("select row_to_json(result_row)::text")
        .doesNotContain(" where ", " or ");
  }

  @Test
  void test_batch_with_different_scope_columns_is_rejected() {
    assertThatThrownBy(
            () ->
                builder.buildDirect(
                    option(),
                    schema(),
                    List.of(
                        new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                            11L, Map.of("target_id", 101L, "target_type", "Issue")),
                        new GitlabAuthoritativeScopeQueryBuilder.ScopeInput(
                            12L, Map.of("target_id", 202L)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("查询列必须一致");
  }

  private TableWhitelistOption option() {
    return new TableWhitelistOption(
        "label_links",
        "Label links",
        "id",
        null,
        SourceCursorStrategy.NONE,
        true);
  }

  private SourceTableSchema schema() {
    return new SourceTableSchema(
        "ods_gitlab_label_links",
        List.of("id"),
        null,
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "character varying", false, 3)));
  }
}
