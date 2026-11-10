package com.data.collection.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CC_PRODUCT 问题调研模板中计划字段的格式规则。 */
public final class IssueResponsePlanFieldRules {
  private static final int MIN_PLAN_YEAR = 2020;
  private static final int MAX_PLAN_YEAR = 2040;
  private static final Pattern PLAN_DATE_PATTERN =
      Pattern.compile(
          "^\\s*(\\d{4})\\s*[.,，、/·`年]\\s*(\\d{1,2})\\s*[.,，、/·`月]\\s*(\\d{1,2})\\s*日?\\s*$");
  private static final Pattern ALTERNATIVE_SEPARATOR_PATTERN = Pattern.compile("[,，、;；＆]");
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
  public static String normalizePlannedMergeBranchText(String value) {
    if (value == null) {
      return "";
    }
    return DISPLAY_WHITESPACE_PATTERN.matcher(value).replaceAll(" ").trim();
  }

  /**
   * 判断自由填写的分支列表是否使用半角 & 分隔且没有空成员。
   *
   * <p>表单提交与客户问题模板判定共用此规则；不限制分支名称，不改写历史展示事实。
   *
   * @param value 模板字段内容
   * @return 一个或多个非空分支以 {@code &} 分隔，且没有其他列表分隔符时返回 {@code true}
   */
  public static boolean hasValidBranchMembers(String value) {
    String normalized = normalizePlannedMergeBranchText(value);
    if (normalized.isEmpty()) {
      return false;
    }
    for (String rawBranch : normalized.split("&", -1)) {
      String branch = normalizePlannedMergeBranchText(rawBranch);
      if (branch.isEmpty() || ALTERNATIVE_SEPARATOR_PATTERN.matcher(branch).find()) {
        return false;
      }
    }
    return true;
  }
}
