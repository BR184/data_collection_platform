package com.data.collection.platform.service;

import com.data.collection.platform.entity.ReviewDataProblemItemResponse;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

final class ReviewDataMetricCalculator {
  private static final Set<String> NON_EFFECTIVE_STATUSES = Set.of("已拒绝", "未评审", "无问题");

  private ReviewDataMetricCalculator() {}

  static boolean isEffectiveProblem(String problemStatus, String problemCategory) {
    String status = TextQuerySupport.normalizeDisplay(problemStatus);
    String category = TextQuerySupport.normalizeDisplay(problemCategory);
    return !NON_EFFECTIVE_STATUSES.contains(status) && !"无问题".equals(category);
  }

  static ReviewRecordMetrics recordMetrics(
      int reviewScalePages,
      int effectiveProblemCount,
      double totalWorkload,
      int docSpecification,
      int integrity,
      int functionality,
      int feasibility) {
    int safeReviewScalePages = Math.max(0, reviewScalePages);
    int safeEffectiveProblemCount = Math.max(0, effectiveProblemCount);
    double safeTotalWorkload = Math.max(0D, totalWorkload);
    double problemDensity =
        safeReviewScalePages <= 0
            ? 0D
            : ReviewDataNumberSupport.roundToTwoDecimals(safeEffectiveProblemCount * 1D / safeReviewScalePages);
    double reviewEfficiency =
        safeTotalWorkload <= 0D
            ? 0D
            : ReviewDataNumberSupport.roundToTwoDecimals(safeEffectiveProblemCount / safeTotalWorkload);
    double reviewRate =
        safeTotalWorkload <= 0D
            ? 0D
            : ReviewDataNumberSupport.roundToTwoDecimals(safeReviewScalePages / safeTotalWorkload);
    double weightedDefectDensity =
        weightedDefectDensity(
            safeReviewScalePages,
            safeEffectiveProblemCount,
            Math.max(0, docSpecification),
            Math.max(0, integrity),
            Math.max(0, functionality),
            Math.max(0, feasibility));
    return new ReviewRecordMetrics(problemDensity, reviewEfficiency, reviewRate, weightedDefectDensity);
  }

  static double weightedDefectDensity(
      int reviewScalePages,
      int defectCount,
      int docSpecification,
      int integrity,
      int functionality,
      int feasibility) {
    int pagePerDefect = reviewScalePages <= 0 || defectCount <= 0 ? 0 : reviewScalePages / defectCount;
    if (pagePerDefect == 0) {
      return 0D;
    }
    double weightedDefectCount =
        docSpecification + integrity * 1.5D + functionality * 2D + feasibility * 2D;
    return ReviewDataNumberSupport.roundToTwoDecimals(weightedDefectCount / pagePerDefect);
  }

  static ReviewProblemSummary problemSummary(
      ReviewDataRecordProjection record, List<ReviewDataProblemItemResponse> items) {
    List<ReviewDataProblemItemResponse> safeItems = items == null ? List.of() : items;
    int defectCount = safeItems.size();
    int reviewScalePages = record.reviewScalePages() == null ? 0 : Math.max(0, record.reviewScalePages());
    int value1 = defectCount == 0 ? 0 : reviewScalePages / defectCount;
    double workload =
        safeItems.stream()
            .map(ReviewDataProblemItemResponse::workloadHours)
            .filter(java.util.Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .sum();
    int docSpecification = countProblemCategory(safeItems, "文档规范");
    int integrity = countProblemCategory(safeItems, "完整性");
    int functionality = countProblemCategory(safeItems, "功能性");
    int feasibility = countProblemCategory(safeItems, "可行性");
    double weightedDefectDensity =
        weightedDefectDensity(
            reviewScalePages, defectCount, docSpecification, integrity, functionality, feasibility);
    double defectEfficiency =
        reviewScalePages == 0 || value1 == 0
            ? 0D
            : ReviewDataNumberSupport.roundToTwoDecimals((double) reviewScalePages / value1);
    double reviewRate =
        workload == 0D ? 0D : ReviewDataNumberSupport.roundToTwoDecimals((double) value1 / workload);
    return new ReviewProblemSummary(
        defectCount,
        reviewScalePages,
        value1,
        workload,
        docSpecification,
        integrity,
        functionality,
        feasibility,
        weightedDefectDensity,
        defectEfficiency,
        reviewRate);
  }

  private static int countProblemCategory(List<ReviewDataProblemItemResponse> items, String category) {
    return (int)
        items.stream()
            .filter(item -> StringUtils.hasText(item.problemCategory()))
            .filter(item -> category.equals(item.problemCategory().trim()))
            .count();
  }

  interface ReviewDataRecordProjection {
    Integer reviewScalePages();
  }

  record ReviewRecordMetrics(
      double problemDensity,
      double reviewEfficiency,
      double reviewRate,
      double weightedDefectDensity) {}

  record ReviewProblemSummary(
      int defectCount,
      int sumCount,
      int value1,
      double workload,
      int docSpecification,
      int integrity,
      int functionality,
      int feasibility,
      double weightedDefectDensity,
      double defectEfficiency,
      double reviewRate) {}
}
