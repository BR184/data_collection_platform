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
        ## 计划合并的版本分支：CC2026R3
        """;
    String earlierTemplate =
        """
        # 问题调研情况说明
        ## 计划解决时间：2026.06.01
        ## 计划合并的版本分支：CC2026R2
        """;

    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(latestTemplate + "\n---\n" + earlierTemplate);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026年7月1日");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("CC2026R3");
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
            CC2026R2
            """);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 6, 30, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026.06.30");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("CC2026R2");
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
  void shouldClearPlanFieldsWhenTheirContentsDoNotFollowTheFormat() {
    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(
            """
            # 问题调研情况说明
            ## 计划解决时间：预计 2026.03.31，最晚 2026.04.01
            ## 计划合并的版本分支：CC2026R4 & release/CC2026R5
            """);

    assertThat(template.plannedResolutionAt()).isNull();
    assertThat(template.plannedResolutionText()).isEmpty();
    assertThat(template.plannedMergeVersionBranch()).isEmpty();
  }

  @Test
  void shouldNormalizeMultiplePlanMergeVersionsIntoStableMembers() {
    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(
            """
            # 问题调研情况说明
            ## 计划解决时间：2026年3月31日
            ## 计划合并的版本分支： CC2026R4 & CC2026R5 & CC2026R4
            """);

    assertThat(template.plannedMergeVersionBranch()).isEqualTo("CC2026R4 & CC2026R5");
  }
}
