package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StatisticSourceValueSupportTest {

  @Test
  void shouldParseLongSafely() {
    assertThat(StatisticSourceValueSupport.parseLong(" 42 ")).isEqualTo(42L);
    assertThat(StatisticSourceValueSupport.parseLong("")).isNull();
    assertThat(StatisticSourceValueSupport.parseLong("not-number")).isNull();
  }

  @Test
  void shouldTrimTextOrReturnFallback() {
    assertThat(StatisticSourceValueSupport.text(" value ", "fallback")).isEqualTo("value");
    assertThat(StatisticSourceValueSupport.text(" ", "fallback")).isEqualTo("fallback");
    assertThat(StatisticSourceValueSupport.text(null, "fallback")).isEqualTo("fallback");
  }

  @Test
  void shouldTrimTextOrReturnEmptyString() {
    assertThat(StatisticSourceValueSupport.text(" value ")).isEqualTo("value");
    assertThat(StatisticSourceValueSupport.text(" ")).isEmpty();
    assertThat(StatisticSourceValueSupport.text(null)).isEmpty();
  }

  @Test
  void shouldConvertTimestampToLocalDateTime() {
    LocalDateTime dateTime = LocalDateTime.of(2026, 4, 24, 14, 40);

    assertThat(StatisticSourceValueSupport.time(Timestamp.valueOf(dateTime))).isEqualTo(dateTime);
    assertThat(StatisticSourceValueSupport.time(null)).isNull();
  }

  @Test
  void shouldSplitCommaSeparatedValuesWithStableDeduplication() {
    assertThat(StatisticSourceValueSupport.split(" module-a, module-b, module-a, ,module-c "))
        .containsExactly("module-a", "module-b", "module-c");
    assertThat(StatisticSourceValueSupport.split(null)).isEmpty();
  }
}
