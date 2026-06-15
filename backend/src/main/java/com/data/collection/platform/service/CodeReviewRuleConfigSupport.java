package com.data.collection.platform.service;

import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRuleConfigCondition;
import com.data.collection.platform.entity.CodeReviewRuleConfigGroup;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.util.StringUtils;

final class CodeReviewRuleConfigSupport {
  private CodeReviewRuleConfigSupport() {
  }

  static boolean hasReadyConfig(CodeReviewRuleConfig config) {
    return config != null
        && config.enabled()
        && config.groups() != null
        && config.groups().stream().anyMatch(CodeReviewRuleConfigSupport::hasReadyGroup);
  }

  static List<CodeReviewIllegalRecordView> apply(
      List<CodeReviewIllegalRecordView> rows, CodeReviewRuleConfig config) {
    if (!hasReadyConfig(config)) {
      return rows;
    }
    return rows.stream().filter(row -> matchesRow(row, config)).toList();
  }

  static boolean matchesRow(CodeReviewIllegalRecordView row, CodeReviewRuleConfig config) {
    if (!hasReadyConfig(config)) {
      return true;
    }
    return config.groups().stream()
        .filter(CodeReviewRuleConfigSupport::hasReadyGroup)
        .anyMatch(group -> matchesGroup(row, group));
  }

  static List<String> explainRow(CodeReviewIllegalRecordView row, CodeReviewRuleConfig config) {
    if (!hasReadyConfig(config)) {
      return row.illegalTypes() == null || row.illegalTypes().isEmpty()
          ? List.of("\u65e0\u5224\u5b9a\u539f\u56e0")
          : row.illegalTypes();
    }
    LinkedHashSet<String> reasons = new LinkedHashSet<>();
    config.groups().stream()
        .filter(CodeReviewRuleConfigSupport::hasReadyGroup)
        .filter(group -> matchesGroup(row, group))
        .forEach(group -> reasons.addAll(explainMatchedGroup(row, group)));
    return reasons.isEmpty() ? List.of("\u6ee1\u8db3\u6211\u7684\u5224\u5b9a\u89c4\u5219") : List.copyOf(reasons);
  }

  private static List<String> explainMatchedGroup(
      CodeReviewIllegalRecordView row, CodeReviewRuleConfigGroup group) {
    List<CodeReviewRuleConfigCondition> readyConditions =
        group.conditions().stream().filter(CodeReviewRuleConfigSupport::isReadyCondition).toList();
    boolean matchAny = "any".equalsIgnoreCase(TextQuerySupport.trimToNull(group.matchMode()));
    return readyConditions.stream()
        .filter(condition -> !matchAny || matchesCondition(row, condition))
        .map(CodeReviewRuleConfigSupport::describeMatchedCondition)
        .toList();
  }

  private static boolean hasReadyGroup(CodeReviewRuleConfigGroup group) {
    return group != null
        && group.conditions() != null
        && group.conditions().stream().anyMatch(CodeReviewRuleConfigSupport::isReadyCondition);
  }

  private static boolean matchesGroup(CodeReviewIllegalRecordView row, CodeReviewRuleConfigGroup group) {
    List<CodeReviewRuleConfigCondition> readyConditions =
        group.conditions().stream().filter(CodeReviewRuleConfigSupport::isReadyCondition).toList();
    if (readyConditions.isEmpty()) {
      return false;
    }
    boolean matchAny = "any".equalsIgnoreCase(TextQuerySupport.trimToNull(group.matchMode()));
    return matchAny
        ? readyConditions.stream().anyMatch(condition -> matchesCondition(row, condition))
        : readyConditions.stream().allMatch(condition -> matchesCondition(row, condition));
  }

  private static boolean isReadyCondition(CodeReviewRuleConfigCondition condition) {
    if (condition == null || !StringUtils.hasText(condition.fieldKey()) || !StringUtils.hasText(condition.operator())) {
      return false;
    }
    if (!isAllowedCondition(condition)) {
      return false;
    }
    return usesConditionValue(condition) ? StringUtils.hasText(condition.value()) : true;
  }

  private static boolean isAllowedCondition(CodeReviewRuleConfigCondition condition) {
    String fieldKey = condition.fieldKey();
    String operator = condition.operator();
    return switch (fieldKey) {
      case "moduleName", "owner", "commentRateMissing", "defectCountMissing", "addedLinesMissing" ->
          "isEmpty".equals(operator);
      case "reviewRecordMissing" -> "isMissingReview".equals(operator);
      case "scanNotDone" -> "isNotScanned".equals(operator);
      case "scanIssueOpen" -> "hasOpenScanIssue".equals(operator);
      case "targetBranch" -> "notIn".equals(operator);
      case "mergeRequestContent" -> "contains".equals(operator);
      case "commentRateLow" -> "lt".equals(operator);
      case "defectCountHigh", "addedLinesHigh" -> "gt".equals(operator);
      default -> false;
    };
  }

  private static boolean usesConditionValue(CodeReviewRuleConfigCondition condition) {
    return switch (condition.operator()) {
      case "isEmpty", "isMissingReview", "isNotScanned", "hasOpenScanIssue" -> false;
      default -> true;
    };
  }

