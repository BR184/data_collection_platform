import { describe, expect, it } from 'vitest';
import type { StatisticColumnLeaf, StatisticRowData } from '../types/api';
import {
  clearSortState,
  nextColumnSortState,
  ROW_LABEL_SORT_KEY,
  sortDirectionForColumn,
  sortRowsFromSource,
  type StatisticBoardSortState,
} from './statistic-board-sorting';

function buildRow(
  rowKey: string,
  rowLabel: string,
  values: Record<string, { numericValue: number | null; displayValue: string }>,
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
  { key: 'updatedAt', label: '有源更新时间', drilldown: true, metricType: 'text' },
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

  it('separates no-data rate cells from real zero when sorting descending', () => {
    const state: StatisticBoardSortState = { sortColumnKey: 'level1Rate', sortDirection: 'desc' };

    expect(sortRowsFromSource(rateRows, rateColumns, state).map((row) => row.rowKey)).toEqual([
      '平台',
      '工程图',
      // 真实 0.00% 先按数值参与排序，再是“无数据（/）”，且保留后端原始行序。
      '企业版',
      '数据交换',
      '二次开发',
      '库',
      '总计',
    ]);
  });

  it('keeps no-data rate cells at the bottom when sorting ascending', () => {
    const state: StatisticBoardSortState = { sortColumnKey: 'level1Rate', sortDirection: 'asc' };

    expect(sortRowsFromSource(rateRows, rateColumns, state).map((row) => row.rowKey)).toEqual([
      '企业版',
      '数据交换',
      '工程图',
      '平台',
      '二次开发',
      '库',
      '总计',
    ]);
  });

  it('keeps blank and slash display text at the bottom of text columns', () => {
    const state: StatisticBoardSortState = { sortColumnKey: 'milestone', sortDirection: 'asc' };

    expect(sortRowsFromSource(textRows, textColumns, state).map((row) => row.rowKey)).toEqual([
      'dwg',
      'platform',
      // 两侧都是无数据时比较结果为 0，按原始行序稳定兜底：slash 在空值之前。
      'slash',
      'empty',
    ]);
  });
});

const rateColumns: StatisticColumnLeaf[] = [
  { key: 'level1Rate', label: '一级修复率', drilldown: false, metricType: 'ratio' },
];

const rateRows: StatisticRowData[] = [
  buildRow('平台', '平台', { level1Rate: { numericValue: 3333, displayValue: '33.33%' } }),
  buildRow('企业版', '企业版', { level1Rate: { numericValue: 0, displayValue: '0.00%' } }),
  buildRow('二次开发', '二次开发', { level1Rate: { numericValue: null, displayValue: '/' } }),
  buildRow('工程图', '工程图', { level1Rate: { numericValue: 3125, displayValue: '31.25%' } }),
  buildRow('库', '库', { level1Rate: { numericValue: null, displayValue: '/' } }),
  buildRow('数据交换', '数据交换', { level1Rate: { numericValue: 0, displayValue: '0.00%' } }),
  buildRow('总计', '总计', { level1Rate: { numericValue: 5000, displayValue: '50.00%' } }),
];

const textColumns: StatisticColumnLeaf[] = [
  { key: 'milestone', label: '产品版本', drilldown: false, metricType: 'text' },
];

const textRows: StatisticRowData[] = [
  buildRow('slash', 'slash', { milestone: { numericValue: 0, displayValue: '/' } }),
  buildRow('platform', 'platform', { milestone: { numericValue: 0, displayValue: 'CC2026 R3' } }),
  buildRow('empty', 'empty', { milestone: { numericValue: 0, displayValue: '' } }),
  buildRow('dwg', 'dwg', { milestone: { numericValue: 0, displayValue: 'CC2026 R2' } }),
];
