import { beforeEach, describe, expect, it } from 'vitest';
import { createDefaultStatisticBoardViewPrefs, loadStatisticBoardViewPrefs, resolveStatisticBoardViewPrefs, saveStatisticBoardViewPrefs } from './statistic-board-view-prefs';
import { resolveOrderedColumnGroups } from './statistic-board-column-layout';
import { flattenStatisticColumnLeaves, type StatisticBoardDefinition } from '../types/api';

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
  beforeEach(() => window.localStorage.clear());

  it('creates default prefs with visible leaves and nested group order', () => {
    const prefs = createDefaultStatisticBoardViewPrefs(createDefinition());

    expect(prefs.hiddenColumnKeys).toEqual([]);
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

  it.each(['moduleName', 'functionName'])('shows new %s after loading the old customer view without restoring hidden metrics', (columnKey) => {
    const definition = createDefinition();
    const legacy = {
      visibleColumnKeys: ['a1-count', 'a2-count', 'b-count'],
      groupOrder: ['root-b', 'root-a'],
      childGroupOrderByParent: { 'root-a': ['child-a2', 'child-a1'] },
      columnOrderByGroup: { 'root-a': ['a2-count', 'a1-rate', 'a1-count'], 'root-b': ['b-count'] },
      sortColumnKey: 'b-count', sortDirection: 'desc', widthStrategy: 'content', stickyHeaderEnabled: false,
    };
    window.localStorage.setItem('stat-board-view:test-board', JSON.stringify(legacy));
    definition.columnGroups.unshift({ key: 'row-dimension', label: '维度', columns: [{ key: columnKey, label: '维度', metricType: 'text', drilldown: false }] });

    const prefs = loadStatisticBoardViewPrefs('test-board', definition);
    const visible = flattenStatisticColumnLeaves(resolveOrderedColumnGroups(definition.columnGroups, prefs)).map(column => column.key);

    expect(visible).toContain(columnKey);
    expect(visible).not.toContain('a1-rate');
    expect(prefs.groupOrder).toEqual(['root-b', 'root-a', 'row-dimension']);
    expect(prefs.childGroupOrderByParent['root-a']).toEqual(['child-a2', 'child-a1']);
    expect(prefs.columnOrderByGroup['root-a']).toEqual(['a2-count', 'a1-rate', 'a1-count']);
    expect(prefs.sortColumnKey).toBe('b-count');
    expect(prefs.sortDirection).toBe('desc');
    expect(prefs.widthStrategy).toBe('content');
    expect(prefs.stickyHeaderEnabled).toBe(false);
    const migrated = JSON.parse(window.localStorage.getItem('stat-board-view:test-board')!);
    expect(migrated.hiddenColumnKeys).toEqual(['a1-rate']);
    expect(migrated).not.toHaveProperty('visibleColumnKeys');
  });

  it('defaults a new metric in an existing group to visible instead of treating it as hidden', () => {
    window.localStorage.setItem('stat-board-view:test-board', JSON.stringify({
      visibleColumnKeys: ['a1-count'],
      columnOrderByGroup: { 'root-a': ['a1-count', 'a1-rate', 'a2-count'], 'root-b': ['b-count'] },
    }));
    const definition = createDefinition();
    definition.columnGroups[0].children![0].columns!.push({ key: 'new-metric', label: '新增指标', metricType: 'count', drilldown: true });
    const prefs = loadStatisticBoardViewPrefs('test-board', definition);
    const visible = flattenStatisticColumnLeaves(resolveOrderedColumnGroups(definition.columnGroups, prefs)).map(column => column.key);
    expect(visible).toEqual(['a1-count', 'new-metric']);
  });

  it('restores an old saved-view snapshot through the same migration boundary', () => {
    const resolved = resolveStatisticBoardViewPrefs({
      visibleColumnKeys: ['a1-count', 'a2-count', 'b-count'],
      columnOrderByGroup: { 'root-a': ['a1-count', 'a1-rate', 'a2-count'], 'root-b': ['b-count'] },
    }, createDefinition());
    expect(resolved.hiddenColumnKeys).toEqual(['a1-rate']);
    expect(resolved).not.toHaveProperty('visibleColumnKeys');
  });

  it('retains hidden intent for columns absent from the current dimension and shows new columns', () => {
    const prefs = createDefaultStatisticBoardViewPrefs(createDefinition());
    prefs.hiddenColumnKeys = ['moduleName', 'a1-rate'];
    saveStatisticBoardViewPrefs('test-board', prefs);
    const customerPrefs = loadStatisticBoardViewPrefs('test-board', createDefinition());
    saveStatisticBoardViewPrefs('test-board', customerPrefs);
    const module = createDefinition();
    module.columnGroups.unshift({ key: 'row-dimension', label: '模块', columns: [
      { key: 'moduleName', label: '模块', metricType: 'text', drilldown: false },
      { key: 'new-column', label: '新增列', metricType: 'count', drilldown: false },
    ] });
    const resolved = loadStatisticBoardViewPrefs('test-board', module);
    const visible = flattenStatisticColumnLeaves(resolveOrderedColumnGroups(module.columnGroups, resolved)).map(column => column.key);
    expect(visible).not.toContain('moduleName');
    expect(visible).not.toContain('a1-rate');
    expect(visible).toContain('new-column');
  });

  it.each(['null', '[]', '{', '{"columnOrderByGroup":{"root-a":null},"groupOrder":42,"hiddenColumnKeys":[null,3]}'])('uses safe defaults for malformed storage %s', (raw) => {
    window.localStorage.setItem('stat-board-view:test-board', raw);
    expect(loadStatisticBoardViewPrefs('test-board', createDefinition())).toEqual(createDefaultStatisticBoardViewPrefs(createDefinition()));
  });

  it('does not let damaged hidden settings remove every current column', () => {
    const resolved = resolveStatisticBoardViewPrefs({ hiddenColumnKeys: ['a1-count', 'a1-rate', 'a2-count', 'b-count', 'moduleName'] }, createDefinition());
    expect(resolved.hiddenColumnKeys).toEqual(['moduleName']);
  });
});
