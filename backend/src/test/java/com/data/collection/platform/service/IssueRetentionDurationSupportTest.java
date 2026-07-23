package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class IssueRetentionDurationSupportTest {

  @Test
  void shouldCalculateWholeHoursAgainstTheProvidedAsOfTime() {
    assertThat(
            IssueRetentionDurationSupport.hoursBetween(
                LocalDateTime.of(2026, 7, 20, 10, 30),
                LocalDateTime.of(2026, 7, 22, 11, 29)))
        .isEqualTo(48L);
  }

  @Test
  void shouldReturnNoDurationWithoutSubmissionTimeAndNeverReturnNegativeHours() {
    LocalDateTime asOf = LocalDateTime.of(2026, 7, 22, 10, 0);

    assertThat(IssueRetentionDurationSupport.hoursBetween(null, asOf)).isNull();
    assertThat(IssueRetentionDurationSupport.hoursBetween(asOf.plusHours(1), asOf)).isZero();
  }
}
