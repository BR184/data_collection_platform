import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { useMirrorStatusPresentation } from './useMirrorStatusPresentation';
import type { GitlabSyncLog, GitlabSyncTask, MirrorStatusResponse, SyncProgress } from '../types/api';

function createLog(overrides: Partial<GitlabSyncLog> = {}): GitlabSyncLog {
  return {
    id: 1,
    syncType: 'FULL',
    status: 'SUCCESS',
    message: 'Sync completed successfully',
    tableCount: 3,
    recordCount: 12,
    startedAt: 'invalid-start',
    finishedAt: null,
    ...overrides,
  };
}

function createTask(overrides: Partial<GitlabSyncTask> = {}): GitlabSyncTask {
  return {
    id: 1,
    runId: 'run-1',
    taskType: 'FULL',
    triggerType: 'MANUAL',
    sourceMode: 'DOCKER',
    scopeKey: 'default',
    dedupeKey: 'full',
    status: 'RUNNING',
    cancelRequested: false,
    pendingResync: false,
    retryCount: 0,
    ...overrides,
  };
}

function createProgress(overrides: Partial<SyncProgress> = {}): SyncProgress {
  return {
    phase: 'FULL_SYNC',
    totalTables: 4,
    completedTables: 1,
    syncedRecords: 20,
    currentTable: 'issues',
    startedAt: 'invalid-progress-start',
    ...overrides,
  };
}

function createStatus(overrides: Partial<MirrorStatusResponse> = {}): MirrorStatusResponse {
  return {
    config: {
      name: 'GitLab default source',
      enabled: true,
      sourceInstance: 'default',
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
    },
    currentStatus: 'IDLE',
    currentMessage: '',
    currentTask: null,
    progress: null,
    logs: [],
    systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    systemHookRegistration: null,
    webhookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    webhookRegistration: null,
    ...overrides,
  };
}

describe('useMirrorStatusPresentation', () => {
  it('exposes idle defaults when status has not loaded', () => {
    const presentation = useMirrorStatusPresentation(ref(null));

    expect(presentation.currentTask.value).toBeNull();
    expect(presentation.recentLogs.value).toEqual([]);
    expect(presentation.latestLog.value).toBeNull();
    expect(presentation.canCancel.value).toBe(false);
    expect(presentation.lastSyncDisplay.value).toBe('Last activity: none');
    expect(presentation.progressPercent.value).toBe(0);
    expect(presentation.displayStatus.value).toEqual({ text: 'Idle', type: 'info' });
    expect(presentation.statusMessageClass.value).toEqual(['status-message', 'status-message--info']);
    expect(presentation.phaseText.value).toBe('Idle');
    expect(presentation.progressHint.value).toBe('There is no sync task running right now.');
    expect(presentation.currentMessageText.value).toBe('There is no sync task running right now.');
  });

  it('derives running progress, cancel state, and translated current message', () => {
    const status = ref(
      createStatus({
        currentStatus: 'RUNNING',
        currentMessage: 'Manual full sync',
        currentTask: createTask(),
        progress: createProgress(),
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.progressPercent.value).toBe(25);
    expect(presentation.canCancel.value).toBe(true);
    expect(presentation.displayStatus.value).toEqual({ text: 'Running', type: 'warning' });
    expect(presentation.phaseText.value).toBe('Initial full sync');
    expect(presentation.progressHint.value).toBe('Processing table issues; synced 20 records so far.');
    expect(presentation.currentMessageText.value).toBe('Manual full sync');
  });

  it('uses recent log status when the current status is idle', () => {
    const status = ref(
      createStatus({
        logs: [createLog()],
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.latestLog.value?.id).toBe(1);
    expect(presentation.lastSyncDisplay.value).toBe('Last activity: invalid-start (Success)');
    expect(presentation.displayStatus.value).toEqual({ text: 'Last activity Success', type: 'success' });
  });

  it('shows queued and zero-table progress hints', () => {
    const status = ref(
      createStatus({
        currentTask: createTask({ status: 'QUEUED' }),
        progress: createProgress({ phase: 'INCREMENTAL_SYNC', totalTables: 0, completedTables: 0, currentTable: null }),
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.progressPercent.value).toBe(5);
    expect(presentation.phaseText.value).toBe('Incremental sync');
    expect(presentation.progressHint.value).toBe('The sync task has started and is preparing the table scan.');
  });

  it('keeps active tasks visible before detailed progress is available', () => {
    const status = ref(
      createStatus({
        currentStatus: 'RUNNING',
        currentMessage: '',
        currentTask: createTask({ status: 'RUNNING', taskType: 'FULL' }),
        progress: null,
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.progressPercent.value).toBe(5);
    expect(presentation.phaseText.value).toBe('Initial full sync');
    expect(presentation.progressHint.value).toBe('The sync task has started and is preparing scan and progress details.');
    expect(presentation.currentMessageText.value).toBe('The sync task has started and the latest status is being collected.');
  });
});
