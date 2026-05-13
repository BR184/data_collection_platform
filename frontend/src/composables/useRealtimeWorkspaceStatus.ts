import { computed, ref } from 'vue';
import type { RealtimeWorkspaceStatusResponse } from '../types/api';

interface UseRealtimeWorkspaceStatusOptions {
  loadStatus: () => Promise<RealtimeWorkspaceStatusResponse>;
  emptyText?: string;
}

export function formatRealtimeLastSyncedText(lastSyncedAt: string | null | undefined, emptyText = '暂无同步记录') {
  if (!lastSyncedAt) {
    return emptyText;
  }
  return lastSyncedAt.replace('T', ' ').slice(0, 19);
}

export function useRealtimeWorkspaceStatus(options: UseRealtimeWorkspaceStatusOptions) {
  const syncStatus = ref<RealtimeWorkspaceStatusResponse | null>(null);
  const lastSyncedText = computed(() =>
    formatRealtimeLastSyncedText(syncStatus.value?.lastSyncedAt, options.emptyText),
  );

  async function loadRealtimeStatus() {
    try {
      syncStatus.value = await options.loadStatus();
      return syncStatus.value;
    } catch {
      syncStatus.value = null;
      return null;
    }
  }

  return {
    syncStatus,
    lastSyncedText,
    loadRealtimeStatus,
  };
}
