import { ref } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticBoardDefinition } from '../types/api';
import {
  createDefaultStatisticBoardViewPrefs,
  loadStatisticBoardViewPrefs,
  resetStatisticBoardViewPrefs,
  saveStatisticBoardViewPrefs,
  type StatisticBoardViewPrefs,
} from '../components/statistic-board-view-prefs';
import { clearSortState } from '../components/statistic-board-sorting';
import { routeBoardSortColumn, routeBoardSortDirection } from '../components/statistic-board-route-query';

type WidthStrategy = StatisticBoardViewPrefs['widthStrategy'];

interface UseStatisticBoardViewPrefsOptions {
  boardKey: () => string;
  routeQuery: () => LocationQuery;
  replaceRouteQuery: (patch: Record<string, string | number | null | undefined>) => void | Promise<void>;
  notifySuccess: (message: string) => void;
  notifyWarning: (message: string) => void;
}

function createEmptyViewPrefs(): StatisticBoardViewPrefs {
  return {
    visibleColumnKeys: [],
    groupOrder: [],
    childGroupOrderByParent: {},
    columnOrderByGroup: {},
    sortColumnKey: '',
    sortDirection: 'default',
    widthStrategy: 'compact',
  };
}

export function useStatisticBoardViewPrefs(options: UseStatisticBoardViewPrefsOptions) {
  const boardViewPrefs = ref<StatisticBoardViewPrefs>(createEmptyViewPrefs());

  function applyStoredViewPrefs(definition: StatisticBoardDefinition) {
    const storedPrefs = loadStatisticBoardViewPrefs(options.boardKey(), definition);
    boardViewPrefs.value = {
      ...storedPrefs,
      sortColumnKey: routeBoardSortColumn(options.routeQuery()),
      sortDirection: routeBoardSortDirection(options.routeQuery()),
    };
  }

  function persistViewPrefs() {
    saveStatisticBoardViewPrefs(options.boardKey(), boardViewPrefs.value);
  }

  function saveVisibleColumnPrefs(draftVisibleColumnKeys: string[], closeSettings: () => void) {
    if (!draftVisibleColumnKeys.length) {
      options.notifyWarning('至少保留一列用于展示');
      return false;
    }
    boardViewPrefs.value = {
      ...boardViewPrefs.value,
      visibleColumnKeys: [...draftVisibleColumnKeys],
    };
    persistViewPrefs();
    closeSettings();
    options.notifySuccess('视图配置已保存');
    return true;
  }

  function restoreDefaultViewPrefs(
    definition: StatisticBoardDefinition,
    syncDraftFromVisible: () => void,
    closeSettings: () => void,
  ) {
    boardViewPrefs.value = createDefaultStatisticBoardViewPrefs(definition);
    syncDraftFromVisible();
    resetStatisticBoardViewPrefs(options.boardKey());
    closeSettings();
    options.notifySuccess('已恢复默认视图');
  }

  function clearCurrentSort() {
    boardViewPrefs.value = {
      ...boardViewPrefs.value,
      ...clearSortState(),
    };
    persistViewPrefs();
    void options.replaceRouteQuery({
      sortBy: '',
      sortOrder: '',
    });
    options.notifySuccess('已恢复默认排序');
  }

  function updateWidthStrategy(value: WidthStrategy) {
    boardViewPrefs.value = {
      ...boardViewPrefs.value,
      widthStrategy: value,
    };
  }

  return {
    boardViewPrefs,
    applyStoredViewPrefs,
    persistViewPrefs,
    saveVisibleColumnPrefs,
    restoreDefaultViewPrefs,
    clearCurrentSort,
    updateWidthStrategy,
  };
}
