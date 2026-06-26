package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
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
  @Mock private ReviewDataMirrorOptionRepository mirrorOptionRepository;

  @Test
  void shouldBuildReviewDataFilterOptionsFromCurrentRecords() {
    ReviewDataFilterOptionService service = new ReviewDataFilterOptionService(persistenceSupport, mirrorOptionRepository);
    when(persistenceSupport.loadRecordsForFilterOptions())
        .thenReturn(
            List.of(
                record("CrownCAD", "Sketch", "Alice", "V1.0"),
                record("CrownCAD", "Sketch", "Bob", "V1.1")));
    when(persistenceSupport.loadExpertOptions()).thenReturn(List.of());
    when(mirrorOptionRepository.loadProjectNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of());

    List<String> ownerOptions =
        service.getFilterOptions().reviewOwners().stream()
            .map(option -> option.value())
            .toList();

    assertThat(ownerOptions).containsExactly("Alice", "Bob");
    verify(persistenceSupport).loadRecordsForFilterOptions();
  }

  @Test
  void shouldIncludeMirrorOptionsForCreatingReviewsEvenWhenRecordsDoNotReferenceThem() {
    ReviewDataFilterOptionService service = new ReviewDataFilterOptionService(persistenceSupport, mirrorOptionRepository);
    when(persistenceSupport.loadRecordsForFilterOptions())
        .thenReturn(List.of(record("ImportedProject", "ImportedModule", "ImportedOwner", "ImportedVersion")));
    when(persistenceSupport.loadExpertOptions()).thenReturn(List.of("ImportedExpert"));
    when(mirrorOptionRepository.loadProjectNames()).thenReturn(List.of("MirrorProject"));
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of("MirrorModule"));
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of("MirrorUser"));
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of("MirrorMilestone"));

    var options = service.getFilterOptions();

    assertThat(options.projectNames().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorProject", "ImportedProject");
    assertThat(options.moduleNames().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorModule", "ImportedModule");
    assertThat(options.reviewOwners().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorUser", "ImportedOwner");
    assertThat(options.reviewExperts().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorUser", "ImportedExpert");
    assertThat(options.reviewVersions().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorMilestone", "ImportedVersion");
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
