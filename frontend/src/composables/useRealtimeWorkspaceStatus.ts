import { computed, ref } from 'vue';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';

interface UseRealtimeWorkspaceStatusOptions {
  loadStatus: () => Promise<RealtimeWorkspaceStatusResponse>;
  emptyText?: string;
}

interface RealtimeWorkspaceRefreshWaitOptions {
  pollIntervalMs?: number;
  wait?: (milliseconds: number) => Promise<void>;
}

const DEFAULT_REFRESH_STATUS_POLL_INTERVAL_MS = 1000;

export function formatRealtimeLastSyncedText(lastSyncedAt: string | null | undefined, emptyText = '暂无同步记录') {
  return formatBeijingDateTime(lastSyncedAt, emptyText);
}

/**
 * 等待页面关联的镜像与事实刷新任务进入终态。
 *
 * 刷新请求仅表示任务已提交；只有服务端状态不再处于刷新中时，调用方才可以重新加载事实层数据。
 */
export async function waitForRealtimeWorkspaceRefresh(
  initialStatus: RealtimeWorkspaceStatusResponse,
  loadStatus: () => Promise<RealtimeWorkspaceStatusResponse | null | undefined | void>,
  options: RealtimeWorkspaceRefreshWaitOptions = {},
) {
  const pollIntervalMs = options.pollIntervalMs ?? DEFAULT_REFRESH_STATUS_POLL_INTERVAL_MS;
  const wait = options.wait ?? sleep;
  let status = initialStatus;

  do {
    if (status.refreshing) {
      await wait(pollIntervalMs);
    }
    const latestStatus = await loadStatus();
    if (!latestStatus) {
      throw new Error('刷新状态获取失败，暂时无法确认最新数据是否已就绪');
    }
    status = latestStatus;
  } while (status.refreshing);

  if (status.status === 'FAILED' || status.status === 'UNSUPPORTED') {
    throw new Error(status.message || '刷新未完成，已展示当前可用数据');
  }

  return status;
}

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

  return {
    syncStatus,
    lastSyncedText,
    loadRealtimeStatus,
  };
}

function sleep(milliseconds: number) {
  return new Promise<void>((resolve) => window.setTimeout(resolve, milliseconds));
}
