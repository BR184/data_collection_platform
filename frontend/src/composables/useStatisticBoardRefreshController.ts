import type { Ref } from 'vue';

interface StatisticBoardRefreshControllerDependencies {
  loading: Ref<boolean>;
  detailVisible: Ref<boolean>;
  loadBoard: () => Promise<void>;
  loadRuleExplanation: () => Promise<void>;
  loadDetail: () => Promise<void>;
}

export function useStatisticBoardRefreshController(deps: StatisticBoardRefreshControllerDependencies) {
  async function refreshBoard() {
    deps.loading.value = true;
    try {
      await deps.loadBoard();
      await deps.loadRuleExplanation();
      if (deps.detailVisible.value) {
        await deps.loadDetail();
      }
    } finally {
      deps.loading.value = false;
    }
  }

  return {
    refreshBoard,
  };
}
