import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import { useMirrorStatusController } from './useMirrorStatusController';
import type { GitlabSyncConfig, MirrorStatusResponse } from '../types/api';

function createConfig(overrides: Partial<GitlabSyncConfig> = {}): GitlabSyncConfig {
  return {
    name: '',
    enabled: true,
    sourceEnabled: undefined,
    sourceInstance: 'default',
    autoSyncEnabled: true,
    sourceMode: 'DOCKER',
    whitelistMode: 'RECOMMENDED',
    whitelistTables: undefined as unknown as string[],
    dbHost: undefined as unknown as string,
    dbPort: undefined as unknown as number,
    dbName: undefined as unknown as string,
    dbUsername: undefined as unknown as string,
    dbPassword: undefined as unknown as string,
    dockerContainerName: undefined,
    webhookSecret: '',
    webhookEnabled: undefined,
    webhookProjectId: undefined,
    compensationIntervalMinutes: 10,
    ...overrides,
  };
}

function createStatus(overrides: Partial<MirrorStatusResponse> = {}): MirrorStatusResponse {
  return {
    config: createConfig(),
    currentTask: null,
    currentStatus: 'IDLE',
    currentMessage: '',
    progress: null,
    logs: [],
    systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    systemHookRegistration: null,
    webhookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    webhookRegistration: null,
    ...overrides,
  };
}

describe('useMirrorStatusController', () => {
  it('loads status and normalizes config defaults into the form', async () => {
    const form = ref(createConfig({ enabled: false, autoSyncEnabled: false }));
    const response = createStatus();
    const deps = {
      form,
      loadStatusData: vi.fn(async () => response),
      loadSystemHookRegistration: vi.fn(),
      notifyError: vi.fn(),
    };
    const controller = useMirrorStatusController(deps);

    await controller.loadStatus();

    expect(deps.loadStatusData).toHaveBeenCalledOnce();
    expect(controller.status.value).toBe(response);
    expect(controller.loading.value).toBe(false);
    expect(form.value).toMatchObject({
      name: 'GitLab default source',
      enabled: true,
      sourceEnabled: true,
      sourceInstance: 'default',
      autoSyncEnabled: true,
      sourceMode: 'DOCKER',
      whitelistTables: [],
      dockerContainerName: 'gitlab-data-web-1',
      dbHost: 'localhost',
      dbPort: 5432,
      dbName: 'gitlabhq_production',
      dbUsername: 'gitlab',
      dbPassword: '',
      webhookProjectId: null,
      webhookEnabled: false,
    });
  });

  it('refreshes status without blocking and reloads system hook registration', async () => {
    const form = ref(createConfig());
    const deps = {
      form,
      loadStatusData: vi.fn(async () => createStatus()),
      loadSystemHookRegistration: vi.fn<() => void>(),
      notifyError: vi.fn(),
    };
    const controller = useMirrorStatusController(deps);

    await controller.refreshStatus();

    expect(controller.refreshing.value).toBe(false);
    expect(controller.loading.value).toBe(false);
    expect(deps.loadSystemHookRegistration).toHaveBeenCalledOnce();
  });

  it('starts and stops running status polling', () => {
    const form = ref(createConfig());
    const intervalIds: number[] = [];
    const clearedIds: number[] = [];
    const deps = {
      form,
      loadStatusData: vi.fn(async () => createStatus()),
      loadSystemHookRegistration: vi.fn(),
      notifyError: vi.fn(),
      setInterval: vi.fn((callback: () => void) => {
        intervalIds.push(intervalIds.length + 1);
        callback();
        return intervalIds[intervalIds.length - 1];
      }),
      clearInterval: vi.fn((timerId: number) => {
        clearedIds.push(timerId);
      }),
    };
    const controller = useMirrorStatusController(deps);

    controller.syncRunningRefresh('RUNNING');
    controller.syncRunningRefresh('SUCCESS');

    expect(deps.setInterval).toHaveBeenCalledOnce();
    expect(deps.loadStatusData).toHaveBeenCalledOnce();
    expect(clearedIds).toEqual([1]);
    expect(controller.refreshTimer.value).toBeNull();
  });

  it('reports load errors only when requested', async () => {
    const form = ref(createConfig());
    const deps = {
      form,
      loadStatusData: vi.fn(async () => {
        throw new Error('Status load failed');
      }),
      loadSystemHookRegistration: vi.fn(),
      notifyError: vi.fn(),
    };
    const controller = useMirrorStatusController(deps);

    await controller.loadStatus(false);
    await controller.loadStatus(true);

    expect(deps.notifyError).toHaveBeenCalledTimes(1);
    expect(deps.notifyError).toHaveBeenCalledWith('Status load failed');
    expect(controller.loading.value).toBe(false);
  });
});
