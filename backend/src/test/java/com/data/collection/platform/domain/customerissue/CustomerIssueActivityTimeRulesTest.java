package com.data.collection.platform.domain.customerissue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 客户问题今日/本周窗口的纯规则测试（CI-18/19/27/28）。
 *
 * <p>入参按事实层实测的两条存储轴给出：{@code created_at_source} 是 UTC naive（走 {@code *FromSourceUtc}），
 * {@code fixed_label_time} 是业务时区墙上值（走 {@code *FromBusinessClock}）。断言一律用北京时间业务日历，
 * 不在测试内把 UTC 值预先换算后再喂给规则，否则生产映射缺失也不会失败。
 */
class CustomerIssueActivityTimeRulesTest {
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);

  @Test
  void weekStart_isMondayOfTheFixedBusinessDateWeek() {
    // 2026-10-12 是周一，2026-10-14 是周三。
    assertEquals(LocalDate.of(2026, 10, 12), CustomerIssueActivityTimeRules.weekStart(WEDNESDAY));
    assertEquals(LocalDate.of(2026, 10, 12), CustomerIssueActivityTimeRules.weekStart(LocalDate.of(2026, 10, 12)));
    assertEquals(LocalDate.of(2026, 10, 12), CustomerIssueActivityTimeRules.weekStart(LocalDate.of(2026, 10, 18)));
    assertThrows(IllegalArgumentException.class, () -> CustomerIssueActivityTimeRules.weekStart(null));
  }

  @Test
  void weekStart_crossYearAnchorsOnMondayNotCalendarYear() {
    // 2027-01-01 是周五，本自然周起点仍在上一年的周一；创建时间取该周一北京零点对应的 UTC 前一日 16:00。
    assertEquals(LocalDate.of(2026, 12, 28), CustomerIssueActivityTimeRules.weekStart(LocalDate.of(2027, 1, 1)));
    assertEquals(
        LocalDate.of(2026, 12, 28), CustomerIssueActivityTimeRules.weekStart(LocalDate.of(2027, 1, 3)));
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 12, 27, 16, 0), LocalDate.of(2027, 1, 1)));
  }

  @Test
  void businessDateFromSourceUtc_mapsUtcNaiveToBeijingCalendarDay() {
    assertEquals(
        LocalDate.of(2026, 10, 12),
        CustomerIssueActivityTimeRules.businessDateFromSourceUtc(LocalDateTime.of(2026, 10, 11, 16, 0)));
    assertEquals(
        LocalDate.of(2026, 10, 11),
        CustomerIssueActivityTimeRules.businessDateFromSourceUtc(LocalDateTime.of(2026, 10, 11, 15, 59, 59)));
    assertEquals(
        LocalDate.of(2026, 10, 13),
        CustomerIssueActivityTimeRules.businessDateFromSourceUtc(LocalDateTime.of(2026, 10, 12, 16, 0)));
    assertNull(CustomerIssueActivityTimeRules.businessDateFromSourceUtc(null));
  }

  @Test
  void weekWindowFromSourceUtc_acceptsUtcSunday1600AsBeijingMondayStart() {
    // 上海周一 00:00 存为 UTC 周日 16:00：必须落在本周内（无映射的旧实现在此失败）。
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 11, 16, 0), WEDNESDAY));
    // 此前一瞬仍是上海周日 23:59:59，属于上一周。
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 11, 15, 59, 59), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 11, 15, 59, 59, 999_999_999), WEDNESDAY));
  }

  @Test
  void weekWindowFromSourceUtc_isHalfOpenFromBeijingMondayToBusinessDayEnd() {
    // 本周末点＝固定业务日（周三）的北京结束时刻，即 UTC 周三 16:00。
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 14, 15, 59, 59), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 14, 16, 0), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(null, WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(LocalDateTime.now(), null));
  }

  @Test
  void dayWindowFromSourceUtc_usesBeijingNaturalDay() {
    // 北京周三 = UTC 周二 16:00 → UTC 周三 16:00 半开区间。
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(
        LocalDateTime.of(2026, 10, 13, 16, 0), WEDNESDAY));
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(
        LocalDateTime.of(2026, 10, 14, 15, 59, 59, 999_999_999), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(
        LocalDateTime.of(2026, 10, 13, 15, 59, 59), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(
        LocalDateTime.of(2026, 10, 14, 16, 0), WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(null, WEDNESDAY));
  }

  @Test
  void businessClockWindows_keepBeijingWallValuesOnTheSameDayWithoutSecondShift() {
    // 事实层 fixed_label_time 已是北京墙上值（本机实测）：10-14 10:00 属周三，不得再偏移成 18:00 之外的日子。
    LocalDateTime beijingWednesdayMorning = LocalDateTime.of(2026, 10, 14, 10, 0);
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
        beijingWednesdayMorning, WEDNESDAY));
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
        beijingWednesdayMorning, WEDNESDAY));
    assertEquals(
        WEDNESDAY, CustomerIssueActivityTimeRules.businessDateFromBusinessClock(beijingWednesdayMorning));

    // 北京周四零点越出周三业务日，但仍在同一自然周内（业务日推进到周四时继续入周）。
    LocalDateTime beijingThursdayStart = LocalDateTime.of(2026, 10, 15, 0, 0);
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(
        beijingThursdayStart, WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
        beijingThursdayStart, WEDNESDAY));
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(
        beijingThursdayStart, LocalDate.of(2026, 10, 15)));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromBusinessClock(null, WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromBusinessClock(beijingThursdayStart, null));
  }

  @Test
  void twoAxes_areNotInterchangeable() {
    // 北京周三零点在创建轴上存为 UTC 10-12 16:00：走创建轴入口命中周三，误走加标轴入口会落到周二。
    LocalDateTime createdAxis = LocalDateTime.of(2026, 10, 13, 16, 0);
    assertEquals(WEDNESDAY, CustomerIssueActivityTimeRules.businessDateFromSourceUtc(createdAxis));
    assertEquals(
        LocalDate.of(2026, 10, 13), CustomerIssueActivityTimeRules.businessDateFromBusinessClock(createdAxis));

    // 同一瞬间在加标轴上已是北京周三 20:00：误走创建轴入口会再推后 8 小时跨到周四。
    LocalDateTime fixedAxis = LocalDateTime.of(2026, 10, 14, 20, 0);
    assertEquals(WEDNESDAY, CustomerIssueActivityTimeRules.businessDateFromBusinessClock(fixedAxis));
    assertEquals(
        LocalDate.of(2026, 10, 15), CustomerIssueActivityTimeRules.businessDateFromSourceUtc(fixedAxis));
  }

  @Test
  void weekAndDayWindows_shareOneBusinessDateAndDoNotRollWithWallClock() {
    // 同一业务日下，今日窗口是本自然周窗口的子集；结果只随业务日推进，不随调用时刻变化。
    LocalDateTime beijingMondayStart = LocalDateTime.of(2026, 10, 11, 16, 0);
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(beijingMondayStart, WEDNESDAY));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(beijingMondayStart, WEDNESDAY));
    LocalDateTime insideToday = LocalDateTime.of(2026, 10, 14, 4, 0);
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessDayFromSourceUtc(insideToday, WEDNESDAY));
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(insideToday, WEDNESDAY));
    // 业务日推进到周五，本周窗口终点随之推进；北京周六起才退出。
    assertTrue(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        beijingMondayStart, LocalDate.of(2026, 10, 15)));
    assertFalse(CustomerIssueActivityTimeRules.withinBusinessWeekFromSourceUtc(
        LocalDateTime.of(2026, 10, 15, 16, 0), LocalDate.of(2026, 10, 15)));
  }

  @Test
  void exactCompletionMember_matchesWholeNameOnly() {
    assertTrue(CustomerIssueActivityTimeRules.hasExactCompletionMember(List.of("已修复/完成")));
    assertTrue(CustomerIssueActivityTimeRules.hasExactCompletionMember(List.of("数据异常", "已修复/完成")));
    // 老平台“已修复”“待合并”等整体判据成员不构成精确完成贡献，沿今日解决口径不放宽。
    assertFalse(CustomerIssueActivityTimeRules.hasExactCompletionMember(List.of("已修复")));
    assertFalse(CustomerIssueActivityTimeRules.hasExactCompletionMember(List.of("已修复/完成/其它")));
    assertFalse(CustomerIssueActivityTimeRules.hasExactCompletionMember(List.of("未复现")));
    assertFalse(CustomerIssueActivityTimeRules.hasExactCompletionMember(null));
  }
}
