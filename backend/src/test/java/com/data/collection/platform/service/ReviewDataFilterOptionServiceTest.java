package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.TagSelectionRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataFilterOptionServiceTest {

  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;

  @Test
  void shouldNarrowReviewDataFilterOptionsBySelectedTags() {
    ReviewDataFilterOptionService service = new ReviewDataFilterOptionService(persistenceSupport);
    List<TagSelectionRequest> tagSelections =
        List.of(new TagSelectionRequest("module", List.of("sketch")));
    when(persistenceSupport.loadRecordsForFilterOptions(tagSelections, "cc"))
        .thenReturn(
            List.of(
                record("CrownCAD", "Sketch", "Alice", "V1.0"),
                record("CrownCAD", "Sketch", "Bob", "V1.1")));
    when(persistenceSupport.loadExpertOptions()).thenReturn(List.of());

    List<String> ownerOptions =
        service.getFilterOptions(tagSelections, "cc").reviewOwners().stream()
            .map(option -> option.value())
            .toList();

    assertThat(ownerOptions).containsExactly("Alice", "Bob");
    verify(persistenceSupport).loadRecordsForFilterOptions(tagSelections, "cc");
  }

  private ReviewDataRecordRowResponse record(
      String projectName, String moduleName, String reviewOwner, String reviewVersion) {
    return new ReviewDataRecordRowResponse(
        1L,
        projectName,
        "Design Review",
        moduleName,
        "Document Review",
        LocalDate.of(2026, 4, 10),
        reviewOwner,
        "",
        24,
        "Design Doc",
        "Dora",
        reviewVersion,
        0,
        0D,
        LocalDateTime.of(2026, 4, 12, 10, 0),
        false);
  }
}
