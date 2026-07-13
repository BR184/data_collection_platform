package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationTestFactRulesTest {

  @Test
  void marksCompleteMatchingCountsAsLegal() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, 8, 2, 3, 1, 0);

    assertThat(result.legal()).isTrue();
    assertThat(result.parseStatus()).isEqualTo("PARSED");
    assertThat(result.validationReason()).isNull();
  }

  @Test
  void keepsParsedButMismatchedCountsForDiagnostics() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, 8, 1, 3, 1, 0);

    assertThat(result.legal()).isFalse();
    assertThat(result.parseStatus()).isEqualTo("PARSED");
    assertThat(result.validationReason()).contains("执行用例总数");
  }

  @Test
  void marksMissingCoreCountsAsPartial() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, null, null, null, null, null);

    assertThat(result.legal()).isFalse();
    assertThat(result.parseStatus()).isEqualTo("PARTIAL");
    assertThat(result.validationReason()).contains("通过用例数", "本次未通过用例数");
  }
}
