import { ref, shallowRef } from 'vue';
import type { LocationQuery } from 'vue-router';
import type { StatisticBoardDefinition } from '../types/api';
import {
  createDefaultStatisticBoardViewPrefs,
  loadStatisticBoardViewPrefs,
  resetStatisticBoardViewPrefs,
  resolveStatisticBoardViewPrefs,
  saveStatisticBoardViewPrefs,
  statisticColumnKeys,
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
    hiddenColumnKeys: [],
    groupOrder: [],
    childGroupOrderByParent: {},
    columnOrderByGroup: {},
    sortColumnKey: '',
    sortDirection: 'default',
    widthStrategy: 'compact',
    stickyHeaderEnabled: true,
  };
}

export function useStatisticBoardViewPrefs(options: UseStatisticBoardViewPrefsOptions) {
  const boardViewPrefs = ref<StatisticBoardViewPrefs>(createEmptyViewPrefs());
  const activeDefinition = shallowRef<StatisticBoardDefinition | null>(null);

  function applyStoredViewPrefs(definition: StatisticBoardDefinition) {
    activeDefinition.value = definition;
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

  /** 收藏与浏览器配置使用同一解析边界，旧收藏迁入后仅保存隐藏意图。 */
  function applySavedViewPrefs(value: unknown) {
    if (!activeDefinition.value) { return; }
    boardViewPrefs.value = resolveStatisticBoardViewPrefs(value, activeDefinition.value);
    persistViewPrefs();
  }

  function saveVisibleColumnPrefs(draftVisibleColumnKeys: string[], closeSettings: () => void) {
    const activeKeys = activeDefinition.value ? statisticColumnKeys(activeDefinition.value) : [];
    const selected = new Set(draftVisibleColumnKeys.filter(key => activeKeys.includes(key)));
    if (!selected.size) {
      options.notifyWarning('至少保留一列用于展示');
      return false;
    }
    boardViewPrefs.value = {
      ...boardViewPrefs.value,
      hiddenColumnKeys: [
        ...boardViewPrefs.value.hiddenColumnKeys.filter(key => !activeKeys.includes(key)),
        ...activeKeys.filter(key => !selected.has(key)),
      ],
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
    activeDefinition.value = definition;
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

  function updateStickyHeaderEnabled(value: boolean) {
    boardViewPrefs.value = {
      ...boardViewPrefs.value,
      stickyHeaderEnabled: value,
    };
  }

  return {
    boardViewPrefs,
    applyStoredViewPrefs,
    applySavedViewPrefs,
    persistViewPrefs,
    saveVisibleColumnPrefs,
    restoreDefaultViewPrefs,
    clearCurrentSort,
    updateWidthStrategy,
    updateStickyHeaderEnabled,
  };
}
