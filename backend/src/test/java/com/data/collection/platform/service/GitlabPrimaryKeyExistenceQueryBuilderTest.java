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

class GitlabPrimaryKeyExistenceQueryBuilderTest {
  private final GitlabPrimaryKeyExistenceQueryBuilder builder =
      new GitlabPrimaryKeyExistenceQueryBuilder();

  @Test
  void test_direct_composite_keys_use_typed_values_and_parameters() {
    GitlabParameterizedQuery query =
        builder.buildDirect(
            option(),
            schema(),
            List.of("label_id", "target_id", "target_type"),
            List.of(
                Map.of(
                    "label_id", 1L,
                    "target_id", 101L,
                    "target_type", "Issue"),
                Map.of(
                    "label_id", 2L,
                    "target_id", 102L,
                    "target_type", "MergeRequest")));

    assertThat(query.sql())
        .contains(
            "(values (?::bigint, ?::bigint, ?::text), "
                + "(?::bigint, ?::bigint, ?::text))")
        .contains(
            "source.\"target_type\" = requested_keys.\"target_type\"")
        .doesNotContain(" or ", "'Issue'", "'MergeRequest'");
    assertThat(query.parameters())
        .containsExactly(1L, 101L, "Issue", 2L, 102L, "MergeRequest");
  }

  @Test
  void test_docker_composite_keys_are_streamed_through_copy_not_shell_literals() {
    String script =
        builder.buildDockerCopyScript(
            option(),
            schema(),
            List.of("label_id", "target_id", "target_type"),
            List.of(
                Map.of(
                    "label_id", 1L,
                    "target_id", 101L,
                    "target_type", "Issue\"WithQuote")));

    assertThat(script)
        .contains("create temp table requested_keys(")
        .contains("copy requested_keys(")
        .contains("\"1\",\"101\",\"Issue\"\"WithQuote\"")
        .contains("\\.\nselect row_to_json")
        .doesNotContain(" where ", " or ");
  }

  @Test
  void test_unsupported_primary_key_type_is_rejected() {
    SourceTableSchema unsupported =
        new SourceTableSchema(
            "label_links",
            List.of("label_id"),
            null,
            List.of(new SourceTableColumn("label_id", "jsonb", false, 1)));

    assertThatThrownBy(
            () ->
                builder.buildDirect(
                    option(), unsupported, List.of("label_id"), List.of(Map.of("label_id", 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持来源列类型");
  }

  private TableWhitelistOption option() {
    return new TableWhitelistOption(
        "label_links",
        "Label links",
        "label_id,target_id,target_type",
        null,
        SourceCursorStrategy.NONE,
        true);
  }

  private SourceTableSchema schema() {
    return new SourceTableSchema(
        "label_links",
        List.of("label_id", "target_id", "target_type"),
        null,
        List.of(
            new SourceTableColumn("label_id", "bigint", false, 1),
            new SourceTableColumn("target_id", "bigint", false, 2),
            new SourceTableColumn("target_type", "character varying", false, 3)));
  }
}
