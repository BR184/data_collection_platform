import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { LocationQuery } from 'vue-router';
import { useStatisticBoardViewPrefs } from './useStatisticBoardViewPrefs';
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

    expect(prefs.boardViewPrefs.value.visibleColumnKeys).toEqual(['blocked', 'resolved']);
    expect(prefs.boardViewPrefs.value.sortColumnKey).toBe('resolved');
    expect(prefs.boardViewPrefs.value.sortDirection).toBe('desc');

    expect(prefs.saveVisibleColumnPrefs([], closeSettings)).toBe(false);
    expect(notifyWarning).toHaveBeenCalledWith('至少保留一列用于展示');

    expect(prefs.saveVisibleColumnPrefs(['blocked'], closeSettings)).toBe(true);
    expect(prefs.boardViewPrefs.value.visibleColumnKeys).toEqual(['blocked']);
    expect(closeSettings).toHaveBeenCalledOnce();
    expect(notifySuccess).toHaveBeenCalledWith('视图配置已保存');

    prefs.updateWidthStrategy('content');
    expect(prefs.boardViewPrefs.value.widthStrategy).toBe('content');

    prefs.clearCurrentSort();
    expect(prefs.boardViewPrefs.value.sortColumnKey).toBe('');
    expect(prefs.boardViewPrefs.value.sortDirection).toBe('default');
    expect(replaceRouteQuery).toHaveBeenCalledWith({ sortBy: '', sortOrder: '' });

    prefs.restoreDefaultViewPrefs(createDefinition(), syncDraftFromVisible, closeSettings);
    expect(prefs.boardViewPrefs.value.visibleColumnKeys).toEqual(['blocked', 'resolved']);
    expect(syncDraftFromVisible).toHaveBeenCalledOnce();
    expect(window.localStorage.getItem('stat-board-view:board-a')).toBeNull();
  });
});
