import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { useMirrorSyncActionsController } from './useMirrorSyncActionsController';
import type { GitlabSyncConfig, SyncSubmissionResponse } from '../types/api';

function createConfig(overrides: Partial<GitlabSyncConfig> = {}): GitlabSyncConfig {
  return {
    name: 'GitLab 默认数据源',
    enabled: true,
    autoSyncEnabled: true,
    sourceMode: 'DOCKER',
    whitelistMode: 'RECOMMENDED',
    whitelistTables: [],
    dbHost: 'localhost',
    dbPort: 5432,
    dbName: 'gitlabhq_production',
    dbUsername: 'gitlab',
    dbPassword: '',
    dockerContainerName: 'gitlab-data-web-1',
    webhookSecret: '',
    webhookProjectId: null,
    compensationIntervalMinutes: 10,
    ...overrides,
  };
}

function createSubmission(action: SyncSubmissionResponse['action'] = 'CREATED'): SyncSubmissionResponse {
  return {
    accepted: true,
    taskId: 1,
    status: action === 'CREATED' ? 'RUNNING' : 'QUEUED',
    action,
    message: `${action} message`,
  };
}

function setup(config: GitlabSyncConfig = createConfig()) {
  return {
    form: ref(config),
    saveConfigData: vi.fn<(config: GitlabSyncConfig) => Promise<GitlabSyncConfig>>((nextConfig) =>
      Promise.resolve(nextConfig),
    ),
    testConnectionData: vi.fn<() => Promise<{ success: boolean; message: string }>>(() =>
      Promise.resolve({ success: true, message: 'ok' }),
    ),
    startFullSyncData: vi.fn<() => Promise<SyncSubmissionResponse>>(() =>
      Promise.resolve(createSubmission('CREATED')),
    ),
    startIncrementalSyncData: vi.fn<() => Promise<SyncSubmissionResponse>>(() =>
      Promise.resolve(createSubmission('QUEUED')),
    ),
    cancelSyncData: vi.fn<() => Promise<{ accepted: boolean; taskId?: number; status?: string }>>(() =>
      Promise.resolve({ accepted: true, taskId: 1, status: 'CANCELLING' }),
    ),
    loadStatus: vi.fn<(showError: boolean, blocking: boolean) => Promise<void>>(() => Promise.resolve()),
    loadWebhookRegistration: vi.fn<() => void>(),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyWarning: vi.fn<(message: string) => void>(),
    notifyInfo: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useMirrorSyncActionsController', () => {
  it('saves config after syncing enabled from auto sync and refreshes dependent state', async () => {
    const deps = setup(createConfig({ enabled: true, autoSyncEnabled: false }));
    const controller = useMirrorSyncActionsController(deps);

    await controller.saveConfig();

    expect(deps.form.value.enabled).toBe(false);
    expect(deps.saveConfigData).toHaveBeenCalledWith(deps.form.value);
    expect(deps.notifySuccess).toHaveBeenCalledWith('配置已保存');
    expect(deps.loadStatus).toHaveBeenCalledWith(false, false);
    expect(deps.loadWebhookRegistration).toHaveBeenCalledOnce();
    expect(controller.saving.value).toBe(false);
  });

  it('tests connection through a silent save and refreshes status', async () => {
    const deps = setup();
    const controller = useMirrorSyncActionsController(deps);

    await controller.testConnection();

    expect(deps.saveConfigData).toHaveBeenCalledOnce();
    expect(deps.testConnectionData).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('连接测试成功');
    expect(deps.loadStatus).toHaveBeenCalledTimes(2);
  });

  it('starts full and incremental syncs with submission feedback', async () => {
    const deps = setup();
    const controller = useMirrorSyncActionsController(deps);

    await controller.startFullSync();
    await controller.startIncrementalSync();

    expect(deps.startFullSyncData).toHaveBeenCalledOnce();
    expect(deps.startIncrementalSyncData).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('CREATED message');
    expect(deps.notifyWarning).toHaveBeenCalledWith('QUEUED message');
    expect(controller.syncing.value).toBe(false);
  });

  it('cancels sync tasks and reports whether a task was accepted', async () => {
    const deps = setup();
    const controller = useMirrorSyncActionsController(deps);

    await controller.cancelSyncTask();
    deps.cancelSyncData.mockResolvedValueOnce({ accepted: false });
    await controller.cancelSyncTask();

    expect(deps.notifySuccess).toHaveBeenCalledWith('已提交中止请求');
    expect(deps.notifyInfo).toHaveBeenCalledWith('当前没有可中止的任务');
    expect(deps.loadStatus).toHaveBeenCalledWith(false, false);
    expect(controller.cancelling.value).toBe(false);
  });
});
