import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { useStatisticBoardTableState } from './useStatisticBoardTableState';
import type { StatisticBoardResponse } from '../types/api';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';

function createBoard(): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'code-review',
      title: '代码评审统计',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '模块',
      filters: [],
      columnGroups: [
        {
          key: 'quality',
          label: '质量',
          columns: [
            {
              key: 'blocked',
              label: '阻塞数',
              drilldown: true,
              metricType: 'count',
            },
            {
              key: 'resolved',
              label: '解决数',
              drilldown: true,
              metricType: 'count',
            },
          ],
        },
      ],
      detailColumns: [],
      defaultPageSize: 20,
    },
    appliedFilters: {},
    rows: [
      {
        rowKey: 'module-b',
        rowLabel: '模块B',
        cells: [
          { columnKey: 'blocked', numericValue: 2, displayValue: '2', drilldown: true, detailParams: {} },
          { columnKey: 'resolved', numericValue: 1, displayValue: '1', drilldown: true, detailParams: {} },
        ],
      },
      {
        rowKey: 'module-a',
        rowLabel: '模块A',
        cells: [
          { columnKey: 'blocked', numericValue: 5, displayValue: '5', drilldown: true, detailParams: {} },
          { columnKey: 'resolved', numericValue: 3, displayValue: '3', drilldown: true, detailParams: {} },
        ],
      },
    ],
    meta: {
      generatedAt: '2026-04-29T09:00:00Z',
      queryDurationMs: 3,
      rowCount: 2,
      columnCount: 2,
      drilldownColumnCount: 2,
    },
  };
}

function createPrefs(): StatisticBoardViewPrefs {
  return {
    visibleColumnKeys: ['blocked'],
    groupOrder: ['quality'],
    childGroupOrderByParent: {},
    columnOrderByGroup: {
      quality: ['blocked', 'resolved'],
    },
    sortColumnKey: 'blocked',
    sortDirection: 'desc',
    widthStrategy: 'compact',
  };
}

describe('useStatisticBoardTableState', () => {
  it('derives visible column groups, sorted rows, pagination, widths, and render key', () => {
    const board = ref(createBoard());
    const boardViewPrefs = ref(createPrefs());
    const tableCurrentPage = ref(1);
    const tablePageSize = ref(1);

    const state = useStatisticBoardTableState({
      board,
      boardViewPrefs,
      tableCurrentPage,
      tablePageSize,
      boardKey: () => 'code-review',
    });

    expect(state.rowHeaderLabel.value).toBe('模块');
    expect(state.orderedColumnGroups.value).toHaveLength(1);
    expect(state.orderedColumnGroups.value[0]?.columns?.map((column) => column.key)).toEqual(['blocked']);
    expect(state.sortedRows.value.map((row) => row.rowKey)).toEqual(['module-a', 'module-b']);
    expect(state.totalTableRows.value).toBe(2);
    expect(state.paginatedRows.value.map((row) => row.rowKey)).toEqual(['module-a']);
    expect(state.firstColumnWidth.value).toBeGreaterThanOrEqual(112);
    expect(state.firstColumnMinWidth.value).toBeGreaterThanOrEqual(100);
    expect(state.tableRenderKey.value).toBe('code-review::compact::quality::::quality:blocked,resolved::blocked');

    tableCurrentPage.value = 2;

    expect(state.paginatedRows.value.map((row) => row.rowKey)).toEqual(['module-b']);
  });

  it('falls back to empty board state before data has loaded', () => {
    const state = useStatisticBoardTableState({
      board: ref(null),
      boardViewPrefs: ref(createPrefs()),
      tableCurrentPage: ref(1),
      tablePageSize: ref(20),
      boardKey: () => 'code-review',
    });

    expect(state.rowHeaderLabel.value).toBe('统计对象');
    expect(state.orderedColumnGroups.value).toEqual([]);
    expect(state.sortedRows.value).toEqual([]);
    expect(state.totalTableRows.value).toBe(0);
    expect(state.paginatedRows.value).toEqual([]);
  });
});
