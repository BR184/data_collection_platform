package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.BiCodingCalculator;
import com.data.collection.platform.bi.domain.model.BiCodingPageData;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.port.BiCodingSourcePort;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;

/** 编码阶段页面 BFF 应用服务。 */
public final class BiCodingPageService {
  private final BiProductVersionPort versionPort;
  private final BiCodingSourcePort sourcePort;
  private final BiCodingCalculator calculator;

  public BiCodingPageService(
      BiProductVersionPort versionPort,
      BiCodingSourcePort sourcePort,
      BiCodingCalculator calculator) {
    this.versionPort = versionPort;
    this.sourcePort = sourcePort;
    this.calculator = calculator;
  }

  /** 冻结产品版本、页面局部筛选和来源版本后生成完整编码页面。 */
  public BiPageResponse<BiCodingPageData> load(
      long productVersionId,
      BiCodingSourcePort.Query query) {
    var scope = versionPort.requireScope(productVersionId);
    return calculator.calculate(sourcePort.load(scope, query));
  }
}
