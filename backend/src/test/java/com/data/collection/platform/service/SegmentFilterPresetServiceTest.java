package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SegmentFilterPresetServiceTest {

  @Test
  void shouldPersistCreatedPresetThroughRepository() {
    InMemorySegmentFilterPresetRepository repository = new InMemorySegmentFilterPresetRepository();
    SegmentFilterPresetService firstService = new SegmentFilterPresetService(repository);

    SegmentFilterPreset created =
        firstService.create(
            new CreateSegmentFilterPresetRequest(
                " Open P1 issues ",
                " pm ",
                "team",
                " issue ",
                " customer_issue ",
                " customer_issue_open_scope ",
                "{\"conditions\":[]}",
                "schema-v1",
                "2026-06-09T10:00:00"));

    SegmentFilterPresetService secondService = new SegmentFilterPresetService(repository);

    assertThat(secondService.list("issue", "customer_issue", "other"))
        .extracting(SegmentFilterPreset::id)
        .containsExactly(created.id());
    assertThat(created.presetName()).isEqualTo("Open P1 issues");
    assertThat(created.visibility()).isEqualTo("TEAM");
    assertThat(created.dslHash()).hasSize(64);
  }
}
