package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueResponsePlanFieldRulesTest {

  @Test
  void shouldNormalizePlanMergeBranchTextWithoutDiscardingSourceMeaning() {
    assertThat(
            IssueResponsePlanFieldRules.normalizePlannedMergeBranchText(
                "  dev\r\n  release_2026R3、优云智能_release_2026R3  "))
        .isEqualTo("dev release_2026R3、优云智能_release_2026R3");
    assertThat(
            IssueResponsePlanFieldRules.normalizePlannedMergeBranchText(
                "\u00a0dev\u2028release_2026R3\u3000"))
        .isEqualTo("dev release_2026R3");
    assertThat(IssueResponsePlanFieldRules.normalizePlannedMergeBranchText(" \t\r\n "))
        .isEmpty();
  }

  @Test
  void shouldKeepCanonicalVersionValidationIndependentFromFactProjection() {
    assertThat(IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches("CC2026R4"))
        .isTrue();
    assertThat(
            IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches(
                "\u00a0CC2026R4\u3000&\u2028CC2026R5\u00a0& CC2026R4"))
        .isTrue();
    assertThat(IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches("dev"))
        .isFalse();
    assertThat(
            IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches(
                "CC2026R4 & release/CC2026R5"))
        .isFalse();
    assertThat(IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches("CC2026R4 &"))
        .isFalse();
    assertThat(IssueResponsePlanFieldRules.hasOnlyCanonicalVersionBranches(""))
        .isFalse();
  }
}
