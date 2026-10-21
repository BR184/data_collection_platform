package com.data.collection.platform.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 默认业务日来源：按平台业务时区取“当日”。
 *
 * <p>业务日是北京时间日历日，不能跟随主机默认时区：同一瞬间在 UTC+8 与 UTC-10 主机上会得出不同日期，
 * 进而让今日/本周窗口整体错位一天。时刻取自可注入的 {@link Clock}（黄金基线用固定时钟覆盖），
 * 时区在本类显式固定，不依赖 {@code ZoneId.systemDefault()}。
 */
@Component
public class SystemBusinessDateProvider implements BusinessDateProvider {

  /** 平台业务日历时区，与统计窗口、趋势分桶同一条边界。 */
  static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final Clock clock;

  public SystemBusinessDateProvider() {
    this(Clock.system(BUSINESS_ZONE));
  }

  /**
   * 使用平台统一时刻来源构造业务日提供者。
   *
   * @param clock 时刻来源，只决定“现在”这一瞬间；日历日一律按 {@link #BUSINESS_ZONE} 解释
   */
  @Autowired
  public SystemBusinessDateProvider(Clock clock) {
    this.clock = clock;
  }

  @Override
  public LocalDate businessDate() {
    return LocalDate.now(clock.withZone(BUSINESS_ZONE));
  }
}
