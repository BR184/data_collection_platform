import type { Ref } from 'vue';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';
import { toUserMessage } from '../utils/user-message';
import { waitForRealtimeWorkspaceRefresh } from './useRealtimeWorkspaceStatus';

interface StatisticBoardRefreshControllerDependencies {
  loading: Ref<boolean>;
  detailVisible: Ref<boolean>;
  loadBoard: () => Promise<void>;
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
        deps.notifySuccess?.(toUserMessage(refreshStatus.message, '已开始刷新最新数据'));
        await waitForRealtimeRefreshToSettle(refreshStatus);
      }
      await deps.loadBoard();
      if (deps.detailVisible.value) {
        await deps.loadDetail();
      }
    } finally {
      deps.loading.value = false;
    }
  }

  async function autoRefreshBoard() {
    deps.loading.value = true;
    try {
      if (deps.requestRealtimeRefresh) {
        const refreshStatus = await deps.requestRealtimeRefresh();
        await waitForRealtimeRefreshToSettle(refreshStatus);
      }
      await deps.loadBoard();
      if (deps.detailVisible.value) {
        await deps.loadDetail();
      }
    } finally {
      deps.loading.value = false;
    }
  }

  return {
    autoRefreshBoard,
    refreshBoard,
  };

  async function waitForRealtimeRefreshToSettle(initialStatus: RealtimeWorkspaceStatusResponse | null | undefined) {
    if (!deps.loadRealtimeStatus || !initialStatus) {
      return;
    }
    await waitForRealtimeWorkspaceRefresh(initialStatus, deps.loadRealtimeStatus);
  }
}
