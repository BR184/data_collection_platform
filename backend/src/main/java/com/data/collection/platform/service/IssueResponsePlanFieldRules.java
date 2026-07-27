package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CC_PRODUCT 问题调研模板中计划字段的格式规则。 */
final class IssueResponsePlanFieldRules {
  private static final int MIN_PLAN_YEAR = 2020;
  private static final int MAX_PLAN_YEAR = 2040;
  private static final Pattern PLAN_DATE_PATTERN =
      Pattern.compile(
          "^\\s*(\\d{4})\\s*[.,，、/·`年]\\s*(\\d{1,2})\\s*[.,，、/·`月]\\s*(\\d{1,2})\\s*日?\\s*$");
  private static final Pattern VERSION_BRANCH_PATTERN = Pattern.compile("CC\\d{4}R[1-9]\\d*");

  private IssueResponsePlanFieldRules() {}

  /**
   * 解析唯一且完整的计划解决日期。
   *
   * @param value 模板字段内容
   * @return 合法日期对应的开始时刻；格式、年份或日历日期不合法时返回 {@code null}
   */
  static LocalDateTime parsePlannedResolutionAt(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    Matcher matcher = PLAN_DATE_PATTERN.matcher(normalized);
    if (!matcher.matches()) {
      return null;
    }
    try {
      int year = Integer.parseInt(matcher.group(1));
      if (year < MIN_PLAN_YEAR || year > MAX_PLAN_YEAR) {
        return null;
      }
      return LocalDate.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)))
          .atStartOfDay();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /**
   * 将计划合并版本分支规范为稳定的版本列表文本。
   *
   * @param value 模板字段内容
   * @return 以 {@code " & "} 连接的唯一版本；存在非法成员时返回空字符串
   */
  static String normalizePlannedMergeVersionBranches(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    if (normalized == null) {
      return "";
    }
    LinkedHashSet<String> branches = new LinkedHashSet<>();
    for (String rawBranch : normalized.split("&", -1)) {
      String branch = TextQuerySupport.trimToNull(rawBranch);
      if (branch == null || !VERSION_BRANCH_PATTERN.matcher(branch).matches()) {
        return "";
      }
      branches.add(branch);
    }
    return String.join(" & ", branches);
  }
}
