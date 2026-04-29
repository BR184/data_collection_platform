package com.data.collection.platform.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

final class CodeReviewIllegalRuleRegistry {

  static final String MISSING_MODULE_LABEL = "\u7f3a\u5c11\u6a21\u5757\u6807\u7b7e";
  static final String MISSING_OWNER_LABEL = "\u7f3a\u5c11\u6807\u6ce8\u8d23\u4efb\u4eba";
  static final String MISSING_REVIEW_LABEL = "\u65e0\u4ee3\u7801\u8d70\u67e5";
  static final String NOT_SCANNED_LABEL = "\u672a\u4ee3\u7801\u626b\u63cf";
  static final String OPEN_SCAN_ISSUE_LABEL = "\u9759\u6001\u626b\u63cf\u95ee\u9898\u672a\u5173\u95ed";
  static final String MISSING_COMMENT_RATE_LABEL = "\u7f3a\u5c11\u4ee3\u7801\u6ce8\u91ca\u6bd4\u4f8b";
  static final String MISSING_DEFECT_COUNT_LABEL = "\u7f3a\u5c11\u7f3a\u9677\u6570\u91cf";
  static final String MISSING_ADDED_LINES_LABEL = "\u7f3a\u5c11\u65b0\u589e\u4ee3\u7801\u884c\u6570";

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
              "missing-module",
              MISSING_MODULE_LABEL,
              source -> source.labelTitles().isEmpty()),
          new CodeReviewIllegalRule(
              "missing-owner",
              MISSING_OWNER_LABEL,
              source -> !StringUtils.hasText(source.owner())),
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
              source -> source.scanBugCount() != null && source.scanBugCount() > 0),
          new CodeReviewIllegalRule(
              "missing-comment-rate",
              MISSING_COMMENT_RATE_LABEL,
              source -> source.commentRate() == null),
          new CodeReviewIllegalRule(
              "missing-defect-count",
              MISSING_DEFECT_COUNT_LABEL,
              source -> source.defectCount() == null),
          new CodeReviewIllegalRule(
              "missing-added-lines",
              MISSING_ADDED_LINES_LABEL,
              source -> source.addedLines() == null));

  private static final List<CodeReviewIllegalRuleGroup> EXPLANATION_GROUPS =
      List.of(
          new CodeReviewIllegalRuleGroup(
              "missing-module-check",
              "\u68c0\u67e5\u6a21\u5757\u6807\u7b7e",
              "\u5982\u679c\u6a21\u5757\u4e3a\u7a7a\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u201c\u7f3a\u5c11\u6a21\u5757\u6807\u7b7e\u201d\u3002",
              List.of("missing-module")),
          new CodeReviewIllegalRuleGroup(
              "missing-owner-check",
              "\u68c0\u67e5\u6807\u6ce8\u8d23\u4efb\u4eba",
              "\u5982\u679c\u8d23\u4efb\u4eba\u4e3a\u7a7a\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u201c\u7f3a\u5c11\u6807\u6ce8\u8d23\u4efb\u4eba\u201d\u3002",
              List.of("missing-owner")),
          new CodeReviewIllegalRuleGroup(
              "review-check",
              "\u68c0\u67e5\u4ee3\u7801\u8d70\u67e5\u8bb0\u5f55",
              "\u5982\u679c\u8fd8\u6ca1\u6709\u5f62\u6210\u6709\u6548\u7684\u4ee3\u7801\u8d70\u67e5\u8bb0\u5f55\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u201c\u65e0\u4ee3\u7801\u8d70\u67e5\u201d\u3002",
              List.of("missing-review")),
          new CodeReviewIllegalRuleGroup(
              "scan-check",
              "\u68c0\u67e5\u4ee3\u7801\u626b\u63cf\u7ed3\u679c",
              "\u5982\u679c\u660e\u786e\u6807\u8bb0\u4e3a\u672a\u4ee3\u7801\u626b\u63cf\uff0c\u6216\u8005\u9759\u6001\u626b\u63cf\u95ee\u9898\u6570\u5927\u4e8e 0\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u5bf9\u5e94\u7684\u975e\u6cd5\u7c7b\u578b\u3002",
              List.of("not-scanned", "open-scan-issue")),
          new CodeReviewIllegalRuleGroup(
              "missing-metric-check",
              "\u68c0\u67e5\u5916\u90e8\u6307\u6807",
              "\u5982\u679c\u4ee3\u7801\u6ce8\u91ca\u6bd4\u4f8b\u3001\u7f3a\u9677\u6570\u91cf\u6216\u65b0\u589e\u4ee3\u7801\u884c\u6570\u7f3a\u5931\uff0c\u5c31\u4f1a\u88ab\u5224\u5b9a\u4e3a\u5bf9\u5e94\u7684\u975e\u6cd5\u7c7b\u578b\u3002",
              List.of("missing-comment-rate", "missing-defect-count", "missing-added-lines")));

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
