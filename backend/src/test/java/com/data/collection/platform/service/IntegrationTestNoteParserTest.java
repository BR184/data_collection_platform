package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationTestNoteParserTest {

  @Test
  void parsesLegacyHeadingFieldsInsideIntegrationSection() {
    String note =
        """
        ## 集成测试数据

        ### 功能：打开工程图
        ### 执行人：张三
        ### 执行用例总数：53
        ### 初始未通过用例数：2
        ### 本次通过用例数：51
        ### 本次未通过用例数：2
        ### 本次问题用例数：1
        ### 用例外问题数：0

        ## 其他说明
        ### 执行用例总数：999
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed =
        IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("打开工程图");
    assertThat(parsed.executor()).isEqualTo("张三");
    assertThat(parsed.executeCase()).isEqualTo(53);
    assertThat(parsed.passCase()).isEqualTo(51);
    assertThat(parsed.notPassCase()).isEqualTo(2);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isZero();
  }

  @Test
  void aggregatesHorizontalMarkdownRows() {
    String note =
        """
        ## 集成测试数据

        | 功能 | 执行人 | 执行用例总数 | 通过用例数 | 未通过用例数 | 问题用例数 | 用例外问题数 |
        | --- | --- | ---: | ---: | ---: | ---: | ---: |
        | 草图 | 张三 | 10 | 8 | 2 | 1 | 0 |
        | 拉伸 | 李四 | 20 | 18 | 2 | 0 | 1 |
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed =
        IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("草图, 拉伸");
    assertThat(parsed.executor()).isEqualTo("张三, 李四");
    assertThat(parsed.executeCase()).isEqualTo(30);
    assertThat(parsed.passCase()).isEqualTo(26);
    assertThat(parsed.notPassCase()).isEqualTo(4);
    assertThat(parsed.notPassCaseNow()).isEqualTo(4);
    assertThat(parsed.problemCase()).isEqualTo(1);
    assertThat(parsed.exceptionCount()).isEqualTo(1);
  }

  @Test
  void acceptsMixedSeparatorsAndNumericUnits() {
    String note =
        """
        ## 集成测试数据
        功能 = 装配
        执行人: 王五
        执行用例总数：共 12 条
        本次通过用例数＝10 条
        本次未通过用例数：2 条
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed =
        IntegrationTestNoteParser.parse(note);

    assertThat(parsed.functionName()).isEqualTo("装配");
    assertThat(parsed.executor()).isEqualTo("王五");
    assertThat(parsed.executeCase()).isEqualTo(12);
    assertThat(parsed.passCase()).isEqualTo(10);
    assertThat(parsed.notPassCaseNow()).isEqualTo(2);
  }

  @Test
  void ignoresUnitTestSections() {
    String note =
        """
        ## 单元测试数据
        ### 执行用例总数：53
        ### 本次通过用例数：51
        ### 本次未通过用例数：2
        """;

    IntegrationTestNoteParser.ParsedIntegrationNote parsed =
        IntegrationTestNoteParser.parse(note);

    assertThat(parsed).isEqualTo(IntegrationTestNoteParser.ParsedIntegrationNote.empty());
  }
}
