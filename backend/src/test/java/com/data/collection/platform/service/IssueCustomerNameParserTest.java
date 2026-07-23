package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IssueCustomerNameParserTest {

  @Test
  void descriptionCustomerNamesShouldTakePrecedenceAndKeepEveryCanonicalMember() {
    assertThat(
            IssueCustomerNameParser.parse(
                "**客户名称**：高晶电器/新世纪\n\n问题描述：示例",
                "【圆柱齿轮】示例——高晶电器",
                Map.of("新世纪", "郑州新世纪")))
        .containsExactly("高晶电器", "郑州新世纪");
  }

  @Test
  void titleSuffixShouldBeUsedOnlyWhenDescriptionHasNoCustomerName() {
    assertThat(
            IssueCustomerNameParser.parse(
                "问题描述：没有客户字段",
                "【约束】增加中点约束——新世纪",
                Map.of("新世纪", "郑州新世纪")))
        .containsExactly("郑州新世纪");
  }

  @Test
  void dateSuffixMustNotBeInventedAsCustomerName() {
    assertThat(
            IssueCustomerNameParser.parse(
                "问题描述：没有客户字段",
                "【数据兼容】文档汇总——2026-06-16",
                Map.of("新世纪", "郑州新世纪")))
        .isEmpty();
  }
}
