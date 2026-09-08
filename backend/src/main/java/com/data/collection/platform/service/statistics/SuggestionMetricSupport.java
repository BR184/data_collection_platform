package com.data.collection.platform.service.statistics;

import org.springframework.util.StringUtils;

final class SuggestionMetricSupport {
  static final String SUGGESTION_HEADER_TOOLTIP =
      "建议类缺陷单独统计，不计入一级、二级、三级、P1/P2/P3、总计、修复率、关闭率和占比。";

  private static final String SUGGESTION = "建议";

  private SuggestionMetricSupport() {}

  /*
   * 业务背景：
   * 老平台为了避免“建议类 + 二级缺陷”这类议题污染二级缺陷数量，在系统测试公共过滤中
   * 直接排除了 category 包含“建议”的议题，因此老平台的“建议类缺陷”列经常固定为 0。
   * 现在领导确认的新口径是：建议类要在“建议类缺陷”列中单独展示，但绝不能影响其它列。
   *
   * 也就是说，一条议题即使同时带有“建议”和“二级缺陷”，也只能进入建议类列，不能进入
   * 二级缺陷、P2/P3、总计、修复率、关闭率、占比等常规指标。这里故意不 1:1 对齐老平台
   * 的 0 值结果，而是对齐领导确认后的业务结果。
   *
   * 如果后续有人或另一个 AI 因为“新老平台不一致”想把建议类列改回 0，请先和当前业务/开发
   * 负责人确认这段历史：这里不是漏对齐，而是老平台实现能力不足导致的折中，新平台按新的
   * 领导口径保留建议类独立列，同时保护其它缺陷指标不被建议类污染。
   */
  static boolean isSuggestionColumnIssue(boolean excluded, String exclusionReason, String severityLevel, String category) {
    if (!isSuggestionFact(severityLevel, category, exclusionReason)) {
      return false;
    }
    return !excluded || isExcludedOnlyBecauseSuggestion(excluded, exclusionReason);
  }

  static boolean isRegularMetricIssue(boolean excluded, String exclusionReason, String severityLevel, String category) {
    return !excluded && !isSuggestionFact(severityLevel, category, exclusionReason);
  }

  private static boolean isSuggestionFact(String severityLevel, String category, String exclusionReason) {
    return "SUGGESTION".equalsIgnoreCase(severityLevel)
        || containsSuggestion(category)
        || SUGGESTION.equals(exclusionReason);
  }

  static boolean isExcludedOnlyBecauseSuggestion(boolean excluded, String exclusionReason) {
    return excluded && SUGGESTION.equals(exclusionReason);
  }

  static boolean containsSuggestion(String category) {
    return StringUtils.hasText(category) && category.contains(SUGGESTION);
  }

  static String regularMetricSql(String tableAlias) {
    String prefix = StringUtils.hasText(tableAlias) ? tableAlias + "." : "";
    return "coalesce(" + prefix + "is_excluded,false) = false"
        + " and coalesce(" + prefix + "severity_level,'') <> 'SUGGESTION'"
        + " and coalesce(" + prefix + "category,'') not like '%建议%'"
        + " and coalesce(" + prefix + "exclusion_reason,'') <> '建议'";
  }

  static String suggestionMetricSql(String tableAlias) {
    String prefix = StringUtils.hasText(tableAlias) ? tableAlias + "." : "";
    return "(coalesce(" + prefix + "severity_level,'') = 'SUGGESTION'"
        + " or coalesce(" + prefix + "category,'') like '%建议%'"
        + " or coalesce(" + prefix + "exclusion_reason,'') = '建议')"
        + " and (coalesce(" + prefix
        + "is_excluded,false) = false or coalesce(" + prefix + "exclusion_reason,'') = '建议')";
  }
}
