package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationTestNoteParserTest {

  @Test
  void shouldParseMarkdownTableRowsInsideIntegrationTestSection() {
    String note =
        """
        ## 集成测试数据
        | 字段 | 值 |
        | --- | --- |
        | 功能 | 拉伸 |
        | 执行人 | 张三 |
        | 执行用例总数 | 10 |
        | 本次通过用例数 | 8 |
        | 初始未通过用例数 | 2 |
        | 本次未通过用例数 | 2 |
        | 本次问题用例数 | 1 |
        | 用例外问题数 | 0 |
        ## 其他内容
        | 执行用例总数 | 99 |
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed = IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("拉伸");
    assertThat(parsed.executor()).isEqualTo("张三");
    assertThat(parsed.executeCase()).isEqualTo(10);
    assertThat(parsed.passCase()).isEqualTo(8);
    assertThat(parsed.notPassCase()).isEqualTo(2);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isZero();
  }

  @Test
  void shouldParseMixedSeparatorsAndNumericUnits() {
    String note =
        """
        ## 集成测试数据
        - 功能：拉伸
        - 执行人 = 张三
        - 执行用例总数＝10个
        - 本次通过用例数: 8 个
        - 本次未通过用例数：2 个
        - 本次问题用例数 = 1个
        - 例外问题数＝0个
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed = IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("拉伸");
    assertThat(parsed.executor()).isEqualTo("张三");
    assertThat(parsed.executeCase()).isEqualTo(10);
    assertThat(parsed.passCase()).isEqualTo(8);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isZero();
  }

  @Test
  void shouldParseAnnotatedNumbersByUsingTheLeadingCount() {
    String note =
        """
        ### 集成测试数据
        1. 功能：倒角
        2. 执行人：李四
        3. 执行用例总数：10（含自动化2条）
        4. 本次通过用例数：8（含自动化2条）
        5. 初始未通过用例数：3（历史遗留1条）
        6. 本次未通过用例数：2（阻塞1条）
        7. 本次问题用例数：1（已提单#123）
        8. 用例外问题数：0（无）
        ### 其他内容
        执行用例总数：99
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed = IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("倒角");
    assertThat(parsed.executor()).isEqualTo("李四");
    assertThat(parsed.executeCase()).isEqualTo(10);
    assertThat(parsed.passCase()).isEqualTo(8);
    assertThat(parsed.notPassCase()).isEqualTo(3);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isZero();
  }

  @Test
  void shouldParseHorizontalMarkdownTableRowsAndValidate() {
    String note =
        """
        ## 集成测试数据
        | 功能 | 执行人 | 执行用例总数 | 本次通过用例数 | 初始未通过用例数 | 本次未通过用例数 | 本次问题用例数 | 用例外问题数 |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | 拉伸 | 张三 | 10 | 8 | 2 | 2 | 1 | 0 |
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed = IntegrationTestNoteParser.parse(note);
    IntegrationTestFactRules.ValidationResult validation =
        IntegrationTestFactRules.validateRecord(
            parsed.executeCase(),
            parsed.passCase(),
            parsed.notPassCaseNow(),
            parsed.notPassCase(),
            parsed.problemCase(),
            parsed.exceptionCount());

    assertThat(parsed.functionName()).isEqualTo("拉伸");
    assertThat(parsed.executor()).isEqualTo("张三");
    assertThat(parsed.executeCase()).isEqualTo(10);
    assertThat(parsed.passCase()).isEqualTo(8);
    assertThat(parsed.notPassCase()).isEqualTo(2);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isZero();
    assertThat(validation.legal()).isTrue();
  }

  @Test
  void shouldAggregateMultipleHorizontalRowsIntoOneIssueFact() {
    String note =
        """
        ## 集成测试数据
        | 功能 | 执行人 | 执行用例总数 | 本次通过用例数 | 初始未通过用例数 | 本次未通过用例数 | 本次问题用例数 | 用例外问题数 |
        | --- | --- | --- | --- | --- | --- | --- | --- |
        | 拉伸 | 张三 | 10 | 8 | 2 | 2 | 1 | 0 |
        | 倒角 | 李四 | 5 | 4 | 1 | 1 | 0 | 1 |
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed = IntegrationTestNoteParser.parse(note);
    IntegrationTestFactRules.ValidationResult validation =
        IntegrationTestFactRules.validateRecord(
            parsed.executeCase(),
            parsed.passCase(),
            parsed.notPassCaseNow(),
            parsed.notPassCase(),
            parsed.problemCase(),
            parsed.exceptionCount());

    assertThat(parsed.functionName()).isEqualTo("拉伸, 倒角");
    assertThat(parsed.executor()).isEqualTo("张三, 李四");
    assertThat(parsed.executeCase()).isEqualTo(15);
    assertThat(parsed.passCase()).isEqualTo(12);
    assertThat(parsed.notPassCase()).isEqualTo(3);
    assertThat(parsed.notPassCaseNow()).isEqualTo(3);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isEqualTo(1);
    assertThat(validation.legal()).isTrue();
  }
}
