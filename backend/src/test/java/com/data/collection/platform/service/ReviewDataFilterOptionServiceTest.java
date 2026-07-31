package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataFilterOptionServiceTest {

  @Mock private ReviewDataMirrorOptionRepository mirrorOptionRepository;
  @Mock private ReviewDataHistoricalOptionRepository historicalOptionRepository;
  @Mock private CodeReviewMatchModeSwitchService matchModeSwitchService;
  @Mock private ReviewDataMatchModeRecordRepository matchModeRecordRepository;

  @Test
  void shouldBuildQuickFilterProjectsAndModulesFromVisibleFormalAndMatchModeRecordsWhenCompatibilityReadEnabled() {
    ReviewDataFilterOptionService service = service();
    when(matchModeSwitchService.isReviewDataCompatibilityReadEnabled()).thenReturn(true);
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
    when(matchModeRecordRepository.loadRecords())
        .thenReturn(
            List.of(
                new ReviewDataRecordRowResponse(
                    -7L,
                    "LegacyProject",
                    "老平台评审",
                    "LegacyModule",
                    "设计说明书评审",
                    null,
                    "LegacyOwner",
                    "LegacyExpertA、LegacyExpertB",
                    0,
                    "",
                    "",
                    "",
                    0,
                    0D,
                    null,
                    false)));

    var options = service.getFilterOptions();
    List<String> projectOptions = options.projectNames().stream().map(option -> option.value()).toList();
    List<String> moduleOptions = options.moduleNames().stream().map(option -> option.value()).toList();

    assertThat(projectOptions).containsExactly("CrownCAD", "LegacyProject");
    assertThat(moduleOptions).containsExactly("Sketch", "LegacyModule");
    assertThat(options.reviewOwners().stream().map(option -> option.value()).toList())
        .containsExactly("Alice", "Bob", "LegacyOwner");
    assertThat(options.reviewExperts().stream().map(option -> option.value()).toList())
        .containsExactly("Alice", "Bob", "LegacyOwner", "LegacyExpertA", "LegacyExpertB");
    verify(matchModeRecordRepository).loadRecords();
  }

  @Test
  void shouldExcludeMatchModeOptionsWhenCompatibilityReadDisabled() {
    ReviewDataFilterOptionService service = service();
    when(matchModeSwitchService.isReviewDataCompatibilityReadEnabled()).thenReturn(false);
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of());
    when(historicalOptionRepository.loadProjectNames()).thenReturn(List.of("CrownCAD"));
    when(historicalOptionRepository.loadModuleNames()).thenReturn(List.of("Sketch"));
    when(historicalOptionRepository.loadReviewVersions()).thenReturn(List.of("V1.0"));
    when(historicalOptionRepository.loadReviewOwners()).thenReturn(List.of("Alice"));
    when(historicalOptionRepository.loadReviewExperts()).thenReturn(List.of());
    when(historicalOptionRepository.loadAuthors()).thenReturn(List.of());

    var options = service.getFilterOptions();

    assertThat(options.projectNames().stream().map(option -> option.value()).toList())
        .containsExactly("CrownCAD");
    assertThat(options.moduleNames().stream().map(option -> option.value()).toList())
        .containsExactly("Sketch");
    verifyNoInteractions(matchModeRecordRepository);
  }

  @Test
  void shouldIncludeMirrorOptionsForCreatingReviewsEvenWhenRecordsDoNotReferenceThem() {
    ReviewDataFilterOptionService service = service();
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
        .containsExactly("ImportedProject");
    assertThat(options.moduleNames().stream().map(option -> option.value()).toList())
        .containsExactly("ImportedModule");
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

  @Test
  void shouldExposeReviewTypeOptionsInLegacyFrontendOrder() {
    ReviewDataFilterOptionService service = service();
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadModuleNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of());
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of());
    when(historicalOptionRepository.loadProjectNames()).thenReturn(List.of());
    when(historicalOptionRepository.loadModuleNames()).thenReturn(List.of());
    when(historicalOptionRepository.loadReviewVersions()).thenReturn(List.of());
    when(historicalOptionRepository.loadReviewOwners()).thenReturn(List.of());
    when(historicalOptionRepository.loadReviewExperts()).thenReturn(List.of());
    when(historicalOptionRepository.loadAuthors()).thenReturn(List.of());

    var options = service.getFilterOptions();

    assertThat(options.reviewTypes().stream().map(option -> option.value()).toList())
        .containsExactly("需求说明书评审", "设计说明书评审", "产品用户手册", "项目计划评审", "其他");
  }

  private ReviewDataFilterOptionService service() {
    return new ReviewDataFilterOptionService(
        mirrorOptionRepository,
        historicalOptionRepository,
        matchModeSwitchService,
        matchModeRecordRepository);
  }
}
