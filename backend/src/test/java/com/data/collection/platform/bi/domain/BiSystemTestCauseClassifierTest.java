package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BiSystemTestCauseClassifierTest {
  private final BiSystemTestCauseClassifier classifier = new BiSystemTestCauseClassifier();

  @Test
  void classifiesAllMatchedCauseSubcategoriesAndTheirCategories() {
    var causes = classifier.classify(
        "功能设计遗漏；编码逻辑：业务逻辑错误",
        "环境配置问题");

    assertThat(causes).extracting("categoryName", "subcategoryName")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("设计问题", "功能设计遗漏"),
            org.assertj.core.groups.Tuple.tuple("编码规范", "编码逻辑：业务逻辑错误"),
            org.assertj.core.groups.Tuple.tuple("打包问题", "环境配置问题"));
  }

  @Test
  void preservesUnmatchedCauseAsExplicitUnclassifiedMember() {
    var causes = classifier.classify("历史自由文本", null);

    assertThat(causes).singleElement().satisfies(cause -> {
      assertThat(cause.categoryName()).isEqualTo("未归类");
      assertThat(cause.subcategoryName()).isEqualTo("未归类");
    });
  }
}
