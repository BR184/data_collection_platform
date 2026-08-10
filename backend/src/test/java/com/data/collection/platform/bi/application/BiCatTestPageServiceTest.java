package com.data.collection.platform.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.source.BiCatTestSource;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiCatTestPageServiceTest {
  @Test
  void returnsVerifiedCatDataWithIncompleteSourceIdentityInsteadOfHidingCharts() {
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    when(versions.requireScope(10L)).thenReturn(new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of()));
    var attainment = new BiTestQualityPageData.Attainment(
        new BiTestQualityPageData.CountSummary(
            BiTestQualityPageData.CountUnit.FUNCTION, 12, 15),
        new java.math.BigDecimal("97.20"),
        new java.math.BigDecimal("95.00"),
        true);
    BiCatTestSourcePort sourcePort = (scope, stage) -> new BiCatTestSource(
        BiDataStatus.INCOMPLETE,
        "",
        "",
        "CAT 尚未提供来源快照身份",
        new BiTestQualityPageData(attainment, List.of(), List.of()));
    var service = new BiCatTestPageService(
        BiCatTestSourcePort.TestStage.INTEGRATION_TEST, versions, sourcePort);

    var response = service.load(10L);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.data().overall()).isEqualTo(attainment);
    assertThat(response.sections()).first().extracting("message").asString()
        .contains("来源快照身份")
        .doesNotContain("mock");
  }

  @Test
  void returnsIncompleteWithoutPlatformOrMockFallbackWhenCatContractIsUnavailable() {
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    when(versions.requireScope(10L)).thenReturn(new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of()));
    BiCatTestPageService service = new BiCatTestPageService(
        BiCatTestSourcePort.TestStage.UNIT_TEST,
        versions,
        (scope, stage) -> {
          throw new BiCatContractUnavailableException("当前产品版本尚未发布 CAT 单元测试镜像");
        });

    var response = service.load(10L);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.sourceVersion()).isEmpty();
    assertThat(response.data().overall()).isNull();
    assertThat(response.data().modules()).isEmpty();
    assertThat(response.sections()).first().extracting("message").asString()
        .contains("尚未发布 CAT 单元测试镜像")
        .contains("不会使用数据采集平台或 mock 补齐");
  }

  @Test
  void explainsVerifiedIntegrationContractGapsWithoutClaimingTheContractIsMissing() {
    BiProductVersionPort versions = mock(BiProductVersionPort.class);
    when(versions.requireScope(10L)).thenReturn(new BiProductVersionScope(
        10L, 9L, "CC2026R4", "CC2026R4", 1, List.of()));
    BiCatTestPageService service = new BiCatTestPageService(
        BiCatTestSourcePort.TestStage.INTEGRATION_TEST,
        versions,
        (scope, stage) -> {
          throw new BiCatContractUnavailableException("当前产品版本尚未发布 CAT 集成测试镜像");
        });

    var response = service.load(10L);

    assertThat(response.status()).isEqualTo(BiDataStatus.INCOMPLETE);
    assertThat(response.sections()).first().extracting("message").asString()
        .contains("尚未发布 CAT 集成测试镜像")
        .contains("不会使用数据采集平台或 mock 补齐");
  }
}
