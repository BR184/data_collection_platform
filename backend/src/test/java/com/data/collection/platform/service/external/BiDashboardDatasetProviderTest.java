package com.data.collection.platform.service.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.statistics.SystemTestModuleFixRateSnapshot;
import com.data.collection.platform.service.CodeReviewMatchModeSwitchService;
import com.data.collection.platform.service.QualityBoardRdService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.statistics.StatisticBoardRegistry;
import com.data.collection.platform.service.statistics.SystemTestDefectSummaryBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BiDashboardDatasetProviderTest {
  private JdbcTemplate jdbcTemplate;
  private QualityBoardRdService qualityBoardRdService;
  private SystemTestDefectSummaryBoardService summaryBoardService;
  private StatisticBoardRegistry statisticBoardRegistry;
  private SystemTestPhaseScopeResolver phaseScopeResolver;
  private CodeReviewMatchModeSwitchService codeReviewSwitchService;
  private BiDashboardDatasetProvider provider;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    qualityBoardRdService = mock(QualityBoardRdService.class);
    summaryBoardService = mock(SystemTestDefectSummaryBoardService.class);
    statisticBoardRegistry = mock(StatisticBoardRegistry.class);
    phaseScopeResolver = mock(SystemTestPhaseScopeResolver.class);
    codeReviewSwitchService = mock(CodeReviewMatchModeSwitchService.class);
    provider = new BiDashboardDatasetProvider(
        jdbcTemplate,
        qualityBoardRdService,
        summaryBoardService,
        statisticBoardRegistry,
        phaseScopeResolver,
        codeReviewSwitchService);
  }

  @Test
  void descriptorExposesSingleAggregateDatasetAndAllDashboardSections() {
    var descriptor = provider.descriptor();

    assertThat(descriptor.datasetKey()).isEqualTo("bi-dashboard");
    assertThat(descriptor.schemaVersion()).isEqualTo("1.1");
    assertThat(descriptor.parameters()).extracting("name")
        .containsExactly(
            "productVersion", "codeGranularity", "codeSource", "repositoryName", "sections");
    assertThat(descriptor.fields()).extracting("path")
        .containsExactly(
            "productVersion",
            "availableProductVersions[]",
            "testingPhases[]",
            "includedSections[]",
            "qualityTargets.metrics[]",
            "moduleFixRates[]",
            "reviewDistributions.byType[]",
            "reviewDistributions.byCategory[]",
            "reviewDistributions.densities[]",
            "phaseFixes",
            "severityDistribution",
            "defectCauseDistribution",
            "delayedDefects",
            "fixUsers[]",
            "codeSubmissionTrend");
  }

  @Test
  void missingProductVersionIsRejectedBeforeAnyDataAccess() {
    assertThatThrownBy(() -> provider.load(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("productVersion");
  }

  @Test
  void productVersionWithoutEnabledTestingPhaseIsRejected() {
    when(phaseScopeResolver.resolveLegacyCrownCadPhases("CC2026R4"))
        .thenReturn(java.util.List.of());

    assertThatThrownBy(() -> provider.load(java.util.Map.of("productVersion", "CC2026R4")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有启用的系统测试阶段");
  }

  @Test
  void selectedModuleFixRateSectionDoesNotLoadUnrelatedDashboardData() {
    var snapshot = new SystemTestModuleFixRateSnapshot(
        "CC2026R3", java.util.List.of("系统测试3轮"), java.util.List.of());
    when(phaseScopeResolver.resolveLegacyCrownCadPhases("CC2026R3"))
        .thenReturn(snapshot.testingPhases());
    when(phaseScopeResolver.listEnabledLegacyCrownCadParentNames())
        .thenReturn(java.util.List.of("CC2026R4", "CC2026R3"));
    when(summaryBoardService.loadExternalModuleFixRates("CC2026R3"))
        .thenReturn(snapshot);

    var payload = provider.load(java.util.Map.of(
        "productVersion", "CC2026R3",
        "sections", "moduleFixRates"));

    assertThat(payload.includedSections()).containsExactly("moduleFixRates");
    assertThat(payload.availableProductVersions()).containsExactly("CC2026R4", "CC2026R3");
    verify(summaryBoardService).loadExternalModuleFixRates("CC2026R3");
    verifyNoInteractions(
        jdbcTemplate, qualityBoardRdService, statisticBoardRegistry, codeReviewSwitchService);
  }

  @Test
  void unsupportedSectionIsRejectedBeforeDataAccess() {
    assertThatThrownBy(() -> provider.load(java.util.Map.of(
        "productVersion", "CC2026R3",
        "sections", "unknown")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown");

    verifyNoInteractions(
        jdbcTemplate,
        qualityBoardRdService,
        summaryBoardService,
        statisticBoardRegistry,
        phaseScopeResolver,
        codeReviewSwitchService);
  }
}
