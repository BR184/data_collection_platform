package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenRunScopeTest {
  @Test
  void defaultScopePreservesEveryCatalogEntry() {
    List<GoldenEndpointCatalog.EndpointEntry> entries =
        List.copyOf(GoldenEndpointCatalog.load().entries().values());
    assertThat(GoldenRunScope.select(entries, false)).containsExactlyElementsOf(entries);
  }

  @Test
  void partialScopeRemovesOnlyCollectFormsAndPreservesSharedSourceAndFactCases() {
    List<GoldenEndpointCatalog.EndpointEntry> entries =
        List.copyOf(GoldenEndpointCatalog.load().entries().values());
    List<GoldenEndpointCatalog.EndpointEntry> selected = GoldenRunScope.select(entries, true);
    assertThat(selected).containsExactlyElementsOf(entries.stream()
        .filter(entry -> !entry.path().startsWith("/api/collect-forms/")).toList());
    assertThat(entries.size() - selected.size()).isEqualTo(24);
    assertThat(selected).anyMatch(entry -> entry.path().contains("gitlab-sync"));
    assertThat(selected.stream().flatMap(entry -> entry.writeCases().stream())
        .flatMap(writeCase -> writeCase.affectedTables().stream()))
        .noneMatch(table -> table.table().startsWith("form_s1_v1_"));
  }
}
