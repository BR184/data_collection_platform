package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.source.BiCatTestSource;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;

/** CAT 单元测试和集成测试页面的唯一出站端口。 */
public interface BiCatTestSourcePort {
  /** 按产品版本和测试阶段原子读取整体、模块、功能三层数据。 */
  BiCatTestSource load(BiProductVersionScope scope, TestStage stage);

  enum TestStage {
    UNIT_TEST("unit-test", "UT", "CAT 单元测试数据源尚未启用或完成稳定 ID 映射"),
    INTEGRATION_TEST(
        "integration-test",
        "IT",
        "CAT 集成测试数据源尚未启用或完成稳定 ID 映射");

    private final String pageKey;
    private final String metricPrefix;
    private final String contractUnavailableMessage;

    TestStage(String pageKey, String metricPrefix, String contractUnavailableMessage) {
      this.pageKey = pageKey;
      this.metricPrefix = metricPrefix;
      this.contractUnavailableMessage = contractUnavailableMessage;
    }

    public String pageKey() {
      return pageKey;
    }

    public String metricPrefix() {
      return metricPrefix;
    }

    /** 返回当前阶段已核实的 CAT 契约缺口，不混用另一阶段的接入状态。 */
    public String contractUnavailableMessage() {
      return contractUnavailableMessage;
    }
  }
}
