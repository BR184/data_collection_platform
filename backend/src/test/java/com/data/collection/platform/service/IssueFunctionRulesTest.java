package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssueFunctionRulesTest {

  @Test
  void bracketInMiddleOfIssueTitleDoesNotChangeIssueFunctionClassification() {
    assertThat(IssueFunctionRules.normalizeFunctionName("修复问题 [工程图]"))
        .isNull();
  }

  @Test
  void leadingBracketStillDefinesIssueFunction() {
    assertThat(IssueFunctionRules.normalizeFunctionName("[工程图] 修复问题"))
        .isEqualTo("工程图");
  }
}
