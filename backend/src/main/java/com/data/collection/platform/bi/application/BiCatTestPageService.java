package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.model.BiDataStatus;
import com.data.collection.platform.bi.domain.model.BiMetricTrace;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiPageSection;
import com.data.collection.platform.bi.domain.model.BiTestQualityPageData;
import com.data.collection.platform.bi.domain.port.BiCatContractUnavailableException;
import com.data.collection.platform.bi.domain.port.BiCatTestSourcePort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import java.util.List;

/** CAT 单元测试或集成测试页面 BFF 应用服务。 */
public final class BiCatTestPageService {
  private static final String RULE_VERSION = "bi-cat-test-v2";

  private final BiCatTestSourcePort.TestStage stage;
  private final BiProductVersionPort versionPort;
  private final BiCatTestSourcePort sourcePort;

  public BiCatTestPageService(
      BiCatTestSourcePort.TestStage stage,
      BiProductVersionPort versionPort,
      BiCatTestSourcePort sourcePort) {
    this.stage = stage;
    this.versionPort = versionPort;
    this.sourcePort = sourcePort;
  }

  /**
   * 读取 CAT 原子三层页面；真实契约未冻结时返回 INCOMPLETE，禁止由平台事实补齐。
   */
  public BiPageResponse<BiTestQualityPageData> load(long productVersionId) {
    var scope = versionPort.requireScope(productVersionId);
    try {
      var source = sourcePort.load(scope, stage);
      return BiPageResponse.create(
          stage.pageKey(),
          source.status(),
          source.sourceVersion(),
          source.snapshotId(),
          RULE_VERSION,
          List.of(new BiPageSection(
              "test-quality",
              "测试质量达成",
              source.status(),
              source.message())),
          traces(),
          source.data());
    } catch (BiCatContractUnavailableException unavailable) {
      return BiPageResponse.create(
          stage.pageKey(),
          BiDataStatus.INCOMPLETE,
          "",
          "",
          RULE_VERSION,
          List.of(new BiPageSection(
              "test-quality",
              "测试质量达成",
              BiDataStatus.INCOMPLETE,
              unavailable.getMessage() + "，当前页面不会使用数据采集平台或 mock 补齐")),
          traces(),
          new BiTestQualityPageData(null, List.of(), List.of()));
    }
  }

  private List<BiMetricTrace> traces() {
    String prefix = stage.metricPrefix();
    return List.of(new BiMetricTrace(
        List.of(prefix + "-01", prefix + "-02", prefix + "-03", prefix + "-04", prefix + "-05",
            prefix + "-06", prefix + "-07", prefix + "-08", prefix + "-09", prefix + "-10",
            prefix + "-11", prefix + "-12", prefix + "-13", prefix + "-14", prefix + "-15",
            prefix + "-16", prefix + "-17", prefix + "-18", prefix + "-19"),
        "CAT",
        List.of(
            new BiMetricTrace.SourceField("模块 ID", "data.result[].id"),
            new BiMetricTrace.SourceField("功能 ID", "statisticsInfoList[].featureUniqueId"),
            new BiMetricTrace.SourceField("整体通过率", "data.passRate"),
            new BiMetricTrace.SourceField("模块通过率", "data.result[].testPassRate"),
            new BiMetricTrace.SourceField("功能通过率", "statisticsInfoList[].testPassRate"),
            new BiMetricTrace.SourceField("达标功能数", "data.result[].passFeatureCount"),
            new BiMetricTrace.SourceField("未达标功能数", "data.result[].notPassFeatureCount")),
        "统计功能数=达标功能数+未达标功能数；整体/模块/功能通过率不低于95%为达标；功能数量不映射为用例数量",
        "CAT提供通过率与功能达标数量，BI服务端计算达标状态"));
  }
}
