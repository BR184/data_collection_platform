import { describe, expect, it } from 'vitest';
import type { StatisticColumnLeaf, StatisticRowData } from '../types/api';
import {
  clearSortState,
  nextColumnSortState,
  ROW_LABEL_SORT_KEY,
  sortDirectionForColumn,
  sortRowsFromSource,
} from './statistic-board-sorting';

function buildRow(
  rowKey: string,
  rowLabel: string,
  values: Record<string, { numericValue: number; displayValue: string }>,
): StatisticRowData {
  return {
    rowKey,
    rowLabel,
    cells: Object.entries(values).map(([columnKey, value]) => ({
      columnKey,
      numericValue: value.numericValue,
      displayValue: value.displayValue,
      drilldown: true,
      detailParams: {},
    })),
  };
}

const columns: StatisticColumnLeaf[] = [
  { key: 'recentSync', label: '24小时内同步', drilldown: true, metricType: 'count' },
  { key: 'updatedAt', label: '有源更新时间', drilldown: true, metricType: 'time' },
];

const rows: StatisticRowData[] = [
  buildRow('zeta', 'zeta_table', {
    recentSync: { numericValue: 3, displayValue: '3' },
    updatedAt: { numericValue: 0, displayValue: '2026-03-20T10:00:00' },
  }),
  buildRow('alpha', 'alpha_table', {
    recentSync: { numericValue: 1, displayValue: '1' },
    updatedAt: { numericValue: 0, displayValue: '2026-03-22T10:00:00' },
  }),
  buildRow('beta', 'beta_table', {
    recentSync: { numericValue: 3, displayValue: '3' },
    updatedAt: { numericValue: 0, displayValue: '2026-03-21T10:00:00' },
  }),
];

describe('statistic board sorting', () => {
  it('supports sorting the first text column independently', () => {
    const state = nextColumnSortState(clearSortState(), ROW_LABEL_SORT_KEY);

    expect(sortDirectionForColumn(state, ROW_LABEL_SORT_KEY)).toBe('desc');
    expect(sortRowsFromSource(rows, columns, state).map((row) => row.rowKey)).toEqual(['zeta', 'beta', 'alpha']);
  });

  it('recomputes from source rows when switching from text sort to metric sort', () => {
    const textSortedState = nextColumnSortState(clearSortState(), ROW_LABEL_SORT_KEY);
    expect(sortRowsFromSource(rows, columns, textSortedState).map((row) => row.rowKey)).toEqual(['zeta', 'beta', 'alpha']);

    const metricSortedState = nextColumnSortState(textSortedState, 'recentSync');
    expect(sortRowsFromSource(rows, columns, metricSortedState).map((row) => row.rowKey)).toEqual(['zeta', 'beta', 'alpha']);

    const toggledMetricState = nextColumnSortState(metricSortedState, 'recentSync');
    expect(sortRowsFromSource(rows, columns, toggledMetricState).map((row) => row.rowKey)).toEqual(['alpha', 'zeta', 'beta']);
  });

  it('keeps inactive columns in default state', () => {
    const state = nextColumnSortState(clearSortState(), 'updatedAt');

    expect(sortDirectionForColumn(state, ROW_LABEL_SORT_KEY)).toBe('default');
    expect(sortDirectionForColumn(state, 'recentSync')).toBe('default');
    expect(sortDirectionForColumn(state, 'updatedAt')).toBe('desc');
  });
});
