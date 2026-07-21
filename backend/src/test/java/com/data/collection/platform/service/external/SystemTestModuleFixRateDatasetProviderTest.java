package com.data.collection.platform.service.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.statistics.SystemTestModuleFixRateSnapshot;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.statistics.SystemTestDefectSummaryBoardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemTestModuleFixRateDatasetProviderTest {

  @Test
  void payloadIncludesVersionDirectoryWithoutLoadingAggregateDashboard() {
    SystemTestDefectSummaryBoardService summaryBoardService =
        mock(SystemTestDefectSummaryBoardService.class);
    SystemTestPhaseScopeResolver phaseScopeResolver = mock(SystemTestPhaseScopeResolver.class);
    var snapshot = new SystemTestModuleFixRateSnapshot(
        "CC2026R3", List.of("系统测试3轮"), List.of());
    when(summaryBoardService.loadExternalModuleFixRates("CC2026R3")).thenReturn(snapshot);
    when(phaseScopeResolver.listEnabledLegacyCrownCadParentNames())
        .thenReturn(List.of("CC2026R4", "CC2026R3"));
    var provider = new SystemTestModuleFixRateDatasetProvider(
        summaryBoardService, phaseScopeResolver);

    var payload = provider.load(Map.of("productVersion", "CC2026R3"));

    assertThat(provider.descriptor().schemaVersion()).isEqualTo("1.1");
    assertThat(payload.productVersion()).isEqualTo("CC2026R3");
    assertThat(payload.availableProductVersions()).containsExactly("CC2026R4", "CC2026R3");
    assertThat(payload.testingPhases()).containsExactly("系统测试3轮");
  }
}
