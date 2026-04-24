package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class IssueFactValueSupportTest {
  @Test
  void textShouldTrimAndFallbackToEmptyString() {
    assertThat(IssueFactValueSupport.text(" value ")).isEqualTo("value");
    assertThat(IssueFactValueSupport.text(" ")).isEmpty();
    assertThat(IssueFactValueSupport.text(null)).isEmpty();
  }

  @Test
  void textShouldUseFallbackWhenValueIsBlank() {
    assertThat(IssueFactValueSupport.text(" value ", "fallback")).isEqualTo("value");
    assertThat(IssueFactValueSupport.text(" ", "fallback")).isEqualTo("fallback");
    assertThat(IssueFactValueSupport.text(null, "fallback")).isEqualTo("fallback");
  }

  @Test
  void timeShouldConvertTimestampToLocalDateTime() {
    LocalDateTime dateTime = LocalDateTime.of(2026, 4, 24, 15, 30);

    assertThat(IssueFactValueSupport.time(Timestamp.valueOf(dateTime))).isEqualTo(dateTime);
    assertThat(IssueFactValueSupport.time(null)).isNull();
  }

  @Test
  void splitShouldTrimDeduplicateAndIgnoreBlankValues() {
    assertThat(IssueFactValueSupport.split(" module-a, module-b, module-a, ,module-c "))
        .containsExactly("module-a", "module-b", "module-c");
    assertThat(IssueFactValueSupport.split(null)).isEmpty();
  }
}
