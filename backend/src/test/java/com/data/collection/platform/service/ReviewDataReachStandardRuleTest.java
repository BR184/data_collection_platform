package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReviewDataReachStandardRuleTest {
  @Test
  void judgesDesignReviewDensityAgainstDesignSpecificBand() {
    assertThat(ReviewDataReachStandardRule.reached("设计说明书评审", 0.25D)).isFalse();
    assertThat(ReviewDataReachStandardRule.reached("设计说明书评审", 0.3D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached("设计说明书评审", 0.5D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached("设计说明书评审", 0.8D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached("设计说明书评审", 0.85D)).isFalse();
  }

  @Test
  void keepsRequirementReviewDensityTargetBandUnchanged() {
    assertThat(ReviewDataReachStandardRule.reached("需求说明书评审", 0.25D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached("需求说明书评审", 0.65D)).isFalse();
  }

  @Test
  void judgesUnknownOrBlankReviewTypesByDefaultBand() {
    assertThat(ReviewDataReachStandardRule.reached("代码评审", 0.25D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached(null, 0.25D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached(" ", 0.25D)).isTrue();
    assertThat(ReviewDataReachStandardRule.reached(null, 0D)).isFalse();
  }

  @Test
  void toleratesSurroundingWhitespaceInReviewType() {
    assertThat(ReviewDataReachStandardRule.reached(" 设计说明书评审 ", 0.25D)).isFalse();
  }

  @Test
  void buildsOrderExpressionWithBothBandsAndDesignTypeGuard() {
    String expression =
        ReviewDataReachStandardRule.orderExpression("fr.review_type", "fr.problem_density");

    assertThat(expression)
        .contains("btrim(coalesce(fr.review_type, '')) = '设计说明书评审'")
        .contains("fr.problem_density >= 0.3")
        .contains("fr.problem_density <= 0.8")
        .contains("fr.problem_density >= 0.2")
        .contains("fr.problem_density <= 0.6");
  }
}
