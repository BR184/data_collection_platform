package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatchModeOwnershipBoundaryTest {

  @Test
  void mongoSyncNeverWritesCompatibilitySnapshotsBackToFormalReviews() throws IOException {
    String source = Files.readString(
        Path.of(
            "src", "main", "java", "com", "data", "collection", "platform", "service",
            "CodeReviewMatchModeMongoReviewSyncService.java"),
        StandardCharsets.UTF_8);

    assertThat(source)
        .doesNotContain("refreshMaterializedReviewDescriptions")
        .doesNotContain("review_data_match_mode_edit_links link")
        .doesNotContain("LEGACY_MANAGED");
  }

  @Test
  void formalImportRetainsOnlyTheCodeReviewPromotionPath() throws IOException {
    String source = Files.readString(
        Path.of(
            "src", "main", "java", "com", "data", "collection", "platform", "service",
            "LegacyPlatformFormalImportService.java"),
        StandardCharsets.UTF_8);

    assertThat(source)
        .contains("CODE_REVIEW_PROMOTION")
        .doesNotContain("request.importReviewData()")
        .doesNotContain("importReviewData()")
        .doesNotContain("\n          review_requested,")
        .doesNotContain("\n            set review_inserted_count = ?,")
        .doesNotContain("\n               review_updated_count = ?,")
        .doesNotContain("\n                 review_skipped_count = ?,")
        .doesNotContain("\n                 review_deleted_count = ?,")
        .doesNotContain("\n               review_status = ?,");
  }
}
