import { ref, type Ref } from 'vue';
import type { GitlabSyncConfig, SyncSubmissionResponse } from '../types/api';

export interface MirrorSyncActionsControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  saveConfigData: (config: GitlabSyncConfig) => Promise<GitlabSyncConfig>;
  testConnectionData: () => Promise<{ success: boolean; message: string }>;
  startFullSyncData: () => Promise<SyncSubmissionResponse>;
  startIncrementalSyncData: () => Promise<SyncSubmissionResponse>;
  cancelSyncData: () => Promise<{ accepted: boolean; taskId?: number; status?: string }>;
  loadStatus: (showError: boolean, blocking: boolean) => Promise<void>;
  loadWebhookRegistration: () => void;
  notifySuccess: (message: string) => void;
  notifyWarning: (message: string) => void;
  notifyInfo: (message: string) => void;
  notifyError: (message: string) => void;
}

export function useMirrorSyncActionsController(deps: MirrorSyncActionsControllerDependencies) {
  const saving = ref(false);
  const syncing = ref(false);
  const cancelling = ref(false);

  function showSubmissionFeedback(result: SyncSubmissionResponse) {
    if (result.action === 'CREATED') {
      deps.notifySuccess(result.message);
      return;
    }
    if (result.action === 'QUEUED') {
      deps.notifyWarning(result.message);
      return;
    }
    deps.notifyInfo(result.message);
  }

  async function saveConfig(showSuccess = true) {
    saving.value = true;
    try {
      deps.form.value.enabled = deps.form.value.autoSyncEnabled;
      await deps.saveConfigData(deps.form.value);
      if (showSuccess) {
        deps.notifySuccess('配置已保存');
      }
      await deps.loadStatus(false, false);
      deps.loadWebhookRegistration();
    } catch (error) {
      deps.notifyError((error as Error).message);
      throw error;
    } finally {
      saving.value = false;
    }
  }

  async function testConnection() {
    try {
      await saveConfig(false);
      await deps.testConnectionData();
      deps.notifySuccess('连接测试成功');
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError((error as Error).message);
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
    cancelling,
    saveConfig,
    testConnection,
    startFullSync,
    startIncrementalSync,
    cancelSyncTask,
    showSubmissionFeedback,
  };
}
