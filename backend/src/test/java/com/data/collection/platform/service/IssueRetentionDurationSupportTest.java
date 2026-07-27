package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IssueRetentionDurationSupportTest {

  @Test
  void shouldCalculateWholeHoursAgainstTheProvidedAsOfTime() {
    assertThat(
            IssueRetentionDurationSupport.calculate(
                LocalDateTime.of(2026, 7, 20, 10, 30),
                LocalDateTime.of(2026, 7, 22, 11, 29),
                "opened",
                null,
                "历史遗留"))
        .isEqualTo(48L);
  }

  @ParameterizedTest
  @ValueSource(strings = {"已修复/完成", "申请延期", "数据异常", "需求如此", "设计如此", "未复现"})
  void shouldReturnZeroForEveryCustomerIssueClosureStatus(String closureStatus) {
    assertThat(calculate("opened", null, "历史遗留、" + closureStatus)).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"closed", "CLOSED"})
  void shouldReturnZeroWhenTheGitLabIssueStateIsClosed(String issueState) {
    assertThat(calculate(issueState, null, "未修复")).isZero();
  }

  @Test
  void shouldReturnZeroWhenTheIssueHasAClosedTime() {
    assertThat(
            calculate(
                "opened", LocalDateTime.of(2026, 7, 21, 9, 0), "未修复"))
        .isZero();
  }

  @Test
  void shouldReturnZeroForClosedIssueEvenWhenItsSubmissionTimeIsMissing() {
    assertThat(
            IssueRetentionDurationSupport.calculate(
                null, null, "closed", null, "未修复"))
        .isZero();
  }

  @Test
  void shouldMatchClosureStatusesAsMembersInsteadOfSubstrings() {
    assertThat(calculate("opened", null, "未修复、申请延期处理中")).isEqualTo(49L);
  }

  @Test
  void shouldReturnNoDurationWithoutSubmissionTimeAndNeverReturnNegativeHours() {
    LocalDateTime asOf = LocalDateTime.of(2026, 7, 22, 10, 0);

    assertThat(IssueRetentionDurationSupport.calculate(null, asOf, "opened", null, "未修复"))
        .isNull();
    assertThat(
            IssueRetentionDurationSupport.calculate(
                asOf.plusHours(1), asOf, "opened", null, "未修复"))
        .isZero();
  }

  private Long calculate(String issueState, LocalDateTime closedAt, String bugStatus) {
    return IssueRetentionDurationSupport.calculate(
        LocalDateTime.of(2026, 7, 20, 8, 30),
        LocalDateTime.of(2026, 7, 22, 10, 0),
        issueState,
        closedAt,
        bugStatus);
  }
}
