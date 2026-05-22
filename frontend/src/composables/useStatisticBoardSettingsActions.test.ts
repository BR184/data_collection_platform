import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardSettingsActions } from './useStatisticBoardSettingsActions';
import type { StatisticBoardResponse } from '../types/api';

function createBoard(): StatisticBoardResponse {
  return {
    definition: {
      boardKey: 'test-board',
      title: 'Test board',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: 'Row',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
      emptyText: '',
    },
    appliedFilters: {},
    appliedFilterGroup: null,
    rows: [],
    meta: {
      generatedAt: '2026-04-30T10:00:00',
      queryDurationMs: 1,
      rowCount: 0,
      columnCount: 0,
      drilldownColumnCount: 0,
    },
  };
}

function setup(board = ref<StatisticBoardResponse | null>(createBoard())) {
  const draftVisibleColumnKeys = ref(['a', 'b']);
  const openSettings = vi.fn();
  const closeSettings = vi.fn();
  const clearCurrentSort = vi.fn();
  const syncDraftFromVisible = vi.fn();
  const saveVisibleColumnPrefs = vi.fn();
  const restoreDefaultViewPrefs = vi.fn();
  const toggleAutoRefreshOnEnter = vi.fn();
  const actions = useStatisticBoardSettingsActions({
    board,
    draftVisibleColumnKeys,
    openSettings,
    closeSettings,
    clearCurrentSort,
    syncDraftFromVisible,
    saveVisibleColumnPrefs,
    restoreDefaultViewPrefs,
    toggleAutoRefreshOnEnter,
  });
  return {
    board,
    draftVisibleColumnKeys,
    openSettings,
    closeSettings,
    clearCurrentSort,
    syncDraftFromVisible,
    saveVisibleColumnPrefs,
    restoreDefaultViewPrefs,
    toggleAutoRefreshOnEnter,
    actions,
  };
}

describe('useStatisticBoardSettingsActions', () => {
  it('routes settings commands to the expected action', () => {
    const context = setup();

    context.actions.handleSettingsCommand('open-settings');
    context.actions.handleSettingsCommand('clear-sort');
    context.actions.handleSettingsCommand('toggle-auto-refresh');
    context.actions.handleSettingsCommand('restore-default-view');

    expect(context.openSettings).toHaveBeenCalledTimes(1);
    expect(context.clearCurrentSort).toHaveBeenCalledTimes(1);
    expect(context.toggleAutoRefreshOnEnter).toHaveBeenCalledTimes(1);
    expect(context.restoreDefaultViewPrefs).toHaveBeenCalledWith(
      context.board.value?.definition,
      context.syncDraftFromVisible,
      context.closeSettings,
    );
  });

  it('saves visible column preferences only when a board is loaded', () => {
    const context = setup();

    context.actions.saveViewPrefs();

    expect(context.saveVisibleColumnPrefs).toHaveBeenCalledWith(['a', 'b'], context.closeSettings);

    context.board.value = null;
    context.actions.saveViewPrefs();

    expect(context.saveVisibleColumnPrefs).toHaveBeenCalledTimes(1);
  });

  it('restores default view only when a board is loaded', () => {
    const context = setup(ref(null));

    context.actions.restoreDefaultView();

    expect(context.restoreDefaultViewPrefs).not.toHaveBeenCalled();
  });
});
