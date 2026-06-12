package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

final class CodeReviewIllegalRuleRegistry {

  static final String MISSING_PROJECT_LABEL = "未标注项目名";
  static final String MISSING_MODULE_LABEL = "未标注模块名";
  static final String MISSING_REVIEW_LABEL = "无代码走查";
  static final String NOT_SCANNED_LABEL = "未进行代码扫描";
  static final String OPEN_SCAN_ISSUE_LABEL = "静态扫描问题未关闭";
  static final String COMMENT_RATE_NOT_PASS_LABEL = "代码注释量未达标";
  static final String SCAN_FAILED_LABEL = "静态扫描失败";
  static final String CLANG_RESULT_FALSE_LABEL = "注释率分析工具Clang分析错误";
  static final String GITLAB_ERROR_LABEL = "GitLab 接口报错";

  private static final Set<String> NOT_SCANNED_STATUSES =
      Set.of(
          "NOT_SCANNED",
          "UNSCANNED",
          "\u672a\u626b\u63cf",
          "\u672a\u4ee3\u7801\u626b\u63cf",
          "\u672a\u8fdb\u884c\u4ee3\u7801\u626b\u63cf");

  private static final List<CodeReviewIllegalRule> ORDERED_RULES =
      List.of(
          new CodeReviewIllegalRule(
              "missing-project",
              MISSING_PROJECT_LABEL,
              source -> "未标注项目名".equals(source.projectName())),
          new CodeReviewIllegalRule(
              "missing-module",
              MISSING_MODULE_LABEL,
              source -> "未标注模块名".equals(source.moduleName())),
          new CodeReviewIllegalRule(
              "missing-review",
              MISSING_REVIEW_LABEL,
              source ->
                  !StringUtils.hasText(source.reviewStatus())
                      || source.reviewDurationMinutes() == null),
          new CodeReviewIllegalRule(
              "not-scanned",
              NOT_SCANNED_LABEL,
              source ->
                  StringUtils.hasText(source.scanStatus())
                      && NOT_SCANNED_STATUSES.contains(
                          source.scanStatus().trim().toUpperCase(Locale.ROOT))),
          new CodeReviewIllegalRule(
              "open-scan-issue",
              OPEN_SCAN_ISSUE_LABEL,
              source ->
                  OPEN_SCAN_ISSUE_LABEL.equals(source.bugCountResult())
                      || source.scanBugCount() != null && source.scanBugCount() > 0),
          new CodeReviewIllegalRule(
              "comment-rate-not-pass",
              COMMENT_RATE_NOT_PASS_LABEL,
              source -> COMMENT_RATE_NOT_PASS_LABEL.equals(source.annotationRateResult())),
          new CodeReviewIllegalRule(
              "scan-failed",
              SCAN_FAILED_LABEL,
              source -> SCAN_FAILED_LABEL.equals(source.bugCountResult())),
          new CodeReviewIllegalRule(
              "clang-result-false",
              CLANG_RESULT_FALSE_LABEL,
              source -> CLANG_RESULT_FALSE_LABEL.equals(source.annotationRateResult())),
          new CodeReviewIllegalRule(
              "gitlab-error",
              GITLAB_ERROR_LABEL,
              source ->
                  GITLAB_ERROR_LABEL.equals(source.scanStatus())
                      || GITLAB_ERROR_LABEL.equals(source.targetBranch())
                      || GITLAB_ERROR_LABEL.equals(source.reviewerNames())
                      || GITLAB_ERROR_LABEL.equals(source.assigneeNames())));

  private static final List<CodeReviewIllegalRuleGroup> EXPLANATION_GROUPS =
      List.of(
          new CodeReviewIllegalRuleGroup(
              "missing-project-module-check",
              "检查项目名和模块名",
              "如果项目名或模块名为老平台非法占位值，就会被判定为对应的未标注非法类型。",
              List.of("missing-project", "missing-module")),
          new CodeReviewIllegalRuleGroup(
              "review-check",
              "\u68c0\u67e5\u4ee3\u7801\u8d70\u67e5\u8bb0\u5f55",
              "\u5982\u679c\u8fd8\u6ca1\u6709\u5f62\u6210\u6709\u6548\u7684\u4ee3\u7801\u8d70\u67e5\u8bb0\u5f55\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u201c\u65e0\u4ee3\u7801\u8d70\u67e5\u201d\u3002",
              List.of("missing-review")),
          new CodeReviewIllegalRuleGroup(
              "scan-check",
              "\u68c0\u67e5\u4ee3\u7801\u626b\u63cf\u7ed3\u679c",
              "如果明确标记为未代码扫描、静态扫描问题未关闭、静态扫描失败或 GitLab 接口报错，就会被判定为对应非法类型。",
              List.of("not-scanned", "open-scan-issue", "scan-failed", "gitlab-error")),
          new CodeReviewIllegalRuleGroup(
              "comment-rate-check",
              "检查代码注释率结果",
              "如果注释率未达标或 Clang 分析错误，就会被判定为对应非法类型。",
              List.of("comment-rate-not-pass", "clang-result-false")));

  private CodeReviewIllegalRuleRegistry() {
  }

  static List<String> evaluateIllegalTypes(CodeReviewIllegalRecordSource source) {
    return ORDERED_RULES.stream()
        .filter(rule -> rule.matches(source))
        .map(CodeReviewIllegalRule::label)
        .toList();
  }

  static List<CodeReviewIllegalRuleGroup> explanationGroups() {
    return EXPLANATION_GROUPS;
  }

  static long countMatches(List<CodeReviewIllegalRecordView> views, CodeReviewIllegalRuleGroup group) {
    Set<String> labels = labelsFor(group);
    return views.stream()
        .filter(view -> view.illegalTypes().stream().anyMatch(labels::contains))
        .count();
  }

  static List<CodeReviewIllegalRecordView> filterMatches(
      List<CodeReviewIllegalRecordView> views, CodeReviewIllegalRuleGroup group) {
    Set<String> labels = labelsFor(group);
    return views.stream()
        .filter(view -> view.illegalTypes().stream().anyMatch(labels::contains))
        .toList();
  }

  private static Set<String> labelsFor(CodeReviewIllegalRuleGroup group) {
    Set<String> labels = new LinkedHashSet<>();
    for (String ruleKey : group.ruleKeys()) {
      ORDERED_RULES.stream()
          .filter(rule -> rule.key().equals(ruleKey))
          .findFirst()
          .map(CodeReviewIllegalRule::label)
          .ifPresent(labels::add);
    }
    return labels;
  }

  static List<String> labels() {
    return ORDERED_RULES.stream().map(CodeReviewIllegalRule::label).collect(Collectors.toList());
  }

  static List<String> notScannedStatuses() {
    return List.copyOf(NOT_SCANNED_STATUSES);
  }
}
