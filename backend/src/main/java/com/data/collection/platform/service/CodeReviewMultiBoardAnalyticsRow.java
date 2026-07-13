package com.data.collection.platform.service;

import java.math.BigDecimal;

public record CodeReviewMultiBoardAnalyticsRow(String label, BigDecimal value) {
  public CodeReviewMultiBoardAnalyticsRow {
    label = label == null ? "" : label.trim();
    value = value == null ? BigDecimal.ZERO : value;
  }
}
