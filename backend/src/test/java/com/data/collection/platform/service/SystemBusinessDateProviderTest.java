package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 业务日来源的时区隔离测试（R2）。
 *
 * <p>业务日必须是北京时间日历日，与主机默认时区无关：同一瞬间在 UTC+8 与 UTC-10 主机上必须得到同一天，
 * 否则今日/本周窗口会整体错位一天。
 */
class SystemBusinessDateProviderTest {
  private static final TimeZone ORIGINAL_DEFAULT = TimeZone.getDefault();

  /** 2026-10-14T20:00Z：北京已是 10-15 周四 04:00，檀香山仍是 10-14 周三 10:00。 */
  private static final Instant BEIJING_NEXT_DAY_INSTANT = Instant.parse("2026-10-14T20:00:00Z");

  @AfterEach
  void restoreDefaultTimeZone() {
    TimeZone.setDefault(ORIGINAL_DEFAULT);
  }

  @Test
  void businessDate_followsBeijingCalendarNotHostDefaultZone() {
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
    SystemBusinessDateProvider provider =
        new SystemBusinessDateProvider(Clock.fixed(BEIJING_NEXT_DAY_INSTANT, ZoneOffset.UTC));

    assertThat(provider.businessDate()).isEqualTo(LocalDate.of(2026, 10, 15));
  }

  @Test
  void businessDate_isStableAcrossHostZoneSwitchAtSameInstant() {
    Clock clock = Clock.fixed(BEIJING_NEXT_DAY_INSTANT, ZoneOffset.UTC);
    LocalDate[] byZone = new LocalDate[3];
    String[] zones = {"Etc/UTC", "Asia/Shanghai", "Pacific/Honolulu"};
    for (int i = 0; i < zones.length; i++) {
      TimeZone.setDefault(TimeZone.getTimeZone(zones[i]));
      byZone[i] = new SystemBusinessDateProvider(clock).businessDate();
    }

    assertThat(byZone).containsOnly(LocalDate.of(2026, 10, 15));
  }

  @Test
  void businessDate_keepsOneIdentityAcrossWeekBoundary() {
    // 北京周一零点前后各一瞬：业务日必须按北京日历翻页，不受主机时区影响继续沿用上周。
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));

    assertThat(new SystemBusinessDateProvider(
        Clock.fixed(Instant.parse("2026-10-11T15:59:59Z"), ZoneOffset.UTC)).businessDate())
            .isEqualTo(LocalDate.of(2026, 10, 11));
    assertThat(new SystemBusinessDateProvider(
        Clock.fixed(Instant.parse("2026-10-11T16:00:00Z"), ZoneOffset.UTC)).businessDate())
            .isEqualTo(LocalDate.of(2026, 10, 12));
  }
}
