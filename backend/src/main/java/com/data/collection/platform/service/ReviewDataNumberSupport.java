package com.data.collection.platform.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class ReviewDataNumberSupport {
  private ReviewDataNumberSupport() {}

  static int safeInt(Integer value) {
    return value == null ? 0 : value;
  }

  static int nonNegativeInt(Integer value) {
    return Math.max(0, safeInt(value));
  }

  static double safeDouble(Double value) {
    return value == null ? 0D : value;
  }

  static double nonNegativeDouble(Double value) {
    return Math.max(0D, safeDouble(value));
  }

  static double floorToTwoDecimals(double value) {
    if (!Double.isFinite(value)) {
      return 0D;
    }
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.FLOOR).doubleValue();
  }
}
