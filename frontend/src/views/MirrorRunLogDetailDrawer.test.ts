import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MirrorRunLogDetailDrawer from './MirrorRunLogDetailDrawer.vue';
import type { SyncRunDiagnosticItem, SyncRunLog } from '../types/api';

const apiMock = vi.hoisted(() => ({
  getRunLogDetails: vi.fn(),
}));

vi.mock('../api', () => ({
  api: apiMock,
}));

function createRun(overrides: Partial<SyncRunLog> = {}): SyncRunLog {
  return {
    id: 77,
    runId: 'run-77',
    syncType: 'FULL',
    runType: 'FULL_SYNC',
    triggerType: 'MANUAL',
    status: 'SUCCESS',
    freshnessStatus: 'NOT_APPLICABLE',
    deleteReconciliationStatus: 'NOT_APPLICABLE',
    message: '完成',
    tableCount: 3,
    completedTableCount: 3,
    recordCount: 12,
    startedAt: '2026-09-30T10:00:00',
    finishedAt: '2026-09-30T10:01:00',
    errorSummary: '上游连接被拒绝',
    failureCount: 2,
    manualAttentionCount: 1,
    diagnosticCount: 2,
    eventCount: 1,
    ...overrides,
  };
}

const diagnosticItems: SyncRunDiagnosticItem[] = [
  { kind: 'TABLE_TASK', status: 'FAILED', taskId: 11, rawError: '源表扫描失败', details: { sourceTable: 'issues', taskStage: 'SCAN', rowsScanned: 120, rowsApplied: 30 } },
  { kind: 'AUTHORITATIVE_SCOPE', status: 'RETRY_WAITING', taskId: 12, scopeKey: 'sig-1', details: { childTable: 'label_links' } },
  { kind: 'FACT_BUILD', status: 'PAUSED', manualDisposition: 'REQUIRES_DECISION', taskId: 13, factType: 'ISSUE', fullBuild: true },
  { kind: 'PROJECTION', status: 'FAILED', taskId: 14, scopeType: 'ISSUE', scopeKey: 'k-1', targetGeneration: 9 },
];

function mountDrawer(run: SyncRunLog | null = createRun()) {
  return mount(MirrorRunLogDetailDrawer, {
    global: { plugins: [ElementPlus] },
    props: { modelValue: true, configId: 1, run },
  });
}

describe('MirrorRunLogDetailDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMock.getRunLogDetails.mockImplementation(
      (_configId: number | undefined, _runId: number | string, section: string) => {
        if (section === 'EVENTS') {
          return Promise.resolve({
            details: {
              runId: 77,
              section: 'EVENTS',
              offset: 0,
              limit: 20,
              total: 1,
              hasMore: false,
              items: [{ eventId: 1, eventType: 'FACT_TASK_FAILED', message: '事实任务失败', createdAt: '2026-09-30T10:00:30' }],
            },
          });
        }
        return Promise.resolve({
          details: {
            runId: 77,
            section: 'DIAGNOSTICS',
            offset: 0,
            limit: 20,
            total: 2,
            hasMore: false,
            items: diagnosticItems,
          },
        });
      },
    );
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('loads both sections and renders the run conclusion with all four diagnostic kinds', async () => {
    const wrapper = mountDrawer();
    await nextTick();
    await Promise.resolve();
    await nextTick();

    expect(apiMock.getRunLogDetails).toHaveBeenCalledWith(1, 77, 'DIAGNOSTICS', 0, 20);
    expect(apiMock.getRunLogDetails).toHaveBeenCalledWith(1, 77, 'EVENTS', 0, 20);

    const text = wrapper.text();
    expect(text).toContain('run-77');
    expect(text).toContain('镜像表任务');
    expect(text).toContain('权威范围');
    expect(text).toContain('事实构建');
    expect(text).toContain('投影发布');
    expect(text).toContain('已暂停，待人工处置');
    expect(text).toContain('需人工处置');
    expect(text).toContain('上游连接被拒绝');
    expect(text).toContain('该运行已结束，尚有 1 项人工待处理');
    wrapper.unmount();
  });

  it('keeps the original task error text copyable', async () => {
    const wrapper = mountDrawer();
    await nextTick();
    await Promise.resolve();
    await nextTick();

    expect(wrapper.text()).toContain('源表扫描失败');
    // 分类专属上下文按服务端实际键名落位，不把未识别的键丢进通用块而丢失可读标签。
    expect(wrapper.text()).toContain('来源表');
    expect(wrapper.text()).toContain('扫描行数');
    expect(wrapper.text()).toContain('120');
    expect(wrapper.text()).toContain('应用行数');
    expect(wrapper.findAll('button').some((button) => button.text().includes('复制全文'))).toBe(true);
    wrapper.unmount();
  });

  it('pages diagnostics through the same run when the total exceeds one page', async () => {
    apiMock.getRunLogDetails.mockImplementation(
      (_configId: number | undefined, _runId: number | string, section: string, offset: number) =>
        Promise.resolve({
          details: {
            runId: 77,
            section,
            offset,
            limit: 20,
            total: 40,
            hasMore: offset === 0,
            items: section === 'EVENTS' ? [] : diagnosticItems,
          },
        }),
    );

    const wrapper = mountDrawer();
    await nextTick();
    await Promise.resolve();
    await nextTick();

    const pagination = wrapper.findAll('.detail-pagination');
    expect(pagination.length).toBeGreaterThan(0);
    const secondPage = pagination[0].findAll('li').find((item) => item.text() === '2');
    expect(secondPage).toBeTruthy();
    await secondPage?.trigger('click');
    await Promise.resolve();
    await nextTick();

    expect(apiMock.getRunLogDetails).toHaveBeenCalledWith(1, 77, 'DIAGNOSTICS', 20, 20);
    wrapper.unmount();
  });

  it('states that nothing was retained when the run has no diagnostics or events', async () => {
    apiMock.getRunLogDetails.mockResolvedValue({
      details: { runId: 77, section: 'DIAGNOSTICS', offset: 0, limit: 20, total: 0, hasMore: false, items: [] },
    });

    const wrapper = mountDrawer(createRun({ diagnosticCount: 0, eventCount: 0, failureCount: 0, manualAttentionCount: 0 }));
    await nextTick();
    await Promise.resolve();
    await nextTick();

    expect(wrapper.text()).toContain('该运行没有保留可展开的故障或待处理项');
    expect(wrapper.text()).toContain('该运行没有保留相关事件');
    wrapper.unmount();
  });
});
