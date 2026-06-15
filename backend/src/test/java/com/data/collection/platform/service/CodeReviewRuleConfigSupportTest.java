package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.CodeReviewRuleConfig;
import com.data.collection.platform.entity.CodeReviewRuleConfigCondition;
import com.data.collection.platform.entity.CodeReviewRuleConfigGroup;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeReviewRuleConfigSupportTest {

  @Test
  void shouldIgnoreMeaninglessNotEmptyRuleForModuleName() {
    CodeReviewIllegalRecordView row = row("module-a", "owner-a", "master");
    CodeReviewRuleConfig config = config("moduleName", "isNotEmpty", "");

    assertThat(CodeReviewRuleConfigSupport.hasReadyConfig(config)).isFalse();
    assertThat(CodeReviewRuleConfigSupport.matchesRow(row, config)).isTrue();
    assertThat(CodeReviewRuleConfigSupport.apply(List.of(row), config)).containsExactly(row);
  }

  @Test
  void shouldJudgeIllegalWhenTargetBranchIsOutsideAllowedBranches() {
    CodeReviewIllegalRecordView row = row("module-a", "owner-a", "feature/demo");
    CodeReviewRuleConfig config = config("targetBranch", "notIn", "master,dev");

    assertThat(CodeReviewRuleConfigSupport.hasReadyConfig(config)).isTrue();
    assertThat(CodeReviewRuleConfigSupport.apply(List.of(row), config)).containsExactly(row);
    assertThat(CodeReviewRuleConfigSupport.explainRow(row, config))
        .containsExactly("\u76ee\u6807\u5206\u652f\u4e0d\u5728\u5141\u8bb8\u8303\u56f4");
  }

  @Test
  void shouldExplainOnlyActuallyMatchedConditionForAnyGroup() {
    CodeReviewIllegalRecordView row = row("", "owner-a", "master");
    CodeReviewRuleConfig config =
        new CodeReviewRuleConfig(
            true,
            List.of(new CodeReviewRuleConfigGroup(
                "g1",
                "any",
                List.of(
                    new CodeReviewRuleConfigCondition("c1", "moduleName", "isEmpty", ""),
                    new CodeReviewRuleConfigCondition("c2", "owner", "isEmpty", "")))),
            null);

    assertThat(CodeReviewRuleConfigSupport.explainRow(row, config))
        .containsExactly("\u7f3a\u5c11\u6a21\u5757\u540d");
  }

  @Test
  void shouldExplainAllActuallyMatchedConditionsForAnyGroup() {
    CodeReviewIllegalRecordView row = row("", "", "master");
    CodeReviewRuleConfig config =
        new CodeReviewRuleConfig(
            true,
            List.of(new CodeReviewRuleConfigGroup(
                "g1",
                "any",
                List.of(
                    new CodeReviewRuleConfigCondition("c1", "moduleName", "isEmpty", ""),
                    new CodeReviewRuleConfigCondition("c2", "owner", "isEmpty", "")))),
            null);

    assertThat(CodeReviewRuleConfigSupport.explainRow(row, config))
        .containsExactly("\u7f3a\u5c11\u6a21\u5757\u540d", "缺少被走查人");
  }

  @Test
  void shouldUseOriginalIllegalReasonsWhenCustomRuleIsNotEnabled() {
    CodeReviewIllegalRecordView row =
        row("", "", "master", List.of("\u7f3a\u5c11\u6a21\u5757\u540d", "缺少被走查人"));

    assertThat(CodeReviewRuleConfigSupport.explainRow(row, null))
        .containsExactly("\u7f3a\u5c11\u6a21\u5757\u540d", "缺少被走查人");
  }

  private CodeReviewRuleConfig config(String fieldKey, String operator, String value) {
    return new CodeReviewRuleConfig(
        true,
        List.of(new CodeReviewRuleConfigGroup(
            "g1",
            "all",
            List.of(new CodeReviewRuleConfigCondition("c1", fieldKey, operator, value)))),
        null);
  }

  private CodeReviewIllegalRecordView row(String moduleName, String owner, String targetBranch) {
    return row(moduleName, owner, targetBranch, List.of());
  }

  private CodeReviewIllegalRecordView row(
      String moduleName, String owner, String targetBranch, List<String> illegalTypes) {
    return new CodeReviewIllegalRecordView(
        "merge_request",
        "cc",
        1001L,
        101,
        1L,
        "feat: demo",
        "http://gitlab.example.com/root/demo/-/merge_requests/101",
        owner,
        "Project A",
        "repo-a",
        LocalDateTime.of(2026, 4, 21, 10, 0),
            owner,
        "Bob",
        moduleName,
        targetBranch,
        illegalTypes,
        "Reviewer",
        "Assignee",
        "DONE",
        20,
        "",
        LocalDateTime.of(2026, 4, 21, 9, 30),
        "SCANNED",
        0,
        "",
        "",
        0.8,
        0,
        120,
        10,
        0,
        0,
        0,
        0,
        0,
        360,
        0.36,
        0.0,
        0.0,
        2,
        60,
        "DemoFunction",
        120);
  }
}