  private static boolean matchesCondition(CodeReviewIllegalRecordView row, CodeReviewRuleConfigCondition condition) {
    return switch (condition.fieldKey()) {
      case "moduleName" -> !StringUtils.hasText(row.moduleName());
      case "owner" -> !StringUtils.hasText(row.author());
      case "reviewRecordMissing" -> !isNoNeedReview(row.reviewerNames())
          && (StringUtils.hasText(row.reviewExceptionReason())
              || !StringUtils.hasText(row.reviewStatus())
              || row.reviewDurationMinutes() == null);
      case "scanNotDone" -> isNotScanned(row.scanStatus());
      case "scanIssueOpen" -> row.scanBugCount() != null && row.scanBugCount() > 0;
      case "targetBranch" -> matchesNotIn(row.targetBranch(), condition.value());
      case "mergeRequestContent" -> containsAny(row.mergeRequestContent(), condition.value());
      case "commentRateMissing" -> row.commentRate() == null;
      case "commentRateLow" -> matchesNumber(row.commentRate(), condition.value(), NumberRule.LESS_THAN);
      case "defectCountMissing" -> row.defectCount() == null;
      case "defectCountHigh" -> matchesNumber(toDouble(row.defectCount()), condition.value(), NumberRule.GREATER_THAN);
      case "addedLinesMissing" -> row.addedLines() == null;
      case "addedLinesHigh" -> matchesNumber(toDouble(row.addedLines()), condition.value(), NumberRule.GREATER_THAN);
      default -> false;
    };
  }

  private static boolean isNotScanned(String scanStatus) {
    String normalizedStatus = TextQuerySupport.trimToNull(scanStatus);
    return normalizedStatus != null
        && List.of("NOT_SCANNED", "UNSCANNED", "\u672a\u626b\u63cf", "\u672a\u4ee3\u7801\u626b\u63cf", "\u672a\u8fdb\u884c\u4ee3\u7801\u626b\u63cf")
            .stream()
            .anyMatch(status -> TextQuerySupport.equalsNormalized(status, normalizedStatus));
  }

  private static boolean isNoNeedReview(String reviewerNames) {
    return "无需走查".equals(reviewerNames) || "无需走查扫描".equals(reviewerNames);
  }

  private static boolean matchesNotIn(String value, String allowedValuesText) {
    String normalizedValue = TextQuerySupport.trimToNull(value);
    if (normalizedValue == null) {
      return true;
    }
    List<String> allowedValues = splitValues(allowedValuesText);
    return !allowedValues.isEmpty()
        && allowedValues.stream().noneMatch(allowed -> TextQuerySupport.equalsNormalized(allowed, normalizedValue));
  }

  private static boolean containsAny(String value, String keywordsText) {
    String normalizedValue = TextQuerySupport.trimToNull(value);
    if (normalizedValue == null) {
      return false;
    }
    return splitValues(keywordsText).stream()
        .anyMatch(keyword -> TextQuerySupport.containsAbstractSearch(normalizedValue, keyword));
  }

  private static boolean matchesNumber(Double value, String expectedText, NumberRule rule) {
    if (value == null) {
      return false;
    }
    Double expected = parseNumber(expectedText);
    if (expected == null) {
      return false;
    }
    return rule == NumberRule.LESS_THAN ? value < expected : value > expected;
  }

  private static String describeMatchedCondition(CodeReviewRuleConfigCondition condition) {
    return switch (condition.fieldKey()) {
      case "moduleName" -> "\u7f3a\u5c11\u6a21\u5757\u540d";
      case "owner" -> "缺少被走查人";
      case "reviewRecordMissing" -> "\u7f3a\u5c11\u4ee3\u7801\u8d70\u67e5\u8bb0\u5f55";
      case "scanNotDone" -> "\u672a\u5b8c\u6210\u4ee3\u7801\u626b\u63cf";
      case "scanIssueOpen" -> "\u626b\u63cf\u95ee\u9898\u672a\u5173\u95ed";
      case "targetBranch" -> "\u76ee\u6807\u5206\u652f\u4e0d\u5728\u5141\u8bb8\u8303\u56f4";
      case "mergeRequestContent" -> "\u5408\u5e76\u5185\u5bb9\u5305\u542b\u98ce\u9669\u8bcd";
      case "commentRateMissing" -> "\u6ce8\u91ca\u7387\u7f3a\u5931";
      case "commentRateLow" -> "\u6ce8\u91ca\u7387\u8fc7\u4f4e";
      case "defectCountMissing" -> "\u7f3a\u9677\u6570\u7f3a\u5931";
      case "defectCountHigh" -> "\u7f3a\u9677\u6570\u8fc7\u9ad8";
      case "addedLinesMissing" -> "\u65b0\u589e\u4ee3\u7801\u884c\u6570\u7f3a\u5931";
      case "addedLinesHigh" -> "\u65b0\u589e\u4ee3\u7801\u884c\u6570\u8fc7\u9ad8";
      default -> "\u6ee1\u8db3\u6211\u7684\u89c4\u5219";
    };
  }

  private static Double parseNumber(String value) {
    try {
      return Double.parseDouble(value.trim());
    } catch (Exception error) {
      return null;
    }
  }

  private static Double toDouble(Integer value) {
    return value == null ? null : value.doubleValue();
  }

  private static List<String> splitValues(String value) {
    return Arrays.stream(String.valueOf(value == null ? "" : value).split("[,\uff0c\u3001\n]"))
        .map(String::trim)
        .filter(StringUtils::hasText)
        .toList();
  }

  private enum NumberRule {
    LESS_THAN,
    GREATER_THAN
  }
}
