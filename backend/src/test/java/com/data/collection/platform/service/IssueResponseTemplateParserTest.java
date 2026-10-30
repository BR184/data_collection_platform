package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class IssueResponseTemplateParserTest {

  @Test
  void shouldReadOnlyTheLatestResponseTemplateInSourceOrder() {
    String latestTemplate =
        """
        # 问题调研情况说明
        ## 计划解决时间：2026年7月1日
        ## 计划合并的版本分支：release_2026R3
        """;
    String earlierTemplate =
        """
        # 问题调研情况说明
        ## 计划解决时间：2026.06.01
        ## 计划合并的版本分支：dev
        """;

    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(latestTemplate + "\n---\n" + earlierTemplate);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026年7月1日");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("release_2026R3");
  }

  @Test
  void shouldReadSectionContentPlacedOnTheFollowingLine() {
    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(
            """
            # 问题调研情况说明
            ## 计划解决时间：
            2026.06.30
            ## 计划合并的版本分支：
            优云智能_release_2026R2
            """);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 6, 30, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026.06.30");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("优云智能_release_2026R2");
  }

  @Test
  void shouldAcceptEveryConfiguredPlanDateSeparator() {
    for (String value :
        java.util.List.of(
            "2026.03.31", "2026,03,31", "2026，03，31", "2026、03、31", "2026/03/31", "2026·03·31", "2026`03`31", "2026年3月31日")) {
      IssueResponseTemplate template =
          IssueResponseTemplateParser.parse(
              """
              # 问题调研情况说明
              ## 计划解决时间：%s
              ## 计划合并的版本分支：CC2026R4
              """.formatted(value));

      assertThat(template.plannedResolutionAt())
          .as("计划解决时间 %s", value)
          .isEqualTo(LocalDateTime.of(2026, 3, 31, 0, 0));
      assertThat(template.plannedResolutionText()).isEqualTo(value);
    }
  }

  @Test
  void shouldKeepPlanMergeBranchSourceTextWhenPlanDateIsInvalid() {
    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(
            """
            # 问题调研情况说明
            ## 计划解决时间：预计 2026.03.31，最晚 2026.04.01
            ## 计划合并的版本分支：CC2026R4 & release/CC2026R5
            """);

    assertThat(template.plannedResolutionAt()).isNull();
    assertThat(template.plannedResolutionText()).isEmpty();
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("CC2026R4 & release/CC2026R5");
  }

  @Test
  void shouldNormalizePlanMergeBranchWhitespaceWithoutInterpretingBranchSyntax() {
    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(
            """
            # 问题调研情况说明
            ## 计划解决时间：2026年3月31日
            ## 计划合并的版本分支：
              crownCAD-Client:dev
              release_2026R4、优云智能_release_2026R4
            """);

    assertThat(template.plannedMergeVersionBranch())
        .isEqualTo("crownCAD-Client:dev release_2026R4、优云智能_release_2026R4");
  }

  @Test
  void shouldDetectPlanSolutionTimeOnlyWhenTheResponseTemplateProvidesIt() {
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        "# 问题调研情况说明\n## 计划解决时间：2026.03.31")).isTrue();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        """
        # 问题调研情况说明
        ## 计划解决时间：
        2026.03.31
        """)).isTrue();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        "# 问题调研情况说明\n## 问题原因：已定位")).isFalse();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        "# 问题调研情况说明\n## 计划解决时间：待定")).isFalse();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime("普通评论")).isFalse();
  }

  @Test
  void test_tab_prefixed_blank_line_before_canonical_heading_is_recognized() {
    String notes = "\t\n# 问题调研情况说明\n## 计划解决时间：2026.12.31";
    assertThat(IssueSlaRules.hasResponse(notes)).isTrue();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(notes)).isTrue();
    assertThat(IssueResponseTemplateParser.parse(notes).plannedResolutionAt())
        .isEqualTo(LocalDateTime.of(2026, 12, 31, 0, 0));
    assertThat(IssueSlaRules.hasResponse("## 问题调研情况说明\n## 计划解决时间：2026.03.31"))
        .as("撤回未批准的首行收紧后，原有关键字识别行为保持不变")
        .isTrue();
  }

  @Test
  void shouldFallBackToEarlierResponseTemplateWhenLatestPlanSolutionTimeIsMissing() {
    String withoutPlan = "# 问题调研情况说明\n## 计划解决时间：\n## 计划合并的版本分支：CC2026R3";
    String withPlan = "# 问题调研情况说明\n## 计划解决时间：2026.06.01";

    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        withoutPlan + "\n---\n" + withPlan)).isTrue();
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(
        withPlan + "\n---\n" + withoutPlan)).isTrue();
  }

  @Test
  void shouldStayAlignedWithTheStrictPlanDateRuleUsedByIllegalTemplates() {
    for (String value :
        java.util.List.of(
            "2026.03.31", "2026年3月31日", "2026年5", "20260615", "暂无", "2026年6月18号")) {
      String notes = "# 问题调研情况说明\n## 计划解决时间：" + value;
      assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(notes))
          .as("计划解决时间 [%s]", value)
          .isEqualTo(IssueResponsePlanFieldRules.parsePlannedResolutionAt(value) != null);
    }
  }
  @Test
  void shouldParsePlanDateWhenBoldHeadingIncludesColon() {
    String notes = "# 问题调研情况说明\n**计划解决时间：**\n2026年11月1日\n## 计划合并版本分支：CC2026R4";
    assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(notes)).isTrue();
    assertThat(IssueResponseTemplateParser.parse(notes).plannedResolutionAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
  }

  @Test
  void shouldReadDateAndBranchAcrossHeadingDecorationAndContentPositions() {
    for (String heading : java.util.List.of(
        "**计划解决时间：**", "**计划解决时间**：", "**计划解决时间:**",
        "## **计划解决时间：**", "`计划解决时间：`", "`计划解决时间`:", "## 计划解决时间：")) {
      for (String separator : java.util.List.of("", "\n")) {
        String notes = "# 问题调研情况说明\n" + heading + separator
            + "2026`11`01\n**计划合并版本分支：**\nrelease/CC2026R4";
        assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(notes)).as(notes).isTrue();
        IssueResponseTemplate template = IssueResponseTemplateParser.parse(notes);
        assertThat(template.plannedResolutionAt()).as(notes)
            .isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertThat(template.plannedResolutionText()).isEqualTo("2026`11`01");
        assertThat(template.plannedMergeVersionBranch()).isEqualTo("release/CC2026R4");
      }
    }
  }

  @Test
  void shouldKeepBlankAndInvalidDatesInvalidWhenHeadingIsDecorated() {
    for (String value : java.util.List.of("", "待定", "2026.02.30", "2026-11-01",
        "预计2026.11.01，最晚2026.11.02")) {
      String notes = "# 问题调研情况说明\n**计划解决时间：**\n" + value
          + "\n**计划合并版本分支：**CC2026R4";
      assertThat(IssueResponseTemplateParser.hasParseablePlanSolutionTime(notes)).as(value).isFalse();
      assertThat(IssueResponseTemplateParser.parse(notes).plannedResolutionAt()).isNull();
      assertThat(IssueResponseTemplateParser.parse(notes).plannedMergeVersionBranch()).isEqualTo("CC2026R4");
    }
  }
}
