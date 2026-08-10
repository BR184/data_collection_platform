package com.data.collection.platform.bi.infrastructure;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.source.BiCatTestSource;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.infrastructure.BiCatMirrorModels.PublishedTestSnapshot;
import java.math.BigDecimal;
import java.util.List;

/** 从 CAT 当前发布指针一次性读取单元/集成页面；页面请求不会访问 CAT 网络。 */
public final class BiCatTestSourceAdapter implements BiCatTestSourcePort {
  private static final BigDecimal TARGET_RATE = new BigDecimal("95.00");

  private final BiCatMirrorRepository repository;

  BiCatTestSourceAdapter(BiCatMirrorRepository repository) {
    this.repository = repository;
  }

  /** 按稳定产品版本和测试阶段读取一份已原子发布的本地镜像快照。 */
  @Override
  public BiCatTestSource load(BiProductVersionScope scope, TestStage stage) {
    // 阶段一：只读取本地已发布镜像指针；页面请求不直接依赖 CAT 网络可用性。
    String testStage = stage.name();
    PublishedTestSnapshot snapshot = repository.loadPublishedTestSnapshot(scope, testStage)
        .orElseThrow(() -> new BiCatContractUnavailableException(
            "当前产品版本尚未发布 CAT " + stageLabel(stage) + "镜像，请先在数据镜像设置中同步"));
    String sourceVersion = BiCatMirrorRepository.sourceVersion(
        scope.id(), testStage, snapshot.publishedVersion());
    // 阶段二：空镜像保持 EMPTY，字段缺失则按 CAT 契约失败，不能静默填零。
    if ("EMPTY".equals(snapshot.dataStatus())) {
      return new BiCatTestSource(
          BiDataStatus.EMPTY,
          sourceVersion,
          snapshot.snapshotId().toString(),
          "当前 CAT 产品版本与测试阶段没有统计数据",
          new BiTestQualityPageData(null, List.of(), List.of()));
    }

    // 阶段三：将 CAT 快照中的总览、模块和功能数据映射为统一达标模型。
    var overall = attainment(
        new BiTestQualityPageData.CountSummary(
            BiTestQualityPageData.CountUnit.FUNCTION,
            requireCount(snapshot.attainedFunctionCount()),
            requireCount(snapshot.totalFunctionCount())),
        requireRate(snapshot.overallPassRate()));
    List<BiTestQualityPageData.ModuleAttainment> modules = snapshot.modules().stream()
        .map(module -> new BiTestQualityPageData.ModuleAttainment(
            module.id(),
            module.name(),
            attainment(
                new BiTestQualityPageData.CountSummary(
                    BiTestQualityPageData.CountUnit.FUNCTION,
                    module.attainedFunctionCount(),
                    module.totalFunctionCount()),
                module.passRate())))
        .toList();
    List<BiTestQualityPageData.FunctionAttainment> functions = snapshot.functions().stream()
        .map(function -> new BiTestQualityPageData.FunctionAttainment(
            function.moduleId(),
            function.id(),
            function.name(),
            attainment(null, function.passRate())))
        .toList();
    return new BiCatTestSource(
        BiDataStatus.READY,
        sourceVersion,
        snapshot.snapshotId().toString(),
        "",
        new BiTestQualityPageData(overall, modules, functions));
  }

  private BiTestQualityPageData.Attainment attainment(
      BiTestQualityPageData.CountSummary counts,
      BigDecimal passRate) {
    return new BiTestQualityPageData.Attainment(
        counts,
        passRate,
        TARGET_RATE,
        passRate.compareTo(TARGET_RATE) >= 0);
  }

  private long requireCount(Long value) {
    if (value == null) {
      throw new BiCatContractUnavailableException("CAT 已发布镜像缺少功能计数");
    }
    return value;
  }

  private BigDecimal requireRate(BigDecimal value) {
    if (value == null) {
      throw new BiCatContractUnavailableException("CAT 已发布镜像缺少通过率");
    }
    return value;
  }

  private String stageLabel(TestStage stage) {
    return stage == TestStage.UNIT_TEST ? "单元测试" : "集成测试";
  }
}
