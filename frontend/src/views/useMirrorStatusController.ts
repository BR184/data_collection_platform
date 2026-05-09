import { ref, shallowRef, type Ref } from 'vue';
import type { GitlabSyncConfig, GitlabSyncStatus, MirrorStatusResponse } from '../types/api';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'CANCELLING'];

export interface MirrorStatusControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  loadStatusData: () => Promise<MirrorStatusResponse>;
  loadWebhookRegistration: () => void;
  notifyError: (message: string) => void;
  setInterval?: (callback: () => void, timeout: number) => number;
  clearInterval?: (timerId: number) => void;
}

function normalizeConfig(config: GitlabSyncConfig): GitlabSyncConfig {
  return {
    ...config,
    name: config.name || 'GitLab 默认数据源',
    enabled: config.sourceEnabled ?? config.enabled ?? true,
    sourceEnabled: config.sourceEnabled ?? config.enabled ?? true,
    sourceInstance: config.sourceInstance ?? 'default',
    autoSyncEnabled: config.autoSyncEnabled ?? true,
    sourceMode: config.sourceMode ?? 'DOCKER',
    whitelistTables: config.whitelistTables ?? [],
    dockerContainerName: config.dockerContainerName ?? 'gitlab-data-web-1',
    dbHost: config.dbHost ?? 'localhost',
    dbPort: config.dbPort ?? 5432,
    dbName: config.dbName ?? 'gitlabhq_production',
    dbUsername: config.dbUsername ?? 'gitlab',
    dbPassword: config.dbPassword ?? '',
    webhookProjectId: config.webhookProjectId ?? null,
    webhookEnabled: config.webhookEnabled ?? Boolean(config.webhookSecret),
  };
}

export function useMirrorStatusController(deps: MirrorStatusControllerDependencies) {
  const loading = ref(false);
  const refreshing = ref(false);
  const status = shallowRef<MirrorStatusResponse | null>(null);
  const refreshTimer = ref<number | null>(null);
  const setTimer = deps.setInterval ?? ((callback, timeout) => window.setInterval(callback, timeout));
  const clearTimer = deps.clearInterval ?? ((timerId) => window.clearInterval(timerId));

  async function loadStatus(showError = true, blocking = true) {
    loading.value = blocking;
    try {
      const data = await deps.loadStatusData();
      status.value = data;
      deps.form.value = normalizeConfig(data.config);
    } catch (error) {
      if (showError) {
        deps.notifyError((error as Error).message);
      }
    } finally {
      loading.value = false;
    }
  }

  async function refreshStatus() {
    refreshing.value = true;
    try {
      await loadStatus(false, false);
      deps.loadWebhookRegistration();
    } finally {
      refreshing.value = false;
    }
  }

  function stopRunningRefresh() {
    if (refreshTimer.value != null) {
      clearTimer(refreshTimer.value);
      refreshTimer.value = null;
    }
  }

  function startRunningRefresh() {
    stopRunningRefresh();
    refreshTimer.value = setTimer(() => {
      void loadStatus(false, false);
    }, 2000);
  }

  function syncRunningRefresh(nextStatus: GitlabSyncStatus | null | undefined) {
    if (nextStatus && ACTIVE_POLLING_STATUSES.includes(nextStatus)) {
      startRunningRefresh();
    } else {
      stopRunningRefresh();
    }
  }

  return {
    loading,
    refreshing,
    status,
    refreshTimer,
    loadStatus,
    refreshStatus,
    startRunningRefresh,
    stopRunningRefresh,
    syncRunningRefresh,
  };
}
