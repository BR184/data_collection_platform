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

  public static long ratioSortValue(long numerator, long denominator) {
    return denominator <= 0 ? 0L : Math.round(numerator * 10000.0 / denominator);
  }

  public static long percentSortValue(double value) {
    return Math.round(value * 100);
  }
}
