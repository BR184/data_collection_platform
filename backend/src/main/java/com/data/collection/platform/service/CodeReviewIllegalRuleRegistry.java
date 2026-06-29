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
  static final String MISSING_REVIEW_LABEL = "代码走查异常";
  static final String LEGACY_MISSING_REVIEW_FILTER_LABEL = "无代码走查";
  static final String NOT_SCANNED_LABEL = "未进行代码扫描";
  static final String LEGACY_MISSING_PROJECT_FILTER_LABEL = "未标注项目名称";
  static final String LEGACY_MISSING_MODULE_FILTER_LABEL = "未标注模块名称";
  static final String LEGACY_NOT_SCANNED_FILTER_LABEL = "未代码扫描";
  static final String OPEN_SCAN_ISSUE_LABEL = "静态扫描问题未关闭";
  static final String COMMENT_RATE_NOT_PASS_LABEL = "代码注释量未达标";
  static final String SCAN_FAILED_LABEL = "静态扫描失败";
  static final String CLANG_RESULT_FALSE_LABEL = "注释率分析工具Clang分析错误";
  static final String GITLAB_ERROR_LABEL = "GitLab 接口报错";
  private static final Set<String> GITLAB_ERROR_VALUES = Set.of(GITLAB_ERROR_LABEL, "GitLab接口报错");
  static final List<String> LEGACY_REVIEW_EXCEPTION_REASONS =
      List.of("没有合法评论", "代码走查时间或缺陷数异常", "代码走查标题异常", "代码走查记录行数异常");

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
                  isLegacyReviewException(source.reviewExceptionReason())
                      || isLegacyReviewException(source.owner())
                      || isLegacyReviewException(source.reviewerNames())
                      || isLegacyReviewException(source.assigneeNames())),
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
              source -> OPEN_SCAN_ISSUE_LABEL.equals(source.bugCountResult())),
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
                  isGitlabError(source.scanStatus())
                      || isGitlabError(source.targetBranch())
                      || isGitlabError(source.owner())
                      || isGitlabError(source.reviewerNames())
                      || isGitlabError(source.assigneeNames())));

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
              "如果还没有形成有效的代码走查记录，就会被判定为“代码走查异常”。",
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

  static boolean matchesIllegalType(List<String> illegalTypes, String expected) {
    String normalizedExpected = TextQuerySupport.trimToNull(expected);
    if (normalizedExpected == null) {
      return true;
    }
    if (LEGACY_MISSING_PROJECT_FILTER_LABEL.equals(normalizedExpected)) {
      normalizedExpected = MISSING_PROJECT_LABEL;
    } else if (LEGACY_MISSING_MODULE_FILTER_LABEL.equals(normalizedExpected)) {
      normalizedExpected = MISSING_MODULE_LABEL;
    } else if (LEGACY_MISSING_REVIEW_FILTER_LABEL.equals(normalizedExpected)) {
      normalizedExpected = MISSING_REVIEW_LABEL;
    } else if (LEGACY_NOT_SCANNED_FILTER_LABEL.equals(normalizedExpected)) {
      normalizedExpected = NOT_SCANNED_LABEL;
    }
    return illegalTypes.contains(normalizedExpected);
  }

  static boolean matchesDefaultIllegalType(List<String> illegalTypes, String source) {
    if (illegalTypes == null || illegalTypes.isEmpty()) {
      return false;
    }
    if (includesClangInDefaultIllegal(source)) {
      return true;
    }
    return illegalTypes.stream().anyMatch(label -> !CLANG_RESULT_FALSE_LABEL.equals(label));
  }

  static boolean includesClangInDefaultIllegal(String source) {
    String normalized = GitlabSourceInstanceSupport.normalizeSourceInstance(source);
    return !("cc".equals(normalized) || GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE.equals(normalized));
  }

  static boolean isGitlabError(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized != null && GITLAB_ERROR_VALUES.contains(normalized);
  }

  static boolean isLegacyReviewException(String value) {
    String normalized = TextQuerySupport.trimToNull(value);
    return normalized != null && LEGACY_REVIEW_EXCEPTION_REASONS.contains(normalized);
  }
}
