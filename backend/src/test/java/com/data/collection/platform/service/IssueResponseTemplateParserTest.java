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
        ## 计划合并的版本分支：release/2026R3
        """;
    String earlierTemplate =
        """
        # 问题调研情况说明
        ## 计划解决时间：2026.06.01
        ## 计划合并的版本分支：release/2026R2
        """;

    IssueResponseTemplate template =
        IssueResponseTemplateParser.parse(latestTemplate + "\n---\n" + earlierTemplate);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026年7月1日");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("release/2026R3");
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
            阅芯电子_release_2026R2
            """);

    assertThat(template.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 6, 30, 0, 0));
    assertThat(template.plannedResolutionText()).isEqualTo("2026.06.30");
    assertThat(template.plannedMergeVersionBranch()).isEqualTo("阅芯电子_release_2026R2");
  }
}
