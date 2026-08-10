package com.data.collection.platform.bi.application;

import com.data.collection.platform.bi.domain.BiReviewCalculator;
import com.data.collection.platform.bi.domain.model.BiPageResponse;
import com.data.collection.platform.bi.domain.model.BiReviewPageData;
import com.data.collection.platform.bi.domain.port.BiProductVersionPort;
import com.data.collection.platform.bi.domain.port.BiReviewSourcePort;

/** 单个普通评审阶段的页面 BFF 应用服务。 */
public final class BiReviewPageService {
  private final BiReviewSourcePort.ReviewStage stage;
  private final BiProductVersionPort versionPort;
  private final BiReviewSourcePort sourcePort;
  private final BiReviewCalculator calculator;

  public BiReviewPageService(
      BiReviewSourcePort.ReviewStage stage,
      BiProductVersionPort versionPort,
      BiReviewSourcePort sourcePort,
      BiReviewCalculator calculator) {
    this.stage = stage;
    this.versionPort = versionPort;
    this.sourcePort = sourcePort;
    this.calculator = calculator;
  }

  /** 冻结产品版本和来源版本后生成完整需求或设计页面。 */
  public BiPageResponse<BiReviewPageData> load(long productVersionId) {
    var scope = versionPort.requireScope(productVersionId);
    return calculator.calculate(stage.pageKey(), sourcePort.load(scope, stage));
  }
}
