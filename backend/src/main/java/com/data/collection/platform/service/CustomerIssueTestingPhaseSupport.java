package com.data.collection.platform.service;

/**
 * 将客户问题测试阶段的展示值与可空事实分离。
 *
 * <p>GitLab 来源可能没有测试阶段标签，此时事实保持为空，本类为客户问题页面、筛选和导出提供统一展示值。</p>
 */
public final class CustomerIssueTestingPhaseSupport {
  static final String UNSPECIFIED_DISPLAY_VALUE = "未设定测试阶段";

  private CustomerIssueTestingPhaseSupport() {}

  /**
   * 返回客户问题页面和导出的稳定测试阶段展示值。
   *
   * @param rawTestingPhase 事实层测试阶段，可为空
   * @return 原测试阶段，空值统一返回“未设定测试阶段”
   */
  public static String display(String rawTestingPhase) {
    String normalized = TextQuerySupport.trimToNull(rawTestingPhase);
    return normalized == null ? UNSPECIFIED_DISPLAY_VALUE : normalized;
  }

  static boolean isUnspecifiedFilter(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return UNSPECIFIED_DISPLAY_VALUE.equals(normalized);
  }

  static boolean matchesFilter(String rawTestingPhase, String filterValue) {
    String normalizedFilter = TextQuerySupport.trimToNull(filterValue);
    if (normalizedFilter == null) {
      return true;
    }
    String normalizedRaw = TextQuerySupport.trimToNull(rawTestingPhase);
    if (isUnspecifiedFilter(normalizedFilter)) {
      return normalizedRaw == null;
    }
    return normalizedRaw != null && normalizedRaw.equalsIgnoreCase(normalizedFilter);
  }
}
