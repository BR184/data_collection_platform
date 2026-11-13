package com.data.collection.platform.golden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GoldenEndpointCatalogStatusTest {
  @Test
  void preservesExplicitErrorStatusAndDefaultsSuccessTo200() {
    var entries = GoldenEndpointCatalog.load().entries().values();
    var detail = entries.stream().filter(e -> e.path().equals("/api/collect-forms/detail"))
        .findFirst().orElseThrow();
    assertThat(detail.cases()).extracting(GoldenEndpointCatalog.ReadCase::expectedHttpStatus)
        .containsExactly(400, 200);
  }

  @Test
  void rejectsInvalidStatusInsteadOfIgnoringCatalogContract() {
    assertThatThrownBy(() -> new GoldenEndpointCatalog.ReadCase("invalid", "", null, 0))
        .isInstanceOf(IllegalStateException.class);
  }
}
