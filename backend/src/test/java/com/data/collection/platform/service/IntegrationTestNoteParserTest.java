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
}
