package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.SourceTableColumn;
import com.data.collection.platform.entity.SourceTableSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitlabSourceMetadataSupportTest {
  private final GitlabSourceMetadataSupport support = new GitlabSourceMetadataSupport();

  @Test
  void shouldPreferUpdatedAtVariantsOverCreatedAtVariants() {
    String resolved = support.resolveUpdatedAtColumn(List.of("id", "CreatedAt", "UpdatedAt"));

    assertThat(resolved).isEqualTo("UpdatedAt");
  }

  @Test
  void shouldFallbackToCreatedAtVariantWhenUpdatedAtIsMissing() {
    String resolved = support.resolveUpdatedAtColumn(List.of("id", "createdAt", "name"));

    assertThat(resolved).isEqualTo("createdAt");
  }

  @Test
  void shouldRecognizeCommonSnakeCaseAndCamelCaseVariants() {
    assertThat(support.resolveUpdatedAtColumn(List.of("id", "updated_at"))).isEqualTo("updated_at");
    assertThat(support.resolveUpdatedAtColumn(List.of("id", "updatedAt"))).isEqualTo("updatedAt");
    assertThat(support.resolveUpdatedAtColumn(List.of("id", "gmt_modified"))).isEqualTo("gmt_modified");
    assertThat(support.resolveUpdatedAtColumn(List.of("id", "create_time"))).isEqualTo("create_time");
  }

  @Test
  void shouldReturnNullWhenNoCandidateExists() {
    assertThat(support.resolveUpdatedAtColumn(List.of("id", "name", "description"))).isNull();
  }

  @Test
  void shouldMarkTablesWithoutUpdatedAtAsFullOnly() {
    assertThat(support.resolveRowStrategy("updated_at")).isEqualTo("INCREMENTAL");
    assertThat(support.resolveRowStrategy(null)).isEqualTo("FULL_ONLY");
    assertThat(support.resolveRowStrategy("")).isEqualTo("FULL_ONLY");
  }

  @Test
  void shouldBuildStableSchemaFingerprintForSourceMetadata() {
    SourceTableSchema schema = new SourceTableSchema(
        "issues",
        List.of("id"),
        "updated_at",
        List.of(
            new SourceTableColumn("id", "bigint", false, 1),
            new SourceTableColumn("title", "text", true, 2),
            new SourceTableColumn("updated_at", "timestamp without time zone", false, 3)));

    assertThat(support.buildSchemaFingerprint(schema)).isEqualTo(support.buildSchemaFingerprint(schema));
    assertThat(support.buildSchemaFingerprint(schema)).hasSize(16);
  }

  @Test
  void shouldSplitPrimaryKeysAndDropBlankSegments() {
    assertThat(support.splitPrimaryKeys(" id, target_id, , target_type "))
        .containsExactly("id", "target_id", "target_type");
    assertThat(support.splitPrimaryKeys(null)).isEmpty();
    assertThat(support.splitPrimaryKeys("")).isEmpty();
  }
}
