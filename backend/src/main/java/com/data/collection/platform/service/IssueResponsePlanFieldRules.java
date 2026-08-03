package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
  private static final Pattern DISPLAY_WHITESPACE_PATTERN =
      Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

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
   * 将计划合并版本分支规范为单行事实文本，不解释或丢弃来源中的分支语义。
   *
   * @param value 最新调研模板中的字段内容
   * @return 仅合并连续空白后的来源文本；空输入返回空字符串
   */
  static String normalizePlannedMergeBranchText(String value) {
    if (value == null) {
      return "";
    }
    return DISPLAY_WHITESPACE_PATTERN.matcher(value).replaceAll(" ").trim();
  }

  /**
   * 判断计划合并版本分支是否只包含规范版本成员。
   *
   * <p>该契约只供客户问题非法模板判定使用，不能用于生成展示事实。
   *
   * @param value 模板字段内容
   * @return 一个或多个 {@code CCyyyyRn} 成员以 {@code &} 分隔时返回 {@code true}
   */
  static boolean hasOnlyCanonicalVersionBranches(String value) {
    String normalized = normalizePlannedMergeBranchText(value);
    if (normalized.isEmpty()) {
      return false;
    }
    for (String rawBranch : normalized.split("&", -1)) {
      String branch = normalizePlannedMergeBranchText(rawBranch);
      if (branch.isEmpty() || !VERSION_BRANCH_PATTERN.matcher(branch).matches()) {
        return false;
      }
    }
    return true;
  }
}
