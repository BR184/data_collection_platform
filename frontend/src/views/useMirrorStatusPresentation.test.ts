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
      systemHookSecret: '',
      systemHookProjectId: null,
      compensationIntervalMinutes: 10,
    },
    currentStatus: 'IDLE',
    currentMessage: '',
    currentTask: null,
    progress: null,
    logs: [],
    systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    systemHookRegistration: null,
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
    expect(presentation.lastSyncDisplay.value).toBe('最近同步：暂无');
    expect(presentation.progressPercent.value).toBe(0);
    expect(presentation.displayStatus.value).toEqual({ text: '空闲', type: 'info' });
    expect(presentation.statusMessageClass.value).toEqual(['status-message', 'status-message--info']);
    expect(presentation.phaseText.value).toBe('空闲');
    expect(presentation.progressHint.value).toBe('当前没有正在执行的同步任务。');
    expect(presentation.currentMessageText.value).toBe('当前没有正在执行的同步任务。');
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
    expect(presentation.displayStatus.value).toEqual({ text: '执行中', type: 'warning' });
    expect(presentation.phaseText.value).toBe('全量同步');
    expect(presentation.progressHint.value).toBe('正在处理表 issues，已同步 20 条记录。');
    expect(presentation.currentMessageText.value).toBe('手动全量同步');
  });

  it('uses recent log status when the current status is idle', () => {
    const presentation = useMirrorStatusPresentation(ref(createStatus({ logs: [createLog()] })));

    expect(presentation.latestLog.value?.id).toBe(1);
    expect(presentation.lastSyncDisplay.value).toBe('最近同步：invalid-start（成功）');
    expect(presentation.displayStatus.value).toEqual({ text: '最近同步成功', type: 'success' });
  });

  it('shows completed progress without preparing text', () => {
    const status = ref(
      createStatus({
        currentStatus: 'SUCCESS',
        currentMessage: 'Full table verification completed with status SUCCESS',
        currentTask: createTask({ status: 'SUCCESS' }),
        progress: createProgress({ totalTables: 4, completedTables: 4, syncedRecords: 35, currentTable: null }),
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.progressPercent.value).toBe(100);
    expect(presentation.progressHint.value).toBe('同步已完成，本次写入 35 条记录。');
    expect(presentation.currentMessageText.value).toBe('全量表校验已完成，状态：成功');
  });

  it('shows queued and zero-table progress hints', () => {
    const status = ref(
      createStatus({
        currentStatus: 'QUEUED',
        currentTask: createTask({ status: 'QUEUED' }),
        progress: createProgress({ phase: 'INCREMENTAL_SYNC', totalTables: 0, completedTables: 0, currentTable: null }),
      }),
    );

    const presentation = useMirrorStatusPresentation(status);

    expect(presentation.progressPercent.value).toBe(5);
    expect(presentation.phaseText.value).toBe('增量同步');
    expect(presentation.progressHint.value).toBe('同步任务已开始，正在准备表扫描。');
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
    expect(presentation.phaseText.value).toBe('全量同步');
    expect(presentation.progressHint.value).toBe('同步任务已开始，正在准备扫描和进度信息。');
    expect(presentation.currentMessageText.value).toBe('同步任务已开始，正在收集最新状态。');
  });
});
