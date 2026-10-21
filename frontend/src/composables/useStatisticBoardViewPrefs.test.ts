import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { LocationQuery } from 'vue-router';
import { useStatisticBoardViewPrefs } from './useStatisticBoardViewPrefs';
import type { StatisticBoardDefinition } from '../types/api';
import { flattenStatisticColumnLeaves } from '../types/api';
import { resolveOrderedColumnGroups } from '../components/statistic-board-column-layout';

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
        key: 'quality',
        label: '质量',
        columns: [
          { key: 'blocked', label: '阻塞数', drilldown: true, metricType: 'count' },
          { key: 'resolved', label: '已解决', drilldown: true, metricType: 'count' },
        ],
      },
    ],
  };
}

describe('useStatisticBoardViewPrefs', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('loads route-aware prefs and manages save, reset, width, and sort actions', () => {
    const query: LocationQuery = {
      sortBy: 'resolved',
      sortOrder: 'desc',
    };
    const replaceRouteQuery = vi.fn();
    const notifySuccess = vi.fn();
    const notifyWarning = vi.fn();
    const closeSettings = vi.fn();
    const syncDraftFromVisible = vi.fn();

    const prefs = useStatisticBoardViewPrefs({
      boardKey: () => 'board-a',
      routeQuery: () => query,
      replaceRouteQuery,
      notifySuccess,
      notifyWarning,
    });

    prefs.applyStoredViewPrefs(createDefinition());

    expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual([]);
    expect(prefs.boardViewPrefs.value.sortColumnKey).toBe('resolved');
    expect(prefs.boardViewPrefs.value.sortDirection).toBe('desc');

    expect(prefs.saveVisibleColumnPrefs([], closeSettings)).toBe(false);
    expect(notifyWarning).toHaveBeenCalledWith('至少保留一列用于展示');

    expect(prefs.saveVisibleColumnPrefs(['blocked'], closeSettings)).toBe(true);
    expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual(['resolved']);
    expect(closeSettings).toHaveBeenCalledOnce();
    expect(notifySuccess).toHaveBeenCalledWith('视图配置已保存');

    prefs.updateWidthStrategy('content');
    expect(prefs.boardViewPrefs.value.widthStrategy).toBe('content');

    prefs.clearCurrentSort();
    expect(prefs.boardViewPrefs.value.sortColumnKey).toBe('');
    expect(prefs.boardViewPrefs.value.sortDirection).toBe('default');
    expect(replaceRouteQuery).toHaveBeenCalledWith({ sortBy: '', sortOrder: '' });

    prefs.restoreDefaultViewPrefs(createDefinition(), syncDraftFromVisible, closeSettings);
    expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual([]);
    expect(syncDraftFromVisible).toHaveBeenCalledOnce();
    expect(window.localStorage.getItem('stat-board-view:board-a')).toBeNull();
  });

  it('keeps hidden metrics and layout while switching customer, module and function after sorting saves preferences', () => {
    const prefs = useStatisticBoardViewPrefs({ boardKey: () => 'board-a', routeQuery: () => ({}), replaceRouteQuery: vi.fn(), notifySuccess: vi.fn(), notifyWarning: vi.fn() });
    const dimensionDefinition = (key: string) => {
      const definition = createDefinition();
      if (key) { definition.columnGroups.unshift({ key: 'row-dimension', label: key, columns: [{ key, label: key, drilldown: false, metricType: 'text' }] }); }
      return definition;
    };
    prefs.applyStoredViewPrefs(dimensionDefinition(''));
    prefs.saveVisibleColumnPrefs(['blocked'], vi.fn());
    prefs.updateWidthStrategy('content');
    prefs.updateStickyHeaderEnabled(false);
    prefs.clearCurrentSort();

    for (const dimension of ['moduleName', 'functionName', '', 'moduleName', 'functionName']) {
      const definition = dimensionDefinition(dimension);
      prefs.applyStoredViewPrefs(definition);
      const visible = flattenStatisticColumnLeaves(resolveOrderedColumnGroups(definition.columnGroups, prefs.boardViewPrefs.value)).map(column => column.key);
      expect(visible).toEqual(dimension ? ['blocked', dimension] : ['blocked']);
      expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual(['resolved']);
      expect(prefs.boardViewPrefs.value.widthStrategy).toBe('content');
      expect(prefs.boardViewPrefs.value.stickyHeaderEnabled).toBe(false);
      prefs.persistViewPrefs();
    }
    expect(JSON.parse(window.localStorage.getItem('stat-board-view:board-a')!)).not.toHaveProperty('visibleColumnKeys');
  });

  it('migrates old saved views instead of merging their obsolete visible whitelist into runtime state', () => {
    const prefs = useStatisticBoardViewPrefs({ boardKey: () => 'board-a', routeQuery: () => ({}), replaceRouteQuery: vi.fn(), notifySuccess: vi.fn(), notifyWarning: vi.fn() });
    const definition = createDefinition();
    definition.columnGroups.unshift({ key: 'dimension', label: '模块', columns: [{ key: 'moduleName', label: '模块', metricType: 'text', drilldown: false }] });
    prefs.applyStoredViewPrefs(definition);
    prefs.applySavedViewPrefs({ visibleColumnKeys: ['blocked'], columnOrderByGroup: { quality: ['resolved', 'blocked'] }, widthStrategy: 'header' });
    const visible = flattenStatisticColumnLeaves(resolveOrderedColumnGroups(definition.columnGroups, prefs.boardViewPrefs.value)).map(column => column.key);
    expect(visible).toEqual(['moduleName', 'blocked']);
    expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual(['resolved']);
    expect(prefs.boardViewPrefs.value).not.toHaveProperty('visibleColumnKeys');
    expect(prefs.boardViewPrefs.value.widthStrategy).toBe('header');
    expect(JSON.parse(window.localStorage.getItem('stat-board-view:board-a')!)).not.toHaveProperty('visibleColumnKeys');
  });

  it('preserves an absent dimension explicitly hidden by the user when saving settings in another dimension', () => {
    const prefs = useStatisticBoardViewPrefs({ boardKey: () => 'board-a', routeQuery: () => ({}), replaceRouteQuery: vi.fn(), notifySuccess: vi.fn(), notifyWarning: vi.fn() });
    const module = createDefinition();
    module.columnGroups.unshift({ key: 'dimension', label: '模块', columns: [{ key: 'moduleName', label: '模块', metricType: 'text', drilldown: false }] });
    prefs.applyStoredViewPrefs(module);
    prefs.saveVisibleColumnPrefs(['blocked', 'resolved'], vi.fn());
    prefs.applyStoredViewPrefs(createDefinition());
    prefs.saveVisibleColumnPrefs(['blocked'], vi.fn());
    prefs.applyStoredViewPrefs(module);
    expect(prefs.boardViewPrefs.value.hiddenColumnKeys).toEqual(['moduleName', 'resolved']);
  });
});
