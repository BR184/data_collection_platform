package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewIllegalRuleRegistryTest {

  @Test
  void shouldEvaluateIllegalTypesInStableOrder() {
    CodeReviewIllegalRecordSource source =
        new CodeReviewIllegalRecordSource(
            "cc",
            1L,
            101,
            2001L,
            "MR",
            "Project A",
            "repo-a",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            "Alice",
            "",
            "",
            "",
            "",
            "master",
            "未标注模块名",
            List.of(),
            null,
            null,
            null,
            LocalDateTime.of(2026, 4, 10, 8, 30),
            "未进行代码扫描",
            2,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(CodeReviewIllegalRuleRegistry.evaluateIllegalTypes(source))
        .containsExactly(
            CodeReviewIllegalRuleRegistry.MISSING_MODULE_LABEL,
            CodeReviewIllegalRuleRegistry.MISSING_REVIEW_LABEL,
            CodeReviewIllegalRuleRegistry.NOT_SCANNED_LABEL,
            CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL);
  }

  @Test
  void shouldNotTreatTechnicalScanStatusCodesAsLegacyNotScanned() {
    CodeReviewIllegalRecordSource source =
        new CodeReviewIllegalRecordSource(
            "cc",
            1L,
            101,
            2001L,
            "MR",
            "Project A",
            "repo-a",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            "Alice",
            "",
            "",
            "",
            "",
            "master",
            "module-a",
            List.of(),
            null,
            null,
            null,
            LocalDateTime.of(2026, 4, 10, 8, 30),
            "NOT_SCANNED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(CodeReviewIllegalRuleRegistry.evaluateIllegalTypes(source))
        .doesNotContain(CodeReviewIllegalRuleRegistry.NOT_SCANNED_LABEL);
  }

  @Test
  void shouldMatchGroupedRulesForExplanation() {
    CodeReviewIllegalRecordView view =
        new CodeReviewIllegalRecordView(
            "merge_request",
            "cc",
            1L,
            101,
            2001L,
            "MR",
            "http://gitlab/repo/-/merge_requests/101",
            "Owner",
            "Project A",
            "repo-a",
            LocalDateTime.of(2026, 4, 10, 9, 0),
            "Alice",
            "Bob",
            "module-a",
            "master",
            List.of(
                CodeReviewIllegalRuleRegistry.NOT_SCANNED_LABEL,
                CodeReviewIllegalRuleRegistry.OPEN_SCAN_ISSUE_LABEL),
            "Reviewer",
            "Assignee",
            "DONE",
            10,
            "",
            LocalDateTime.of(2026, 4, 10, 8, 30),
            "NOT_SCANNED",
            2,
            "",
            "静态扫描问题未关闭",
            0.5,
            1,
            100,
            10,
            0,
            1,
            0,
            0,
            0,
            600,
            0.6,
            10.0,
            6.0,
            2,
            50,
            "DemoFunction",
            100);

    CodeReviewIllegalRuleGroup scanGroup =
        CodeReviewIllegalRuleRegistry.explanationGroups().stream()
            .filter(group -> group.key().equals("scan-check"))
            .findFirst()
            .orElseThrow();

    assertThat(CodeReviewIllegalRuleRegistry.countMatches(List.of(view), scanGroup)).isEqualTo(1);
    assertThat(CodeReviewIllegalRuleRegistry.filterMatches(List.of(view), scanGroup))
        .containsExactly(view);
  }
}
