import { ref, type Ref } from 'vue';
import type {
  GitlabSourceHealthResponse,
  SyncRunDiagnosticsResponse,
  SyncSubmissionResponse,
} from '../types/api';
import { getErrorMessage } from '../utils/user-message';

export interface MirrorDiagnosticsControllerDependencies {
  selectedConfigId: Readonly<Ref<number | undefined>>;
  sourceHealth: Ref<GitlabSourceHealthResponse[]>;
  tableSyncDiagnostics: Ref<SyncRunDiagnosticsResponse | null>;
  getSourceHealth: () => Promise<GitlabSourceHealthResponse[]>;
  getTableSyncDiagnostics: (configId?: number) => Promise<SyncRunDiagnosticsResponse>;
  retryFailedSync: (configId?: number) => Promise<SyncSubmissionResponse>;
  showSubmissionFeedback: (result: SyncSubmissionResponse) => void;
  loadStatus: (showError: boolean, blocking: boolean) => Promise<void>;
  loadSystemHookRegistration: (showError?: boolean) => Promise<void>;
  hasUnsavedChanges: () => boolean;
  actionDisabled: () => boolean;
  notifyWarning: (message: string) => void;
  notifyError: (message: string) => void;
}

/**
 * 管理镜像设置页的延迟诊断加载、表级诊断状态和失败任务重试。
 */
export function useMirrorDiagnosticsController(deps: MirrorDiagnosticsControllerDependencies) {
  const tableSyncDiagnosticsLoading = ref(false);
  const retryingFailedRun = ref(false);

  async function loadMirrorSection(sectionName: string, loader: () => Promise<void>) {
    try {
      await loader();
    } catch (error) {
      console.warn(`${sectionName} 加载失败`, error);
    }
  }

  async function loadDeferredMirrorSections() {
    await Promise.all([
      loadMirrorSection('数据源健康状态', loadSourceHealth),
      loadMirrorSection('表级同步诊断', () => loadTableSyncDiagnostics(false)),
      loadMirrorSection('System Hook 状态', () => deps.loadSystemHookRegistration(false)),
    ]);
  }

  async function loadSourceHealth() {
    const healthItems = await deps.getSourceHealth();
    deps.sourceHealth.value = Array.isArray(healthItems) ? healthItems : [];
  }

  async function loadTableSyncDiagnostics(showError = false) {
    const configId = deps.selectedConfigId.value;
    if (configId == null) {
      deps.tableSyncDiagnostics.value = null;
      return;
    }
    tableSyncDiagnosticsLoading.value = true;
    try {
      deps.tableSyncDiagnostics.value = await deps.getTableSyncDiagnostics(configId);
    } catch (error) {
      deps.tableSyncDiagnostics.value = null;
      if (showError) {
        deps.notifyError(getErrorMessage(error, '加载表级同步诊断失败'));
      }
    } finally {
      tableSyncDiagnosticsLoading.value = false;
    }
  }

  async function retryFailedRun() {
    if (deps.actionDisabled() || retryingFailedRun.value) {
      return;
    }
    if (deps.hasUnsavedChanges()) {
      deps.notifyWarning('当前设置尚未保存，请先保存配置后再重试同步任务。');
      return;
    }
    retryingFailedRun.value = true;
    try {
      const result = await deps.retryFailedSync(deps.selectedConfigId.value);
      deps.showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
      await Promise.all([
        loadMirrorSection('数据源健康状态', loadSourceHealth),
        loadMirrorSection('表级同步诊断', () => loadTableSyncDiagnostics(false)),
      ]);
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '同步任务重试失败'));
    } finally {
      retryingFailedRun.value = false;
    }
  }

  return {
    tableSyncDiagnosticsLoading,
    retryingFailedRun,
    loadMirrorSection,
    loadDeferredMirrorSections,
    loadSourceHealth,
    loadTableSyncDiagnostics,
    retryFailedRun,
  };
}
