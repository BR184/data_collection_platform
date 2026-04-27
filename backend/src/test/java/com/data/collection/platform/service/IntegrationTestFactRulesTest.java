package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationTestFactRulesTest {

  @Test
  void shouldRejectMissingCoreFields() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(null, 8, null, 1, 0, 0);

    assertThat(result.legal()).isFalse();
    assertThat(result.parseStatus()).isEqualTo("PARTIAL");
    assertThat(result.validationReason()).contains("执行用例总数", "本次未通过用例数");
  }

  @Test
  void shouldRejectNegativeValuesAcrossAllCountFields() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, 8, 2, 1, -1, 0);

    assertThat(result.legal()).isFalse();
    assertThat(result.parseStatus()).isEqualTo("PARTIAL");
    assertThat(result.validationReason()).contains("本次问题用例数", "不能为负数");
  }

  @Test
  void shouldRejectCoreCountMismatch() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, 7, 2, 1, 0, 0);

    assertThat(result.legal()).isFalse();
    assertThat(result.parseStatus()).isEqualTo("PARSED");
    assertThat(result.validationReason()).contains("执行用例总数应等于通过用例数 + 本次未通过用例数");
  }

  @Test
  void shouldAcceptCompleteAndBalancedRecord() {
    IntegrationTestFactRules.ValidationResult result =
        IntegrationTestFactRules.validateRecord(10, 8, 2, 1, 0, 0);

    assertThat(result.legal()).isTrue();
    assertThat(result.parseStatus()).isEqualTo("PARSED");
    assertThat(result.validationReason()).isNull();
  }
}
