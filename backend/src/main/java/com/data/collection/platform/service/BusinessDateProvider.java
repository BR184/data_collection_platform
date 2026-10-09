package com.data.collection.platform.service;

import java.time.LocalDate;

/**
 * 业务日的唯一来源。
 *
 * <p>业务日决定统计范围末端与快照业务日标记。把它收敛到单一可注入来源，既避免各处分别取"当日"导致口径
 * 漂移，也让需要可重复产出的链路（黄金基线回归、快照比对）能注入固定日期，从而与运行日历解耦。
 */
public interface BusinessDateProvider {

  /**
   * 返回当前业务日。
   *
   * @return 业务日，不得为 {@code null}
   */
  LocalDate businessDate();
}
