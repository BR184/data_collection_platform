package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GitlabSourceTimestampNormalizerTest {

  @Test
  void test_offset_timestamp_text_normalizes_same_instant_to_utc_local_datetime() {
    LocalDateTime normalized =
        GitlabSourceTimestampNormalizer.normalizeSourceValue("2026-08-06T11:20:22.840104+08:00");

    assertThat(normalized).isEqualTo(LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000));
  }

  @Test
  void test_timestamp_text_without_offset_preserves_source_wall_clock() {
    LocalDateTime normalized =
        GitlabSourceTimestampNormalizer.normalizeSourceValue("2026-08-06 03:20:22.840104");

    assertThat(normalized).isEqualTo(LocalDateTime.of(2026, 8, 6, 3, 20, 22, 840_104_000));
  }

  @Test
  void test_local_date_time_value_passes_through_without_conversion() {
    LocalDateTime source = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    assertThat(GitlabSourceTimestampNormalizer.normalizeSourceValue(source)).isSameAs(source);
  }

  @Test
  void test_blank_or_unparseable_text_normalizes_to_null() {
    assertThat(GitlabSourceTimestampNormalizer.normalizeSourceValue("")).isNull();
    assertThat(GitlabSourceTimestampNormalizer.normalizeSourceValue("   ")).isNull();
    assertThat(GitlabSourceTimestampNormalizer.normalizeSourceValue("not-a-time")).isNull();
    assertThat(GitlabSourceTimestampNormalizer.normalizeSourceValue(null)).isNull();
  }
}
