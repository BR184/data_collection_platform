package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataFilterOptionServiceTest {

  @Mock private ReviewDataMirrorOptionRepository mirrorOptionRepository;
  @Mock private ReviewDataHistoricalOptionRepository historicalOptionRepository;

  @Test
  void shouldBuildReviewDataFilterOptionsFromHistoricalRecords() {
    ReviewDataFilterOptionService service =
        new ReviewDataFilterOptionService(mirrorOptionRepository, historicalOptionRepository);
    when(mirrorOptionRepository.loadProjectNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of());
    when(historicalOptionRepository.loadProjectNames()).thenReturn(List.of("CrownCAD"));
    when(historicalOptionRepository.loadModuleNames()).thenReturn(List.of("Sketch"));
    when(historicalOptionRepository.loadReviewVersions()).thenReturn(List.of("V1.0"));
    when(historicalOptionRepository.loadReviewOwners()).thenReturn(List.of("Alice", "Bob"));
    when(historicalOptionRepository.loadReviewExperts()).thenReturn(List.of());
    when(historicalOptionRepository.loadAuthors()).thenReturn(List.of());

    List<String> ownerOptions =
        service.getFilterOptions().reviewOwners().stream()
            .map(option -> option.value())
            .toList();

    assertThat(ownerOptions).containsExactly("Alice", "Bob");
  }

  @Test
  void shouldIncludeMirrorOptionsForCreatingReviewsEvenWhenRecordsDoNotReferenceThem() {
    ReviewDataFilterOptionService service =
        new ReviewDataFilterOptionService(mirrorOptionRepository, historicalOptionRepository);
    when(mirrorOptionRepository.loadProjectNames()).thenReturn(List.of("MirrorProject"));
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(List.of("LabelProject"));
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of("MirrorModule"));
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of("MirrorUser"));
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of("MirrorMilestone"));
    when(historicalOptionRepository.loadProjectNames()).thenReturn(List.of("ImportedProject"));
    when(historicalOptionRepository.loadModuleNames()).thenReturn(List.of("ImportedModule"));
    when(historicalOptionRepository.loadReviewVersions()).thenReturn(List.of("ImportedVersion"));
    when(historicalOptionRepository.loadReviewOwners()).thenReturn(List.of("ImportedOwner"));
    when(historicalOptionRepository.loadReviewExperts()).thenReturn(List.of("ImportedExpert"));
    when(historicalOptionRepository.loadAuthors()).thenReturn(List.of("ImportedAuthor"));

    var options = service.getFilterOptions();

    assertThat(options.projectNames().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorProject", "ImportedProject");
    assertThat(options.moduleNames().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorModule", "ImportedModule");
    assertThat(options.reviewOwners().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorUser", "ImportedOwner");
    assertThat(options.reviewExperts().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorUser", "ImportedOwner", "ImportedExpert", "ImportedAuthor");
    assertThat(options.reviewVersions().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorMilestone", "ImportedVersion");
    assertThat(options.formProjectNames().stream().map(option -> option.value()).toList())
        .containsExactly("LabelProject");
    assertThat(options.formModuleNames().stream().map(option -> option.value()).toList())
        .containsExactly("MirrorModule");
  }
}
