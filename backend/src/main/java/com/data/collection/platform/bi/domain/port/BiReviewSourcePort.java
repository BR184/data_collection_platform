package com.data.collection.platform.bi.domain.port;

import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiReviewSource;

/** 需求和设计评审已发布可见事实的只读端口。 */
public interface BiReviewSourcePort {
  /** 按稳定产品版本和明确评审类型读取同一来源版本的数据。 */
  BiReviewSource load(BiProductVersionScope scope, ReviewStage stage);

  /** BI 支持的普通评审阶段及其平台精确类型。 */
  enum ReviewStage {
    REQUIREMENTS("requirements", "需求说明书评审"),
    DESIGN("design", "设计说明书评审");

    private final String pageKey;
    private final String reviewType;

    ReviewStage(String pageKey, String reviewType) {
      this.pageKey = pageKey;
      this.reviewType = reviewType;
    }

    public String pageKey() {
      return pageKey;
    }

    public String reviewType() {
      return reviewType;
    }
  }
}
