package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * S06 / T26：旧看板的下钻能力在契约迁移前后逐项一致。
 *
 * <p>历史前端在 {@code drilldown} 之外还要求数值大于 0；该条件现已由生产端唯一表达，
 * 本测试锁定这一等价规则，避免迁移后 0 值单元格变成可点击或非 0 单元格失去点击。
 */
class StatisticDrilldownSupportTest {

  @Test
  void zeroValueCellIsNotDrillableEvenWhenDeclared() {
    assertThat(StatisticDrilldownSupport.legacyCellDrilldown(true, 0L)).isFalse();
  }

  @Test
  void missingValueCellIsNotDrillable() {
    assertThat(StatisticDrilldownSupport.legacyCellDrilldown(true, null)).isFalse();
  }

  @Test
  void positiveValueCellKeepsDrilldown() {
    assertThat(StatisticDrilldownSupport.legacyCellDrilldown(true, 7L)).isTrue();
  }

  @Test
  void notDeclaredCellStaysClosedEvenWithPositiveValue() {
    assertThat(StatisticDrilldownSupport.legacyCellDrilldown(false, 7L)).isFalse();
  }
}
