import { ref, type Ref } from 'vue';
import type { GitlabSyncConfig, SyncSubmissionResponse } from '../types/api';
import type { MirrorStatusLoadOptions } from './useMirrorStatusController';

export interface MirrorSyncActionsControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  saveConfigData: (config: GitlabSyncConfig) => Promise<GitlabSyncConfig>;
  testConnectionData: () => Promise<{ success: boolean; message: string }>;
  startFullSyncData: () => Promise<SyncSubmissionResponse>;
  startIncrementalSyncData: () => Promise<SyncSubmissionResponse>;
  cancelSyncData: () => Promise<{ accepted: boolean; runId?: number; status?: string; message?: string }>;
  loadStatus: (showError: boolean, blocking: boolean, options?: MirrorStatusLoadOptions) => Promise<void>;
  loadSystemHookRegistration: () => void;
  notifySuccess: (message: string) => void;
  notifyWarning: (message: string) => void;
  notifyInfo: (message: string) => void;
  notifyError: (message: string) => void;
  hasActiveSync?: () => boolean;
}

export function useMirrorSyncActionsController(deps: MirrorSyncActionsControllerDependencies) {
  const saving = ref(false);
  const syncing = ref(false);
  const testing = ref(false);
  const cancelling = ref(false);

  function showSubmissionFeedback(result: SyncSubmissionResponse) {
    if (result.status === 'FAILED' || result.status === 'TIMEOUT' || result.status === 'CANCELLED') {
      deps.notifyError(result.message);
      return;
    }
    if (result.status === 'PARTIAL_SUCCESS') {
      deps.notifyWarning(result.message || '已完成，部分表需要查看明细');
      return;
    }
    if (result.action === 'CREATED') {
      deps.notifySuccess(result.message);
      return;
    }
    if (result.action === 'QUEUED') {
      deps.notifyInfo(result.message);
      return;
    }
    deps.notifyInfo(result.message);
  }

  async function saveConfig(showSuccess = true) {
    saving.value = true;
    try {
      deps.form.value.enabled = deps.form.value.sourceEnabled ?? deps.form.value.enabled;
      await deps.saveConfigData(deps.form.value);
      if (showSuccess) {
        if (deps.hasActiveSync?.()) {
          deps.notifyInfo('设置已保存。当前同步仍按启动时配置执行，新设置将在下一次同步生效。');
        } else {
          deps.notifySuccess('配置已保存');
        }
      }
      await deps.loadStatus(false, false, { applyRemoteConfig: true });
      deps.loadSystemHookRegistration();
    } catch (error) {
      deps.notifyError((error as Error).message);
      throw error;
    } finally {
      saving.value = false;
    }
  }

  async function testConnection() {
    if (testing.value) {
      return;
    }
    testing.value = true;
    try {
      await saveConfig(false);
      await deps.testConnectionData();
      deps.notifySuccess('连接测试成功');
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      testing.value = false;
    }
  }

  async function startFullSync() {
    syncing.value = true;
    try {
      await saveConfig(false);
      const result = await deps.startFullSyncData();
      showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      syncing.value = false;
    }
  }

  async function startIncrementalSync() {
    syncing.value = true;
    try {
      await saveConfig(false);
      const result = await deps.startIncrementalSyncData();
      showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      syncing.value = false;
    }
  }

  async function cancelSyncTask() {
    cancelling.value = true;
    try {
      const result = await deps.cancelSyncData();
      if (result.accepted) {
        deps.notifySuccess('已提交中止请求');
      } else {
        deps.notifyInfo('当前没有可中止的任务');
      }
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError((error as Error).message);
    } finally {
      cancelling.value = false;
    }
  }

  return {
    saving,
    syncing,
    testing,
    cancelling,
    saveConfig,
    testConnection,
    startFullSync,
    startIncrementalSync,
    cancelSyncTask,
    showSubmissionFeedback,
  };
}
