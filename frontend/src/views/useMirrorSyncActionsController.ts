import { ref, type Ref } from 'vue';
import { ElMessageBox } from '../element-plus-services';
import type { GitlabSyncConfig, SyncSubmissionResponse } from '../types/api';
import type { MirrorStatusLoadOptions } from './useMirrorStatusController';
import { getErrorMessage } from '../utils/user-message';

export interface MirrorSyncActionsControllerDependencies {
  form: Ref<GitlabSyncConfig>;
  saveConfigData: (config: GitlabSyncConfig) => Promise<GitlabSyncConfig>;
  testConnectionData: () => Promise<{ success: boolean; message: string }>;
  startFullSyncData: () => Promise<SyncSubmissionResponse>;
  startIncrementalSyncData: () => Promise<SyncSubmissionResponse>;
  startFullCompensationSyncData: () => Promise<SyncSubmissionResponse>;
  cancelSyncData: () => Promise<{ accepted: boolean; runId?: number; status?: string; message?: string }>;
  loadStatus: (showError: boolean, blocking: boolean, options?: MirrorStatusLoadOptions) => Promise<void>;
  loadSystemHookRegistration: () => void;
  notifySuccess: (message: string) => void;
  notifyWarning: (message: string) => void;
  notifyInfo: (message: string) => void;
  notifyError: (message: string) => void;
  hasActiveSync?: () => boolean;
  hasUnsavedChanges?: () => boolean;
}

export function useMirrorSyncActionsController(deps: MirrorSyncActionsControllerDependencies) {
  const saving = ref(false);
  const syncing = ref(false);
  const testing = ref(false);
  const cancelling = ref(false);

  function canSubmitSync() {
    if (!deps.hasUnsavedChanges?.()) {
      return true;
    }
    deps.notifyWarning('当前设置尚未保存，请先保存配置后再提交同步任务。');
    return false;
  }

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
      deps.notifyError(getErrorMessage(error, '镜像配置保存失败'));
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
      deps.notifyError(getErrorMessage(error, '连接测试失败'));
    } finally {
      testing.value = false;
    }
  }

  async function startFullSync() {
    syncing.value = true;
    try {
      if (!canSubmitSync()) {
        return;
      }
      const confirmed = await confirmHeavySync(
        '首次全量同步会按当前白名单重新读取源库数据，耗时和资源占用通常高于增量刷新。确认现在提交？',
        '确认首次全量同步',
      );
      if (!confirmed) {
        return;
      }
      const result = await deps.startFullSyncData();
      showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '全量同步提交失败'));
    } finally {
      syncing.value = false;
    }
  }

  async function startIncrementalSync() {
    syncing.value = true;
    try {
      if (!canSubmitSync()) {
        return;
      }
      const result = await deps.startIncrementalSyncData();
      showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '增量同步提交失败'));
    } finally {
      syncing.value = false;
    }
  }

  async function startFullCompensationSync() {
    syncing.value = true;
    try {
      if (!canSubmitSync()) {
        return;
      }
      const confirmed = await confirmHeavySync(
        '全量补偿对账会对源库和镜像库做完整差异校验，可能耗时较长。建议在业务低峰执行，确认现在提交？',
        '确认全量补偿对账',
      );
      if (!confirmed) {
        return;
      }
      const result = await deps.startFullCompensationSyncData();
      showSubmissionFeedback(result);
      await deps.loadStatus(false, false);
    } catch (error) {
      deps.notifyError(getErrorMessage(error, '全量补偿对账提交失败'));
    } finally {
      syncing.value = false;
    }
  }

  async function confirmHeavySync(message: string, title: string) {
    try {
      await ElMessageBox.confirm(message, title, {
        type: 'warning',
        confirmButtonText: '确认提交',
        cancelButtonText: '取消',
      });
      return true;
    } catch {
      return false;
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
      deps.notifyError(getErrorMessage(error, '中止同步任务失败'));
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
    startFullCompensationSync,
    cancelSyncTask,
    showSubmissionFeedback,
  };
}
