package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.BiSystemTestCalculator;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiSystemTestPageData;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.port.BiSystemTestSourcePort;

/** 系统测试页面 BFF 应用服务。 */
public final class BiSystemTestPageService {
  private final BiProductVersionPort versionPort;
  private final BiSystemTestSourcePort sourcePort;
  private final BiSystemTestCalculator calculator;

  public BiSystemTestPageService(
      BiProductVersionPort versionPort,
      BiSystemTestSourcePort sourcePort,
      BiSystemTestCalculator calculator) {
    this.versionPort = versionPort;
    this.sourcePort = sourcePort;
    this.calculator = calculator;
  }

  /** 冻结产品版本、轮次目录和缺陷来源版本后生成完整系统测试页面。 */
  public BiPageResponse<BiSystemTestPageData> load(long productVersionId) {
    var scope = versionPort.requireScope(productVersionId);
    return calculator.calculate(sourcePort.load(scope));
  }
}
