package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustomerIssueIllegalRuleCompatibilityTest {

  @Test
  void shouldKeepMissingSeverityCompatibleWithOldPlatformLabels() {
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("类别：建议", "模块：工程图"),
        List.of("工程图"),
        "",
        false))
        .containsExactly("未设定严重程度");
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("需求", "模块：工程图"),
        List.of("工程图"),
        "",
        false))
        .containsExactly("未设定严重程度");
  }

  @Test
  void shouldAcceptResearchPlanDateUsingConfiguredSlashSeparator() {
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图"),
        List.of("工程图"),
        researchTemplate("2026/06/16"),
        false))
        .isEmpty();
  }

  @Test
  void shouldAcceptResearchPlanDateAcceptedByOldPlatform() {
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图"),
        List.of("工程图"),
        researchTemplate("2026.06.16"),
        false))
        .isEmpty();
  }

  @Test
  void shouldRejectResearchTemplateWithInvalidPlanMergeVersionBranch() {
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图"),
        List.of("工程图"),
        researchTemplate("2026.06.16", "release/CC2026R4"),
        false))
        .containsExactly("未按照要求填写缺陷调研模板");
  }

  @Test
  void shouldKeepNonUniqueReasonCompatibleWithOldPlatformBug() {
    String multipleMajorReasons =
        """
        ### 1、修复状态
        [x] 编码逻辑：业务逻辑错误
        [x] 新增需求问题
        ### 3、请描述具体原因：
        已处理
        """;

    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图", "状态：已修复/完成"),
        List.of("工程图"),
        multipleMajorReasons,
        true))
        .isEmpty();
  }

  @Test
  void shouldTreatUnparseableFixTemplateAsTemplateNotFollowed() {
    assertThat(IssueFactNormalizationRules.customerIssueIllegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图", "状态：已修复/完成"),
        List.of("工程图"),
        "### 1、修复状态\n尚未填写缺陷原因",
        true))
        .containsExactly("未按照模板回复");
  }

  @Test
  void shouldLeaveSystemTestCompatibilityPathUnchanged() {
    String multipleMajorReasons =
        """
        ### 1、修复状态
        [x] 编码逻辑：业务逻辑错误
        [x] 新增需求问题
        ### 3、请描述具体原因：
        已处理
        """;

    assertThat(IssueFactNormalizationRules.illegalReasons(
        List.of("严重程度：二级缺陷", "模块：工程图", "状态：已修复/完成"),
        false,
        List.of("工程图"),
        multipleMajorReasons,
        true))
        .isEmpty();
  }

  private static String researchTemplate(String planDate) {
    return researchTemplate(planDate, "CC2026R4");
  }

  private static String researchTemplate(String planDate, String planMergeVersionBranch) {
    return """
        # 问题调研情况说明
        ## 问题类型：
        * [x] 缺陷
        * [ ] 需求
        ## 问题原因：已定位
        ## 修改方案：已修改
        ## 计划解决时间：%s
        ## 计划合并的版本分支：%s
        """.formatted(planDate, planMergeVersionBranch);
  }
}
