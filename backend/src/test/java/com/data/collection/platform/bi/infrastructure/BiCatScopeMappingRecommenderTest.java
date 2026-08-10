package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.bi.domain.model.BiProductVersionCatalog;
import com.data.collection.platform.bi.domain.model.BiProductVersionOption;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogNode;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogProject;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.CatalogSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BiCatScopeMappingRecommenderTest {
  private final BiCatScopeMappingRecommender recommender = new BiCatScopeMappingRecommender();

  @Test
  void recommend_uniqueDirectorySignals_returnsStableIdsWithoutGuessingOtherVersions() {
    var catalog = new CatalogSnapshot(
        UUID.randomUUID(),
        Instant.parse("2026-08-06T08:00:00Z"),
        List.of(new CatalogProject("cat-project", "CrownCAD", null, null, null, true)),
        List.of(
            node("VERSION", "cat-r4", "CrownCAD 2026 R4（正式版）", null, true),
            node("VERSION", "cat-r3", "历史版本", null, false),
            node("TEST_PHASE", "cat-ut-r4", "UT", "cat-r4", null),
            node("TEST_PHASE", "cat-it-r4", "SIT系统集成测试", "cat-r4", null)),
        List.of());
    var versions = new BiProductVersionCatalog(
        10L,
        List.of(
            new BiProductVersionOption(10L, "CC2026R4", "CC 2026 R4", 1),
            new BiProductVersionOption(11L, "CC2026R3", "CC 2026 R3", 2)));

    var suggestions = recommender.recommend(versions, catalog);

    assertThat(suggestions).extracting(BiCatScopeMappingRecommender.Suggestion::productVersionId)
        .containsExactly(10L, 11L);
    assertThat(suggestions.get(0).catProjectId()).isEqualTo("cat-project");
    assertThat(suggestions.get(0).catVersionId()).isEqualTo("cat-r4");
    assertThat(suggestions.get(0).unitTestingPhaseId()).isEqualTo("cat-ut-r4");
    assertThat(suggestions.get(0).integrationTestingPhaseId()).isEqualTo("cat-it-r4");
    assertThat(suggestions.get(1).catProjectId()).isEqualTo("cat-project");
    assertThat(suggestions.get(1).catVersionId()).isNull();
  }

  @Test
  void recommend_ambiguousDefaultProject_doesNotSelectAnArbitraryProject() {
    var catalog = new CatalogSnapshot(
        UUID.randomUUID(),
        Instant.parse("2026-08-06T08:00:00Z"),
        List.of(
            new CatalogProject("project-a", "A", null, null, null, true),
            new CatalogProject("project-b", "B", null, null, null, true)),
        List.of(),
        List.of());
    var versions = new BiProductVersionCatalog(
        10L,
        List.of(new BiProductVersionOption(10L, "CC2026R4", "CC 2026 R4", 1)));

    var suggestion = recommender.recommend(versions, catalog).getFirst();

    assertThat(suggestion.catProjectId()).isNull();
    assertThat(suggestion.catVersionId()).isNull();
  }

  private CatalogNode node(
      String type,
      String id,
      String name,
      String versionId,
      Boolean currentVersion) {
    return new CatalogNode(
        "cat-project",
        type,
        id,
        versionId,
        name,
        null,
        null,
        null,
        currentVersion,
        null,
        null,
        null,
        versionId);
  }
}
