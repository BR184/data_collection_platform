package com.data.collection.platform.bi.domain.model;

import com.data.collection.platform.bi.domain.source.BiSourceDimension;
import java.math.BigDecimal;
import java.util.List;

/** 系统测试页面的强类型质量、轮次、原因、延期和人员数据。 */
public record BiSystemTestPageData(
    Overview overview,
    List<QualityTarget> qualityTargets,
    List<RoundQuality> rounds,
    SeverityDistribution severity,
    List<ModuleQuality> modules,
    List<CauseCategory> causeCategories,
    List<CauseSubcategory> causeSubcategories,
    List<DelayCell> delays,
    List<DeveloperWorkload> developers) {
  public BiSystemTestPageData {
    qualityTargets = copy(qualityTargets);
    rounds = copy(rounds);
    modules = copy(modules);
    causeCategories = copy(causeCategories);
    causeSubcategories = copy(causeSubcategories);
    delays = copy(delays);
    developers = copy(developers);
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** 系统测试整体缺陷概览。 */
  public record Overview(
      long totalCount,
      long fixedCount,
      long openCount,
      BigDecimal fixRate) {}

  /** 一级、P1 或 P2 的独立质量目标。 */
  public record QualityTarget(
      String key,
      String label,
      Long totalCount,
      Long fixedCount,
      BigDecimal fixRate,
      BigDecimal targetRate,
      BiDataStatus status,
      Boolean achieved) {}

  /** 单个测试轮次的严重度与关闭状态。 */
  public record RoundQuality(
      String roundId,
      String roundName,
      int roundOrder,
      long levelOneCount,
      long levelTwoCount,
      long levelThreeCount,
      long submittedCount,
      long closedCount,
      long openCount,
      BigDecimal closeRate) {}

  /** 系统测试整体严重度构成。 */
  public record SeverityDistribution(
      long levelOneCount,
      long levelTwoCount,
      long levelThreeCount) {}

  /** 来源快照内按模块字段分组的系统测试模块质量。 */
  public record ModuleQuality(
      BiSourceDimension module,
      long totalCount,
      long fixedCount,
      long openCount,
      BigDecimal fixRate,
      long levelOneCount,
      long levelTwoCount,
      long levelThreeCount,
      BigDecimal levelOneFixRate,
      BigDecimal p1FixRate,
      BigDecimal p2FixRate) {}

  /** 原因大类构成。 */
  public record CauseCategory(String categoryId, String categoryName, long count, BigDecimal sharePercent) {}

  /** 原因子类构成及所属大类。 */
  public record CauseSubcategory(
      String categoryId,
      String categoryName,
      String subcategoryId,
      String subcategoryName,
      long count,
      BigDecimal sharePercent) {}

  /** 延期原因和严重度交叉矩阵单元格。 */
  public record DelayCell(String reason, String severity, long count) {}

  /** 当前指派人负责总数及待修复重点值。 */
  public record DeveloperWorkload(
      BiSourceDimension assignee,
      long totalCount,
      long fixedCount,
      long openCount) {}
}
