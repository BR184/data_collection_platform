import { ref, shallowRef, type Ref } from 'vue';
import type { GitlabSyncConfig, GitlabSyncStatus, MirrorStatusResponse } from '../types/api';

const ACTIVE_POLLING_STATUSES: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'CANCELLING'];

export interface MirrorStatusControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  loadStatusData: () => Promise<MirrorStatusResponse>;
  loadSystemHookRegistration: () => void;
  notifyError: (message: string) => void;
  setInterval?: (callback: () => void, timeout: number) => number;
  clearInterval?: (timerId: number) => void;
}

export interface MirrorStatusLoadOptions {
  applyRemoteConfig?: boolean;
}

function normalizeConfig(config: GitlabSyncConfig): GitlabSyncConfig {
  return {
    ...config,
    name: config.name || 'GitLab default source',
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
    systemHookProjectId: config.systemHookProjectId ?? null,
    systemHookEnabled: config.systemHookEnabled ?? Boolean(config.systemHookSecret),
    syncThreadMode: config.syncThreadMode ?? 'FIXED',
    syncThreadValue: config.syncThreadValue ?? (config.syncThreadMode === 'CPU_RATIO' ? 0.8 : 2),
    maxSyncThreads: config.maxSyncThreads ?? 16,
  };
}

export function useMirrorStatusController(deps: MirrorStatusControllerDependencies) {
  const loading = ref(false);
  const refreshing = ref(false);
  const status = shallowRef<MirrorStatusResponse | null>(null);
  const refreshTimer = ref<number | null>(null);
  const lastAppliedFormSnapshot = ref('');
  const setTimer = deps.setInterval ?? ((callback, timeout) => window.setInterval(callback, timeout));
  const clearTimer = deps.clearInterval ?? ((timerId) => window.clearInterval(timerId));

  function getConfigSnapshot(config: GitlabSyncConfig) {
    return JSON.stringify(normalizeConfig(config));
  }

  function hasUnsavedFormChanges() {
    return lastAppliedFormSnapshot.value !== '' && getConfigSnapshot(deps.form.value) !== lastAppliedFormSnapshot.value;
  }

  function applyRemoteConfig(config: GitlabSyncConfig) {
    const normalizedConfig = normalizeConfig(config);
    deps.form.value = normalizedConfig;
    lastAppliedFormSnapshot.value = getConfigSnapshot(normalizedConfig);
  }

  async function loadStatus(showError = true, blocking = true, options: MirrorStatusLoadOptions = {}) {
    loading.value = blocking;
    try {
      const data = await deps.loadStatusData();
      status.value = data;
      if (options.applyRemoteConfig === true || blocking || !hasUnsavedFormChanges()) {
        applyRemoteConfig(data.config);
      }
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
      deps.loadSystemHookRegistration();
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
    }, 1500);
  }

  function startIdleRefresh() {
    stopRunningRefresh();
    refreshTimer.value = setTimer(() => {
      void loadStatus(false, false);
    }, 3000);
  }

  function syncRunningRefresh(nextStatus: GitlabSyncStatus | null | undefined) {
    if (nextStatus && ACTIVE_POLLING_STATUSES.includes(nextStatus)) {
      startRunningRefresh();
    } else {
      startIdleRefresh();
    }
  }

  return {
    loading,
    refreshing,
    status,
    refreshTimer,
    loadStatus,
    refreshStatus,
    startIdleRefresh,
    startRunningRefresh,
    stopRunningRefresh,
    syncRunningRefresh,
  };
}
