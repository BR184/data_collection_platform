package com.data.collection.platform.service.statistics;

import java.util.Locale;

public final class StatisticMetricCalculator {
  private StatisticMetricCalculator() {}

  public static String count(long value) {
    return String.valueOf(value);
  }

  public static String rate(long numerator, long denominator) {
    return denominator <= 0 ? "/" : String.format(Locale.ROOT, "%.2f%%", numerator * 100.0 / denominator);
  }

  public static String percent(double value) {
    return String.format(Locale.ROOT, "%.2f%%", value);
  }

  public static double percentageOf(long numerator, long denominator) {
    return denominator <= 0 ? 0D : numerator * 100.0 / denominator;
  }

  /** 比率排序键：分母≤0 表示无数据，返回 null（与真实 0% 区分）；否则返回万分比整数。 */
  public static Long ratioSortValue(long numerator, long denominator) {
    return denominator <= 0 ? null : Math.round(numerator * 10000.0 / denominator);
  }

  public static long percentSortValue(double value) {
    return Math.round(value * 100);
  }
}
