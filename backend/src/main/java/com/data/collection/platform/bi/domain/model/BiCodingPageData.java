package com.data.collection.platform.bi.domain.model;

import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 编码阶段页面的强类型数据。 */
public record BiCodingPageData(
    Summary summary,
    List<CodeTrendPoint> codeTrend,
    List<SubmissionPoint> submissionTrend,
    List<CategoryBreakdown> reviewCategories,
    List<Contributor> contributors,
    List<ModuleIncrement> moduleIncrements,
    List<ModuleReviewQuality> moduleReviewQuality,
    List<ReviewPoint> reviewPoints,
    List<CommentRateTrendPoint> commentRateTrend,
    List<ReviewDensityTrendPoint> reviewDensityTrend,
    Coverage commentRateCoverage,
    Coverage reviewDensityCoverage) {
  public BiCodingPageData {
    codeTrend = copy(codeTrend);
    submissionTrend = copy(submissionTrend);
    reviewCategories = copy(reviewCategories);
    contributors = copy(contributors);
    moduleIncrements = copy(moduleIncrements);
    moduleReviewQuality = copy(moduleReviewQuality);
    reviewPoints = copy(reviewPoints);
    commentRateTrend = copy(commentRateTrend);
    reviewDensityTrend = copy(reviewDensityTrend);
  }

  /** 一个编码指标对当前走查记录的可计算覆盖。 */
  public record Coverage(long totalObservations, long validObservations, BigDecimal coveragePercent) {}

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** 编码页整体代码量和人工走查质量。 */
  public record Summary(
      Long addedLines,
      BigDecimal addedKloc,
      Long mergeRequestCount,
      Long contributorCount,
      BigDecimal reviewDefectDensity,
      BigDecimal reviewSpeedLocPerHour,
      Boolean reviewDensityAchieved) {}

  /** 新增代码和累计代码趋势点。 */
  public record CodeTrendPoint(LocalDate period, long addedLines, long cumulativeLines) {}

  /** 提交数与合并请求数趋势点。 */
  public record SubmissionPoint(LocalDate period, Long commitCount, long mergeRequestCount) {}

  /** 人工走查问题类别构成。 */
  public record CategoryBreakdown(String category, long count, BigDecimal sharePercent) {}

  /** 来源快照内按作者字段分组的个人代码贡献。 */
  public record Contributor(BiSourceDimension contributor, long addedLines) {}

  /** 来源快照内按模块字段分组的模块代码增量。 */
  public record ModuleIncrement(BiSourceDimension module, long addedLines) {}

  /** 来源快照内按模块字段分组的人工走查质量。 */
  public record ModuleReviewQuality(
      BiSourceDimension module,
      BigDecimal defectDensity,
      BigDecimal reviewSpeedLocPerHour,
      Boolean achieved) {}

  /** 单次人工走查质量散点。 */
  public record ReviewPoint(
      long codeReviewId,
      LocalDate reviewDate,
      BiSourceDimension module,
      BigDecimal reviewSpeedKlocPerHour,
      BigDecimal defectDensity,
      Boolean achieved) {}

  /** 按日或周对合法注释率观测取非加权算术平均后的代码注释率趋势点（CD-38A）。 */
  public record CommentRateTrendPoint(LocalDate period, BigDecimal averageCommentRate) {}

  /** 按日或周使用总体 KLOC 公式计算的代码走查缺陷密度。 */
  public record ReviewDensityTrendPoint(LocalDate period, BigDecimal reviewDefectDensity) {}
}
