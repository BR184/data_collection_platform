package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhaseCalendarContractTest {
  private static final long PROJECT_ID = 9L;
  private static final String PHASE = "CC2026R4第一轮系统测试";

  @Test
  void factProjectionShouldKeepNewestDatedEntryForSamePhase() {
    var older = entry(LocalDateTime.of(2026, 4, 1, 0, 0), null, true);
    var newer = entry(LocalDateTime.of(2026, 5, 1, 0, 0), null, true);

    var calendar =
        IssuePhaseCalendarLoader.selectForFactProjection(List.of(older, newer));

    assertThat(calendar.values()).containsExactly(newer);
  }

  @Test
  void factProjectionShouldPreserveExistingNullStartPrecedence() {
    var dated = entry(LocalDateTime.of(2026, 5, 1, 0, 0), null, true);
    var withoutStart = entry(null, null, true);

    var calendar =
        IssuePhaseCalendarLoader.selectForFactProjection(List.of(dated, withoutStart));

    assertThat(calendar.values()).containsExactly(withoutStart);
  }

  @Test
  void integrationTestShouldKeepFirstSeenEntryForSamePhase() {
    var first = entry(LocalDateTime.of(2026, 4, 1, 0, 0), null, true);
    var later = entry(LocalDateTime.of(2026, 5, 1, 0, 0), null, true);

    var calendar =
        IssuePhaseCalendarLoader.selectForIntegrationTest(List.of(first, later));

    assertThat(calendar.values()).containsExactly(first);
  }

  @Test
  void entryShouldMatchInclusiveRangeAndRejectIncompleteOrDisabledRanges() {
    LocalDateTime start = LocalDateTime.of(2026, 4, 1, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 4, 30, 23, 59);
    var bounded = entry(start, end, true);

    assertThat(bounded.matches(start)).isTrue();
    assertThat(bounded.matches(end)).isTrue();
    assertThat(bounded.matches(start.minusNanos(1))).isFalse();
    assertThat(bounded.matches(end.plusNanos(1))).isFalse();
    assertThat(entry(start, null, true).matches(end.plusYears(1))).isTrue();
    assertThat(entry(null, end, true).matches(end)).isFalse();
    assertThat(entry(start, end, false).matches(start)).isFalse();
    assertThat(bounded.matches(null)).isFalse();
  }

  @Test
  void phaseKeyShouldNormalizeCaseAndWhitespaceOnBothPolicies() {
    var value =
        new IssuePhaseCalendarLoader.PhaseCalendarEntry(
            PROJECT_ID, "  cc2026R4第一轮系统测试  ", null, null, true);
    var expectedKey =
        new IssuePhaseCalendarLoader.PhaseCalendarKey(
            PROJECT_ID, "cc2026r4第一轮系统测试");

    assertThat(IssuePhaseCalendarLoader.selectForFactProjection(List.of(value)))
        .containsKey(expectedKey);
    assertThat(IssuePhaseCalendarLoader.selectForIntegrationTest(List.of(value)))
        .containsKey(expectedKey);
  }

  private IssuePhaseCalendarLoader.PhaseCalendarEntry entry(
      LocalDateTime start, LocalDateTime end, boolean enabled) {
    return new IssuePhaseCalendarLoader.PhaseCalendarEntry(
        PROJECT_ID, PHASE, start, end, enabled);
  }
}
