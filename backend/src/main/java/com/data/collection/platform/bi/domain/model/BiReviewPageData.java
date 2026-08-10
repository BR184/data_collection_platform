package com.data.collection.platform.bi.domain.model;

import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 需求或设计评审页面的强类型数据。 */
public record BiReviewPageData(
    Summary summary,
    List<CategoryBreakdown> categories,
    List<ModuleQuality> modules,
    List<ReviewPoint> reviewPoints,
    Coverage moduleCoverage,
    Coverage reviewPointCoverage) {
  public BiReviewPageData {
    categories = categories == null ? List.of() : List.copyOf(categories);
    modules = modules == null ? List.of() : List.copyOf(modules);
    reviewPoints = reviewPoints == null ? List.of() : List.copyOf(reviewPoints);
  }

  /** 一个图表对当前来源记录的可计算覆盖。 */
  public record Coverage(long totalObservations, long validObservations, BigDecimal coveragePercent) {}

  /** 页面整体评审质量摘要。 */
  public record Summary(
      long reviewCount,
      Long reviewedPages,
      BigDecimal workloadHours,
      Long effectiveProblemCount,
      BigDecimal defectDensity,
      BigDecimal reviewRate,
      Boolean achieved) {}

  /** 有效问题类别构成。 */
  public record CategoryBreakdown(String category, long count, BigDecimal sharePercent) {}

  /** 来源快照内按模块字段分组的模块级质量。 */
  public record ModuleQuality(
      BiSourceDimension module,
      long reviewedPages,
      BigDecimal workloadHours,
      long effectiveProblemCount,
      BigDecimal defectDensity,
      BigDecimal reviewRate,
      Boolean achieved) {}

  /** 单次评审的速率和密度散点。 */
  public record ReviewPoint(
      long reviewId,
      LocalDate reviewDate,
      BiSourceDimension module,
      BigDecimal reviewRate,
      BigDecimal defectDensity,
      Boolean achieved) {}
}
