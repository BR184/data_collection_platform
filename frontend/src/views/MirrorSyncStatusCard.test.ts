import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';
import MirrorSyncStatusCard from './MirrorSyncStatusCard.vue';
import type { GitlabSyncTask, SyncProgress } from '../types/api';

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
    completedTables: 2,
    syncedRecords: 42,
    currentTable: 'issues',
    startedAt: 'invalid-start',
    ...overrides,
  };
}

describe('MirrorSyncStatusCard', () => {
  it('renders status, progress, and sync metadata', () => {
    const wrapper = mount(MirrorSyncStatusCard, {
      global: { plugins: [ElementPlus] },
      props: {
        displayStatus: { text: '执行中', type: 'warning' },
        statusMessageClass: ['status-message', 'status-message--warning'],
        currentMessageText: '手工触发的全量同步',
        phaseText: '首次全量同步',
        progressPercent: 50,
        progressHint: '正在处理表 issues，已同步 42 条记录。',
        progress: createProgress(),
        currentTask: createTask(),
        currentStartedAt: 'fallback-start',
      },
    });

    expect(wrapper.text()).toContain('同步状态');
    expect(wrapper.text()).toContain('执行中');
    expect(wrapper.text()).toContain('手工触发的全量同步');
    expect(wrapper.text()).toContain('首次全量同步');
    expect(wrapper.text()).toContain('50%');
    expect(wrapper.text()).toContain('issues');
    expect(wrapper.text()).toContain('2/4');
    expect(wrapper.text()).toContain('42');
    expect(wrapper.text()).toContain('invalid-start');
    expect(wrapper.get('.status-message').classes()).toContain('status-message--warning');
  });

  it('falls back to the current started time and idle metadata', () => {
    const wrapper = mount(MirrorSyncStatusCard, {
      global: { plugins: [ElementPlus] },
      props: {
        displayStatus: { text: '空闲', type: 'info' },
        statusMessageClass: ['status-message', 'status-message--info'],
        currentMessageText: '当前没有正在执行的同步任务。',
        phaseText: '空闲',
        progressPercent: 0,
        progressHint: '当前没有正在执行的同步任务。',
        progress: null,
        currentTask: null,
        currentStartedAt: 'fallback-start',
      },
    });

    expect(wrapper.text()).toContain('空闲');
    expect(wrapper.text()).toContain('0%');
    expect(wrapper.text()).toContain('-');
    expect(wrapper.text()).toContain('0/0');
    expect(wrapper.text()).toContain('fallback-start');
  });
});
