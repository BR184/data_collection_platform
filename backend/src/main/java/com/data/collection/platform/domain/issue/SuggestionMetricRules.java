package com.data.collection.platform.domain.issue;

/**
 * 建议类/常规缺陷纯判定规则。
 *
 * <p>与页面 DTO、Spring Bean、数据库无关；SQL 谓词仍由统计层 {@code SuggestionMetricSupport} 提供，
 * 二者语义必须一致。旧汇总与新统计共用本类，禁止复制第二套实现。
 */
public final class SuggestionMetricRules {
  private static final String SUGGESTION = "建议";

  private SuggestionMetricRules() {}

  /**
   * 判断议题是否进入“建议类”统计列。
   *
   * @param excluded 是否已被公共业务排除
   * @param exclusionReason 排除原因（可空）
   * @param severityLevel 归一化严重级别（可空）
   * @param category 归一化类别（可空）
   * @return true 表示计入建议类列
   */
  public static boolean isSuggestionColumnIssue(
      boolean excluded, String exclusionReason, String severityLevel, String category) {
    if (!isSuggestionFact(severityLevel, category, exclusionReason)) {
      return false;
    }
    return !excluded || isExcludedOnlyBecauseSuggestion(excluded, exclusionReason);
  }

  /**
   * 判断议题是否进入常规缺陷指标（D 集合）。
   *
   * @param excluded 是否已被公共业务排除
   * @param exclusionReason 排除原因（可空）
   * @param severityLevel 归一化严重级别（可空）
   * @param category 归一化类别（可空）
   * @return true 表示计入常规缺陷
   */
  public static boolean isRegularMetricIssue(
      boolean excluded, String exclusionReason, String severityLevel, String category) {
    return !excluded && !isSuggestionFact(severityLevel, category, exclusionReason);
  }

  /** 仅因建议而被排除的议题仍可进入建议类列。 */
  public static boolean isExcludedOnlyBecauseSuggestion(boolean excluded, String exclusionReason) {
    return excluded && SUGGESTION.equals(exclusionReason);
  }

  /** 类别文本是否包含“建议”。 */
  public static boolean containsSuggestion(String category) {
    return category != null && !category.isBlank() && category.contains(SUGGESTION);
  }

  private static boolean isSuggestionFact(
      String severityLevel, String category, String exclusionReason) {
    return "SUGGESTION".equalsIgnoreCase(severityLevel)
        || containsSuggestion(category)
        || SUGGESTION.equals(exclusionReason);
  }
}
