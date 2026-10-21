package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.domain.customerissue.CustomerIssueActivityTimeRules;
import java.time.LocalDate;
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

  @Test
  void test_beijingMondayMidnight_normalizedThenMappedIntoBusinessWeekAndDay() {
    // 来源规范化 → 事实存储值 → 业务时间映射 → 今日/本周窗口，走的是生产同一条链路，不在测试内预先换算。
    LocalDateTime stored =
        GitlabSourceTimestampNormalizer.normalizeSourceValue("2026-10-12T00:00:00+08:00");
    assertThat(stored).isEqualTo(LocalDateTime.of(2026, 10, 11, 16, 0, 0));

    LocalDate beijingWednesday = LocalDate.of(2026, 10, 14);
    assertThat(CustomerIssueActivityTimeRules.businessDateFromSourceUtc(stored))
        .isEqualTo(LocalDate.of(2026, 10, 12));
    assertThat(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(stored, beijingWednesday)).isTrue();
    assertThat(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(stored, beijingWednesday)).isFalse();
    assertThat(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(stored, LocalDate.of(2026, 10, 12)))
        .isTrue();

    // 前一瞬仍是上海周日 23:59:59，规范化后不得进入本自然周。
    LocalDateTime previousInstant =
        GitlabSourceTimestampNormalizer.normalizeSourceValue("2026-10-11T23:59:59+08:00");
    assertThat(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(previousInstant, beijingWednesday))
        .isFalse();
  }

  @Test
  void test_timestamptz_event_time_reachesFactOnBusinessClockAxis() {
    // 实测：resource_label_events.created_at 是 timestamptz，事实投影按运行主机时区落成 naive，
    // 本机存的是北京墙上值（2026-10-14T02:00Z → 10:00），因此解决贡献必须走业务时钟入口，不再二次偏移。
    LocalDateTime utcInstant =
        GitlabSourceTimestampNormalizer.normalizeSourceValue("2026-10-14T02:00:00+00:00");
    assertThat(utcInstant).isEqualTo(LocalDateTime.of(2026, 10, 14, 2, 0));

    LocalDate beijingWednesday = LocalDate.of(2026, 10, 14);
    LocalDateTime factStoredBusinessClock = LocalDateTime.of(2026, 10, 14, 10, 0);
    assertThat(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
        factStoredBusinessClock, beijingWednesday)).isTrue();
    assertThat(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
        factStoredBusinessClock, beijingWednesday)).isTrue();
  }
}
