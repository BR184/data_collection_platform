package com.data.collection.platform.domain.issue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 建议类/常规缺陷纯判定特征测试。 */
class SuggestionMetricRulesTest {

  @Test
  void suggestionColumn_excludesRegularDefect() {
    assertTrue(SuggestionMetricRules.isSuggestionColumnIssue(false, null, "SUGGESTION", null));
    assertTrue(SuggestionMetricRules.isSuggestionColumnIssue(false, null, "LEVEL2", "建议类"));
    assertTrue(SuggestionMetricRules.isSuggestionColumnIssue(false, "建议", "LEVEL2", null));
    assertFalse(SuggestionMetricRules.isSuggestionColumnIssue(false, null, "LEVEL2", "功能"));
    assertFalse(SuggestionMetricRules.isSuggestionColumnIssue(true, "需求如此", "LEVEL2", null));
  }

  @Test
  void regularMetric_excludesSuggestionAndExcluded() {
    assertTrue(SuggestionMetricRules.isRegularMetricIssue(false, null, "LEVEL2", "功能"));
    assertFalse(SuggestionMetricRules.isRegularMetricIssue(true, "需求如此", "LEVEL2", "功能"));
    assertFalse(SuggestionMetricRules.isRegularMetricIssue(false, null, "SUGGESTION", "功能"));
    assertFalse(SuggestionMetricRules.isRegularMetricIssue(false, null, "LEVEL2", "类别：建议"));
  }

  @Test
  void excludedOnlyBecauseSuggestion_stillCountsInSuggestionColumn() {
    assertTrue(SuggestionMetricRules.isExcludedOnlyBecauseSuggestion(true, "建议"));
    assertFalse(SuggestionMetricRules.isExcludedOnlyBecauseSuggestion(false, "建议"));
  }
}
