import { describe, expect, it } from 'vitest';
import { buildSystemTestRepairChartOption } from './quality-board';
import type { StatisticBoardResponse } from '../types/api';

function boardWithRate(displayValue: string, numericValue = 14569): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'system-test-defect-summary',
      title: '系统测试缺陷汇总',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '模块',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
      emptyText: '',
    },
    appliedFilters: {},
    appliedFilterGroup: null,
    rows: [
      {
        rowKey: 'sketch',
        rowLabel: '草图',
        cells: [
          { columnKey: 'module_total', numericValue: 20000, displayValue: '20000', drilldown: true, detailParams: {} },
          { columnKey: 'fix_rate', numericValue, displayValue, drilldown: false, detailParams: {} },
        ],
      },
    ],
    meta: {
      generatedAt: '2026-06-02T10:00:00',
      queryDurationMs: 1,
      rowCount: 1,
      columnCount: 2,
      drilldownColumnCount: 1,
    },
  };
}

function firstSeriesData(option: unknown) {
  const record = option as { series?: Array<{ data?: unknown[] }> };
  return record.series?.[0]?.data ?? [];
}

describe('quality board chart helpers', () => {
  it('uses ratio display value for system test repair chart instead of raw numerator', () => {
    const option = buildSystemTestRepairChartOption(boardWithRate('80.00%'));

    expect(firstSeriesData(option)).toEqual([80]);
  });

  it('falls back to numeric value when repair-rate display value is not a percent', () => {
    const option = buildSystemTestRepairChartOption(boardWithRate('80.00', 76.25));

    expect(firstSeriesData(option)).toEqual([76.25]);
  });
});
