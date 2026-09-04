import { describe, expect, it, vi } from 'vitest';
import { ref } from 'vue';
import type {
  GitlabSourceHealthResponse,
  SyncRunDiagnosticsResponse,
  SyncSubmissionResponse,
} from '../types/api';
import type { MirrorDiagnosticsControllerDependencies } from './useMirrorDiagnosticsController';
import { useMirrorDiagnosticsController } from './useMirrorDiagnosticsController';

function createHealth(): GitlabSourceHealthResponse {
  return {
    configId: 1,
    name: 'GitLab default source',
    sourceInstance: 'default',
    enabled: true,
    currentStatus: 'IDLE',
    currentMessage: null,
    latestLogStatus: null,
    latestLogMessage: null,
    latestLogFinishedAt: null,
    registeredMirrorTables: 1,
    existingMirrorTables: 1,
    factLayerLagging: false,
    factLayerMessage: null,
    latestFactUpdatedAt: null,
    mergeRequestFactLagging: false,
    issueFactLagging: false,
    mergeRequestFactCount: 1,
    issueFactCount: 1,
    missingRequiredMirrorTables: [],
  };
}

function createDiagnostics(): SyncRunDiagnosticsResponse {
  return {
    configId: 1,
    sourceInstance: 'default',
    generatedAt: '2026-08-24T10:00:00',
    tableCount: 1,
    dirtyTableCount: 0,
    pendingTaskCount: 0,
    runningTaskCount: 0,
    retryingTaskCount: 0,
    failedTaskCount: 0,
    timedOutTaskCount: 0,
    historicalFailedTaskCount: 0,
    historicalTimedOutTaskCount: 0,
    tables: [],
  };
}

function createSubmission(): SyncSubmissionResponse {
  return {
    accepted: true,
    runId: 42,
    status: 'QUEUED',
    action: 'CREATED',
    message: '同步任务已提交',
  };
}

function createDependencies(
  overrides: Partial<MirrorDiagnosticsControllerDependencies> = {},
): MirrorDiagnosticsControllerDependencies {
  return {
    selectedConfigId: ref(1),
    sourceHealth: ref([]),
    tableSyncDiagnostics: ref(null),
    getSourceHealth: vi.fn(async () => [createHealth()]),
    getTableSyncDiagnostics: vi.fn(async () => createDiagnostics()),
    retryFailedSync: vi.fn(async () => createSubmission()),
    showSubmissionFeedback: vi.fn(),
    loadStatus: vi.fn(async () => {}),
    loadSystemHookRegistration: vi.fn(async () => {}),
    hasUnsavedChanges: vi.fn(() => false),
    actionDisabled: vi.fn(() => false),
    notifyWarning: vi.fn(),
    notifyError: vi.fn(),
    ...overrides,
  };
}

describe('useMirrorDiagnosticsController', () => {
  it('loads deferred diagnostics independently and updates the bound refs', async () => {
    const deps = createDependencies();
    const controller = useMirrorDiagnosticsController(deps);

    await controller.loadDeferredMirrorSections();

    expect(deps.sourceHealth.value).toEqual([createHealth()]);
    expect(deps.tableSyncDiagnostics.value).toEqual(createDiagnostics());
    expect(deps.loadSystemHookRegistration).toHaveBeenCalledWith(false);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('clears diagnostics without requesting a table when no source is selected', async () => {
    const deps = createDependencies({ selectedConfigId: ref(undefined) });
    deps.tableSyncDiagnostics.value = createDiagnostics();
    const controller = useMirrorDiagnosticsController(deps);

    await controller.loadTableSyncDiagnostics();

    expect(deps.tableSyncDiagnostics.value).toBeNull();
    expect(deps.getTableSyncDiagnostics).not.toHaveBeenCalled();
    expect(controller.tableSyncDiagnosticsLoading.value).toBe(false);
  });

  it('reports table diagnostic errors only when explicitly requested', async () => {
    const deps = createDependencies({
      getTableSyncDiagnostics: vi.fn(async () => {
        throw new Error('diagnostic unavailable');
      }),
    });
    const controller = useMirrorDiagnosticsController(deps);

    await controller.loadTableSyncDiagnostics();
    expect(deps.notifyError).not.toHaveBeenCalled();
    await controller.loadTableSyncDiagnostics(true);

    expect(deps.tableSyncDiagnostics.value).toBeNull();
    expect(deps.notifyError).toHaveBeenCalledWith('diagnostic unavailable');
    expect(controller.tableSyncDiagnosticsLoading.value).toBe(false);
  });

  it('guards retries and refreshes status and diagnostics after a successful retry', async () => {
    const deps = createDependencies();
    const controller = useMirrorDiagnosticsController(deps);

    await controller.retryFailedRun();

    expect(deps.retryFailedSync).toHaveBeenCalledWith(1);
    expect(deps.showSubmissionFeedback).toHaveBeenCalledWith(createSubmission());
    expect(deps.loadStatus).toHaveBeenCalledWith(false, false);
    expect(deps.getSourceHealth).toHaveBeenCalledTimes(1);
    expect(deps.getTableSyncDiagnostics).toHaveBeenCalledWith(1);
    expect(controller.retryingFailedRun.value).toBe(false);

    const unsavedDeps = createDependencies({ hasUnsavedChanges: vi.fn(() => true) });
    const unsavedController = useMirrorDiagnosticsController(unsavedDeps);
    await unsavedController.retryFailedRun();
    expect(unsavedDeps.retryFailedSync).not.toHaveBeenCalled();
    expect(unsavedDeps.notifyWarning).toHaveBeenCalledWith('当前设置尚未保存，请先保存配置后再重试同步任务。');
  });
});
