package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MatchModeOwnershipBoundaryTest {

  @Test
  void mongoRefreshOnlyUpdatesLegacyManagedFormalReviews() throws IOException {
    String source = Files.readString(
        Path.of(
            "src", "main", "java", "com", "data", "collection", "platform", "service",
            "CodeReviewMatchModeMongoReviewSyncService.java"),
        StandardCharsets.UTF_8);

    String refreshMethod = source.substring(
        source.indexOf("private void refreshMaterializedReviewDescriptions()"),
        source.indexOf("private String listText", source.indexOf(
            "private void refreshMaterializedReviewDescriptions()")));
    assertThat(refreshMethod)
        .containsSubsequence(
            "from review_data_match_mode_edit_links link",
            "where link.authority = 'LEGACY_MANAGED'")
        .containsSubsequence(
            "from review_data_match_mode_edit_links link",
            "where link.authority = 'LEGACY_MANAGED'");
    assertThat(countOccurrences(refreshMethod, "where link.authority = 'LEGACY_MANAGED'"))
        .isEqualTo(2);
  }

  private int countOccurrences(String source, String fragment) {
    return (source.length() - source.replace(fragment, "").length()) / fragment.length();
  }
}
