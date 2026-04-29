import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardSortControls } from './useStatisticBoardSortControls';
import type { StatisticBoardResponse } from '../types/api';
import type { StatisticBoardViewPrefs } from '../components/statistic-board-view-prefs';
import { ROW_LABEL_SORT_KEY } from '../components/statistic-board-sorting';

function createBoard(): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'test-board',
      title: '统计表',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '统计对象',
      filters: [],
      detailColumns: [],
      defaultPageSize: 10,
      columnGroups: [
        {
          key: 'quality',
          label: '质量',
          columns: [
            { key: 'blocked', label: '阻塞数', drilldown: true, metricType: 'count' },
            { key: 'resolved', label: '解决数', drilldown: true, metricType: 'count' },
          ],
        },
      ],
    },
    appliedFilters: {},
    rows: [],
    meta: {
      generatedAt: '2026-04-29T00:00:00',
      queryDurationMs: 1,
      rowCount: 0,
      columnCount: 2,
      drilldownColumnCount: 2,
    },
  };
}

function createPrefs(): StatisticBoardViewPrefs {
  return {
    visibleColumnKeys: ['blocked', 'resolved'],
    groupOrder: ['quality'],
    childGroupOrderByParent: {},
    columnOrderByGroup: { quality: ['blocked', 'resolved'] },
    sortColumnKey: ROW_LABEL_SORT_KEY,
    sortDirection: 'asc',
    widthStrategy: 'compact',
  };
}

describe('useStatisticBoardSortControls', () => {
  it('resolves current sort summary and toggles column sort with route sync', () => {
    const board = ref<StatisticBoardResponse | null>(createBoard());
    const boardViewPrefs = ref(createPrefs());
    const persistViewPrefs = vi.fn();
    const replaceRouteQuery = vi.fn();

    const controls = useStatisticBoardSortControls({
      board,
      boardViewPrefs,
      persistViewPrefs,
      replaceRouteQuery,
    });

    expect(controls.currentSortSummary.value).toBe('统计对象 / 升序');
    expect(controls.sortDirectionForColumn(ROW_LABEL_SORT_KEY)).toBe('asc');
    expect(controls.sortStateLabel('default')).toBe('当前为默认顺序，点击开始排序');

    controls.toggleColumnSort('blocked');

    expect(boardViewPrefs.value.sortColumnKey).toBe('blocked');
    expect(boardViewPrefs.value.sortDirection).toBe('desc');
    expect(persistViewPrefs).toHaveBeenCalledOnce();
    expect(replaceRouteQuery).toHaveBeenCalledWith({
      sortBy: 'blocked',
      sortOrder: 'desc',
    });
    expect(controls.currentSortSummary.value).toBe('阻塞数 / 降序');
  });
});
