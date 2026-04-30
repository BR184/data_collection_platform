import type { Ref } from 'vue';
import type { StatisticBoardDefinition, StatisticBoardResponse } from '../types/api';

interface StatisticBoardSettingsActionsDependencies {
  board: Ref<StatisticBoardResponse | null>;
  draftVisibleColumnKeys: Ref<string[]>;
  openSettings: () => void;
  closeSettings: () => void;
  clearCurrentSort: () => void;
  syncDraftFromVisible: () => void;
  saveVisibleColumnPrefs: (visibleColumnKeys: string[], afterSave?: () => void) => void;
  restoreDefaultViewPrefs: (
    definition: StatisticBoardDefinition,
    afterRestore?: () => void,
    afterClose?: () => void,
  ) => void;
}

export function useStatisticBoardSettingsActions(deps: StatisticBoardSettingsActionsDependencies) {
  function restoreDefaultView() {
    if (!deps.board.value) {
      return;
    }
    deps.restoreDefaultViewPrefs(
      deps.board.value.definition,
      deps.syncDraftFromVisible,
      deps.closeSettings,
    );
  }

  function handleSettingsCommand(command: string) {
    if (command === 'open-settings') {
      deps.openSettings();
      return;
    }
    if (command === 'clear-sort') {
      deps.clearCurrentSort();
      return;
    }
    if (command === 'restore-default-view') {
      restoreDefaultView();
    }
  }

  function saveViewPrefs() {
    if (!deps.board.value) {
      return;
    }
    deps.saveVisibleColumnPrefs(deps.draftVisibleColumnKeys.value, deps.closeSettings);
  }

  return {
    handleSettingsCommand,
    saveViewPrefs,
    restoreDefaultView,
  };
}
