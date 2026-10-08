import { computed, ref } from 'vue';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';

interface UseRealtimeWorkspaceStatusOptions {
  loadStatus: () => Promise<RealtimeWorkspaceStatusResponse>;
  emptyText?: string;
}

export function formatRealtimeLastSyncedText(lastSyncedAt: string | null | undefined, emptyText = '暂无同步记录') {
  return formatBeijingDateTime(lastSyncedAt, emptyText);
}

/**
 * 读取并持有工作区实时状态；只负责状态展示与刷新状态的取数。
 *
 * 刷新提交、按钮窗口与后台跟踪由 {@link useRealtimeRefreshLifecycle} 统一承载，
 * 本 composable 只提供 `loadRealtimeStatus` 供其轮询，并在读取失败时清除上一范围的状态显示。
 */
export function useRealtimeWorkspaceStatus(options: UseRealtimeWorkspaceStatusOptions) {
  const syncStatus = ref<RealtimeWorkspaceStatusResponse | null>(null);
  let loadRunId = 0;
  const lastSyncedText = computed(() =>
    formatRealtimeLastSyncedText(syncStatus.value?.lastSyncedAt, options.emptyText),
  );

  async function loadRealtimeStatus() {
    const runId = ++loadRunId;
    try {
      const nextStatus = await options.loadStatus();
      if (runId === loadRunId) {
        syncStatus.value = nextStatus;
      }
      return syncStatus.value;
    } catch {
      if (runId === loadRunId) {
        syncStatus.value = null;
      }
      return null;
    }
  }

  /** 作废过期路由的状态响应，并清除上一范围的状态显示。 */
  function invalidateRealtimeStatusRequest() {
    loadRunId += 1;
    syncStatus.value = null;
  }

  return {
    syncStatus,
    lastSyncedText,
    loadRealtimeStatus,
    invalidateRealtimeStatusRequest,
  };
}
