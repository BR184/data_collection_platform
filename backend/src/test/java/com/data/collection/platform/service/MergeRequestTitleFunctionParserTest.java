package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MergeRequestTitleFunctionParserTest {

  @ParameterizedTest
  @CsvSource({
      "'[装配] 修复连接问题', '装配'",
      "'前缀 [工程图] 修复显示问题', '工程图'",
      "'[  草图  ][平台] 调整交互', '草图'",
      "'【MBD】兼容中文括号', 'MBD'"
  })
  void validTitleReturnsFirstNonBlankBracketMember(String title, String expected) {
    assertThat(MergeRequestTitleFunctionParser.parse(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "普通标题", "[] 空功能", "【   】空功能"})
  void missingFunctionReturnsNull(String title) {
    assertThat(MergeRequestTitleFunctionParser.parse(title)).isNull();
  }
}
