package com.data.collection.platform.service.analytics;

import com.data.collection.platform.service.CodeReviewDataReadMode;

/** Converts the request-scoped Analytics mode to the code-review domain mode. */
public final class CodeReviewAnalyticsReadMode {
  private CodeReviewAnalyticsReadMode() {}

  public static CodeReviewDataReadMode from(AnalyticsDashboardQueryContext.ReadMode readMode) {
    if (readMode == AnalyticsDashboardQueryContext.ReadMode.MATCH_MODE) {
      //兼容模式-MatchMode：转换后的值必须沿一次请求全链路透传，禁止查询时重新读取开关。
      return CodeReviewDataReadMode.MATCH_MODE;
    }
    return CodeReviewDataReadMode.FORMAL;
  }
}
