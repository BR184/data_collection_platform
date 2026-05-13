import type { Ref } from 'vue';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';

interface StatisticBoardRefreshControllerDependencies {
  loading: Ref<boolean>;
  detailVisible: Ref<boolean>;
  loadBoard: () => Promise<void>;
  loadRuleExplanation: () => Promise<void>;
  loadDetail: () => Promise<void>;
  requestRealtimeRefresh?: () => Promise<RealtimeWorkspaceStatusResponse>;
  loadRealtimeStatus?: () => Promise<RealtimeWorkspaceStatusResponse | null | void>;
  notifySuccess?: (message: string) => void;
}

export function useStatisticBoardRefreshController(deps: StatisticBoardRefreshControllerDependencies) {
  async function refreshBoard() {
    deps.loading.value = true;
    try {
      if (deps.requestRealtimeRefresh) {
        const refreshStatus = await deps.requestRealtimeRefresh();
        deps.notifySuccess?.(refreshStatus.message || '已开始刷新最新数据');
        await waitForRealtimeRefreshToSettle(refreshStatus);
      }
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

  async function waitForRealtimeRefreshToSettle(initialStatus: RealtimeWorkspaceStatusResponse | null | undefined) {
    if (!deps.loadRealtimeStatus) {
      return;
    }
    let status = initialStatus;
    for (let attempt = 0; attempt < 8; attempt++) {
      if (!status?.refreshing) {
        await deps.loadRealtimeStatus();
        return;
      }
      await sleep(1000);
      status = await deps.loadRealtimeStatus() as RealtimeWorkspaceStatusResponse | null | undefined;
    }
  }
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
