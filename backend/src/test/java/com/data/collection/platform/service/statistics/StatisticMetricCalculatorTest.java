package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 统计看板比率单元格口径单测：锁定显示值与排序键的一致性，防止“无数据”再次被坍缩成 0。
 */
class StatisticMetricCalculatorTest {

  @Test
  void test_zero_denominator_rate_displays_no_data_marker() {
    assertThat(StatisticMetricCalculator.rate(0, 0)).isEqualTo("/");
    assertThat(StatisticMetricCalculator.rate(3, 0)).isEqualTo("/");
  }

  @Test
  void test_zero_denominator_ratio_sort_value_is_null_not_zero() {
    // 分母为 0 表示无数据：排序键为 null，前端据此把该行恒置底，不与真实 0% 交错。
    assertThat(StatisticMetricCalculator.ratioSortValue(0, 0)).isNull();
    assertThat(StatisticMetricCalculator.ratioSortValue(3, 0)).isNull();
    assertThat(StatisticMetricCalculator.ratioSortValue(3, -1)).isNull();
  }

  @Test
  void test_valid_denominator_ratio_sort_value_is_basis_points() {
    assertThat(StatisticMetricCalculator.ratioSortValue(0, 5)).isZero();
    assertThat(StatisticMetricCalculator.ratioSortValue(1, 3)).isEqualTo(3333L);
    assertThat(StatisticMetricCalculator.ratioSortValue(1, 2)).isEqualTo(5000L);
    assertThat(StatisticMetricCalculator.ratioSortValue(1, 300)).isEqualTo(33L);
  }
}
