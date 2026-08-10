package com.data.collection.platform.bi.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.FunctionSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.ModuleSnapshot;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.PublishedTestSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BiCatTestSourceAdapterTest {
  private final BiCatMirrorRepository repository = mock(BiCatMirrorRepository.class);
  private final BiCatTestSourceAdapter adapter = new BiCatTestSourceAdapter(repository);

  @Test
  void load_publishedSnapshot_returnsReadyDataAndLocalSourceVersion() {
    UUID snapshotId = UUID.randomUUID();
    when(repository.loadPublishedTestSnapshot(scope(), "UNIT_TEST"))
        .thenReturn(Optional.of(new PublishedTestSnapshot(
            snapshotId,
            4L,
            "READY",
            new BigDecimal("97.20"),
            12L,
            15L,
            List.of(new ModuleSnapshot(
                "module-1", "草图", 12, 15, new BigDecimal("96.50"), 0)),
            List.of(new FunctionSnapshot(
                "module-1", "feature-1", "拉伸", "核心", new BigDecimal("96.00"), 0)))));

    var result = adapter.load(scope(), BiCatTestSourcePort.TestStage.UNIT_TEST);

    assertThat(result.status()).isEqualTo(BiDataStatus.READY);
    assertThat(result.sourceVersion()).isEqualTo("cat-mirror:10:UNIT_TEST:4");
    assertThat(result.snapshotId()).isEqualTo(snapshotId.toString());
    assertThat(result.data().overall().counts()).isEqualTo(
        new BiTestQualityPageData.CountSummary(
            BiTestQualityPageData.CountUnit.FUNCTION, 12, 15));
    assertThat(result.data().functions()).singleElement().satisfies(function ->
        assertThat(function.attainment().counts()).isNull());
  }

  @Test
  void load_missingPublication_reportsIncompleteContractWithoutNetworkFallback() {
    when(repository.loadPublishedTestSnapshot(scope(), "INTEGRATION_TEST"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> adapter.load(
        scope(), BiCatTestSourcePort.TestStage.INTEGRATION_TEST))
        .isInstanceOf(BiCatContractUnavailableException.class)
        .hasMessageContaining("尚未发布 CAT 集成测试镜像")
        .hasMessageContaining("数据镜像设置");
  }

  private BiProductVersionScope scope() {
    return new BiProductVersionScope(10L, 9L, "CC2026R4", "CC2026R4", 1, List.of());
  }
}
