package com.data.collection.platform.bi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BiSystemTestDelayCauseClassifierTest {
  private final BiSystemTestDelayCauseClassifier classifier =
      new BiSystemTestDelayCauseClassifier();

  @Test
  void mergesKnownCausesFromFactCompatibilityFieldAndLabelsInStableOrder() {
    var causes = classifier.classify(
        "资源卡点&算法问题",
        "技术卡点",
        "普通标签 延期原因：资源卡点 延期原因:计算效率");

    assertThat(causes).extracting("displayName")
        .containsExactly("资源卡点", "算法问题", "技术卡点", "计算效率");
  }

  @Test
  void preservesDelayedIssueWithoutKnownCauseAsExplicitUnknownMember() {
    var causes = classifier.classify("历史原因", null, null);

    assertThat(causes).singleElement().satisfies(cause -> {
      assertThat(cause.displayName()).isEqualTo("未归类延期原因");
      assertThat(cause.identified()).isFalse();
    });
  }
}
