import { describe, expect, it } from 'vitest';
import { createDefaultStatisticBoardViewPrefs } from './statistic-board-view-prefs';
import type { StatisticBoardDefinition } from '../types/api';

function createDefinition(): StatisticBoardDefinition {
  return {
    boardKey: 'test-board',
    title: '统计表',
    description: '',
    queryTitle: '',
    queryDescription: '',
    rowHeaderLabel: '统计对象',
    defaultPageSize: 10,
    filters: [],
    detailColumns: [],
    columnGroups: [
      {
        key: 'root-a',
        label: 'Root A',
        children: [
          {
            key: 'child-a1',
            label: 'Child A1',
            columns: [
              { key: 'a1-count', label: 'A1 Count', drilldown: true, metricType: 'count' },
              { key: 'a1-rate', label: 'A1 Rate', drilldown: false, metricType: 'rate' },
            ],
          },
          {
            key: 'child-a2',
            label: 'Child A2',
            columns: [{ key: 'a2-count', label: 'A2 Count', drilldown: true, metricType: 'count' }],
          },
        ],
      },
      {
        key: 'root-b',
        label: 'Root B',
        columns: [{ key: 'b-count', label: 'B Count', drilldown: true, metricType: 'count' }],
      },
    ],
  };
}

describe('statistic-board-view-prefs', () => {
  it('creates default prefs with visible leaves and nested group order', () => {
    const prefs = createDefaultStatisticBoardViewPrefs(createDefinition());

    expect(prefs.visibleColumnKeys).toEqual(['a1-count', 'a1-rate', 'a2-count', 'b-count']);
    expect(prefs.groupOrder).toEqual(['root-a', 'root-b']);
    expect(prefs.childGroupOrderByParent).toEqual({
      'root-a': ['child-a1', 'child-a2'],
    });
    expect(prefs.columnOrderByGroup).toEqual({
      'root-a': ['a1-count', 'a1-rate', 'a2-count'],
      'root-b': ['b-count'],
    });
    expect(prefs.sortColumnKey).toBe('');
    expect(prefs.sortDirection).toBe('default');
    expect(prefs.widthStrategy).toBe('compact');
  });
});
