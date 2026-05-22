import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';
import MirrorRunMonitorPanel from './MirrorRunMonitorPanel.vue';
import type { MirrorStatusResponse, SyncRunDiagnosticsResponse, SyncRunSummary } from '../types/api';

function createTask(overrides: Partial<SyncRunSummary> = {}): SyncRunSummary {
  return {
    id: 11,
    runId: 'run-full-11',
    taskType: 'FULL',
    triggerType: 'MANUAL',
    sourceMode: 'DOCKER',
    scopeKey: 'default',
    dedupeKey: 'full-default',
    status: 'RUNNING',
    cancelRequested: false,
    pendingResync: false,
    retryCount: 0,
    ...overrides,
  };
}

function createStatus(): MirrorStatusResponse {
  return {
    config: {
      id: 1,
      name: 'GitLab default source',
      enabled: true,
      sourceEnabled: true,
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
      compensationIntervalMinutes: 360,
      syncThreadMode: 'CPU_RATIO',
      syncThreadValue: 0.8,
      maxSyncThreads: 16,
    },
    currentTask: createTask(),
    currentStatus: 'RUNNING',
    currentMessage: 'Manual full sync',
    currentStartedAt: '2026-05-15T10:00:00',
    progress: {
      phase: 'FULL_SYNC',
      runId: 'run-full-11',
      runType: 'FULL',
      status: 'RUNNING',
      totalTables: 4,
      completedTables: 1,
      runningTables: 2,
      failedTables: 0,
      dirtyTables: 1,
      syncedRecords: 2000,
      appliedRows: 1800,
      recordsPerSecond: 1200,
      estimatedRemainingSeconds: 90,
      activeTableTasks: ['issues', 'merge_requests'],
      startedAt: '2026-05-15T10:00:00',
    },
    logs: [
      {
        id: 1,
        syncType: 'INCREMENTAL',
        status: 'FAILED',
        message: 'Task heartbeat timed out',
        tableCount: 2,
        recordCount: 100,
        startedAt: '2026-05-15T09:00:00',
        finishedAt: '2026-05-15T09:05:00',
      },
      {
        id: 2,
        syncType: 'FULL',
        status: 'SUCCESS',
        message: 'Sync completed successfully',
        tableCount: 4,
        recordCount: 3000,
        startedAt: '2026-05-15T08:00:00',
        finishedAt: '2026-05-15T08:20:00',
      },
    ],
    availableProcessors: 8,
    resolvedSyncThreads: 6,
  };
}

function createDiagnostics(): SyncRunDiagnosticsResponse {
  return {
    configId: 1,
    sourceInstance: 'default',
    generatedAt: '2026-05-15T10:01:00',
    tableCount: 2,
    dirtyTableCount: 1,
    pendingTaskCount: 1,
    runningTaskCount: 1,
    retryingTaskCount: 0,
    failedTaskCount: 1,
    timedOutTaskCount: 0,
    tables: [
      {
        sourceTable: 'issues',
        mirrorTable: 'ods_gitlab_issues',
        primaryKeyColumns: 'id',
        rowStrategy: 'INCREMENTAL',
        syncEnabled: true,
        dirty: true,
        dirtyReason: 'row_count_drift',
        blockingRunId: 'run-full-11',
        latestTaskStatus: 'RUNNING',
        mirrorRows: 900,
        sourceRows: 1000,
      },
      {
        sourceTable: 'notes',
        mirrorTable: 'ods_gitlab_notes',
        primaryKeyColumns: 'id',
        rowStrategy: 'INCREMENTAL',
        syncEnabled: true,
        dirty: false,
        latestTaskStatus: 'FAILED',
        latestTaskError: 'deadlock retry exhausted',
      },
    ],
  };
}

describe('MirrorRunMonitorPanel', () => {
  it('renders run, queue, worker, dirty table, and terminal run sections', async () => {
    const wrapper = mount(MirrorRunMonitorPanel, {
      global: { plugins: [ElementPlus] },
      props: {
        status: createStatus(),
        diagnostics: createDiagnostics(),
        refreshing: false,
        cancelling: false,
        retrying: false,
      },
    });

    expect(wrapper.text()).toContain('数据镜像监控');
    expect(wrapper.text()).toContain('run-full-11');
    expect(wrapper.text()).toContain('同步线程使用');
    expect(wrapper.text()).toContain('表任务状态');
    expect(wrapper.text()).toContain('运行中的表任务');
    expect(wrapper.text()).toContain('处理批次完成数');
    expect(wrapper.text()).toContain('issues');
    expect(wrapper.text()).toContain('待修复表');
    expect(wrapper.text()).toContain('最近完成的运行');

    await wrapper.findAll('button').find((button) => button.text().includes('刷新'))!.trigger('click');
    await wrapper.findAll('button').find((button) => button.text().includes('取消'))!.trigger('click');
    await wrapper.findAll('button').find((button) => button.text().includes('重试失败任务'))!.trigger('click');
    await wrapper.findAll('button').find((button) => button.text().includes('表任务'))!.trigger('click');

    expect(wrapper.emitted('refresh')).toHaveLength(1);
    expect(wrapper.emitted('cancel')).toHaveLength(1);
    expect(wrapper.emitted('retry')).toHaveLength(1);
    expect(wrapper.emitted('openTableTasks')).toBeTruthy();
  });

  it('disables cancel and retry when no actionable run exists', () => {
    const status = createStatus();
    status.currentTask = createTask({ status: 'SUCCESS' });
    status.currentStatus = 'SUCCESS';
    status.logs = [];

    const wrapper = mount(MirrorRunMonitorPanel, {
      global: { plugins: [ElementPlus] },
      props: {
        status,
        diagnostics: createDiagnostics(),
        refreshing: false,
        cancelling: false,
        retrying: false,
      },
    });

    const cancelButton = wrapper.findAll('button').find((button) => button.text().includes('取消'));
    const retryButton = wrapper.findAll('button').find((button) => button.text().includes('重试失败任务'));
    expect(cancelButton?.attributes('disabled')).toBeDefined();
    expect(retryButton?.attributes('disabled')).toBeDefined();
  });
});
