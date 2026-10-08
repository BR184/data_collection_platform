import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, inject, provide } from 'vue';
import MirrorSyncLogTable from './MirrorSyncLogTable.vue';
import mirrorSyncLogTableSource from './MirrorSyncLogTable.vue?raw';
import type { SyncRunLog } from '../types/api';

const tableRowsKey = Symbol('tableRows');
const doLayoutSpy = vi.fn();

const tableStubs = {
  ElTable: defineComponent({
    props: {
      data: {
        type: Array,
        default: () => [],
      },
      maxHeight: {
        type: [Number, String],
        default: undefined,
      },
    },
    setup(props, { slots }) {
      provide(tableRowsKey, props.data);
      return () => h('div', { class: 'sync-log-table' }, slots.default?.());
    },
    methods: {
      doLayout: doLayoutSpy,
    },
  }),
  ElTableColumn: defineComponent({
    props: {
      label: String,
      prop: String,
    },
    setup(props, { slots }) {
      const rows = inject<SyncRunLog[]>(tableRowsKey, []);
      return () =>
        h('div', { class: 'sync-log-table-column' }, [
          h('span', props.label),
          ...rows.map((row) =>
            h('span', slots.default ? slots.default({ row }) : String(row[props.prop as keyof SyncRunLog] ?? '')),
          ),
        ]);
    },
  }),
};

function createLog(overrides: Partial<SyncRunLog> = {}): SyncRunLog {
  return {
    id: 1,
    syncType: 'FULL',
    runType: 'FULL_SYNC',
    triggerType: 'MANUAL',
    status: 'SUCCESS',
    freshnessStatus: 'NOT_APPLICABLE',
    deleteReconciliationStatus: 'NOT_APPLICABLE',
    message: 'Sync completed successfully',
    tableCount: 3,
    recordCount: 42,
    startedAt: 'invalid-start',
    finishedAt: null,
    ...overrides,
  };
}

describe('MirrorSyncLogTable', () => {
  beforeEach(() => {
    doLayoutSpy.mockClear();
  });

  it('renders localized sync logs', () => {
    const wrapper = mount(MirrorSyncLogTable, {
      global: {
        plugins: [ElementPlus],
        stubs: tableStubs,
      },
      props: {
        logs: [
          createLog(),
          createLog({
            id: 2,
            syncType: 'PURGE',
            status: 'FAILED',
            message: '删除全部镜像数据',
            tableCount: 5,
            recordCount: 0,
          }),
          createLog({
            id: 3,
            syncType: 'INCREMENTAL',
            runType: 'INCREMENTAL_SYNC',
            freshnessStatus: 'CAUGHT_UP',
          }),
          createLog({
            id: 4,
            syncType: 'COMPENSATION',
            runType: 'DELETE_RECONCILIATION',
            deleteReconciliationStatus: 'COMPLETED',
          }),
        ],
        refreshing: false,
      },
    });

    expect(wrapper.text()).toContain('最近同步日志');
    expect(wrapper.text()).toContain('全量同步');
    expect(wrapper.text()).toContain('删除全部镜像数据');
    expect(wrapper.text()).toContain('已完成');
    expect(wrapper.text()).toContain('需要处理');
    expect(wrapper.text()).toContain('invalid-start');
    expect(wrapper.text()).toContain('3');
    expect(wrapper.text()).toContain('42');
    expect(wrapper.text()).toContain('同步已完成');
    expect(wrapper.text()).toContain('物理删除对账');
    expect(wrapper.text()).toContain('数据已追平');
    expect(wrapper.text()).toContain('删除对账完成');
    expect(wrapper.text()).toContain('运行编号');
    expect(wrapper.text()).toContain('同步内容');
    expect(wrapper.text()).toContain('触发来源');
    expect(wrapper.text()).toContain('当前结果');
    expect(wrapper.text()).not.toContain('内部状态');
    expect(wrapper.text()).not.toContain('MANUAL');
    expect(wrapper.text()).not.toContain('FULL_SYNC');
    expect(wrapper.text()).not.toContain('SUCCESS');
    expect(wrapper.text()).not.toContain('Sync completed successfully');
    expect(wrapper.find('.sync-log-table-shell').exists()).toBe(true);
  });

  it('emits refresh when clicking the refresh button', async () => {
    const wrapper = mount(MirrorSyncLogTable, {
      global: { plugins: [ElementPlus] },
      props: {
        logs: [],
        refreshing: false,
      },
    });

    await wrapper.get('button').trigger('click');

    expect(wrapper.emitted('refresh')).toHaveLength(1);
  });

  it('recalculates layout and wakes the horizontal scrollbar after expanding a log row', async () => {
    vi.useFakeTimers();
    const wrapper = mount(MirrorSyncLogTable, {
      global: {
        plugins: [ElementPlus],
        stubs: tableStubs,
      },
      props: {
        logs: [createLog()],
        refreshing: false,
      },
    });

    await wrapper.getComponent(tableStubs.ElTable).vm.$emit('expand-change', createLog(), [createLog()]);
    await wrapper.vm.$nextTick();
    await Promise.resolve();
    await wrapper.vm.$nextTick();

    expect(doLayoutSpy).toHaveBeenCalledTimes(1);
    expect(wrapper.get('.sync-log-table-shell').classes()).toContain('is-scrollbar-awake');

    vi.advanceTimersByTime(1200);
    await wrapper.vm.$nextTick();

    expect(wrapper.get('.sync-log-table-shell').classes()).not.toContain('is-scrollbar-awake');
    vi.useRealTimers();
  });

  it('keeps the sync log scrollbar enhancement scoped to this table', () => {
    expect(mirrorSyncLogTableSource).toContain('.sync-log-table-shell :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal)');
    expect(mirrorSyncLogTableSource).toContain('.sync-log-floating-horizontal');
    expect(mirrorSyncLogTableSource).toContain('.el-table__body-wrapper .el-scrollbar__bar.is-horizontal');
    expect(mirrorSyncLogTableSource).not.toContain('max-height="280"');
    expect(mirrorSyncLogTableSource).toContain('@expand-change="handleExpandChange"');
    expect(mirrorSyncLogTableSource).toContain('tableRef.value?.doLayout?.()');
  });

  it('summarizes anomalies per run and opens the full same-run detail', async () => {
    const wrapper = mount(MirrorSyncLogTable, {
      global: {
        plugins: [ElementPlus],
        stubs: tableStubs,
      },
      props: {
        logs: [
          createLog({
            failureCount: 2,
            manualAttentionCount: 1,
            diagnosticCount: 3,
            eventCount: 7,
            diagnostics: [
              { kind: 'FACT_BUILD', taskId: 5, status: 'PAUSED', rawError: '事实构建失败' },
              { kind: 'PROJECTION', taskId: 6, status: 'FAILED', dispositionReason: '需人工决定' },
            ],
            eventTrail: [{ eventId: 9, eventType: 'FACT_TASK_FAILED', message: '任务失败', createdAt: '2026-09-30T10:00:00' }],
            latestProgressMessage: '正在构建事实',
          }),
        ],
        refreshing: false,
      },
    });

    const text = wrapper.text();
    expect(text).toContain('失败项 2');
    expect(text).toContain('待人工处置 1');
    expect(text).toContain('可查明细 3');
    expect(text).toContain('相关事件 7');
    expect(text).toContain('事实构建');
    expect(text).toContain('事实构建失败');
    expect(text).toContain('正在构建事实');

    const detailButton = wrapper.findAll('button').find((button) => button.text().includes('查看全部'));
    expect(detailButton).toBeTruthy();
    await detailButton?.trigger('click');
    expect(wrapper.emitted('openDetails')).toHaveLength(1);
    expect(wrapper.emitted('openDetails')?.[0]?.[0]).toMatchObject({ id: 1, diagnosticCount: 3 });
  });

  it('docks the horizontal bar inside the module and gives the table a vertical max-height', () => {
    // 模块内停靠：不再 Teleport 到 body、不再用视口 fixed 定位，横条作为外壳子节点随模块滚动。
    expect(mirrorSyncLogTableSource).not.toContain('<Teleport');
    expect(mirrorSyncLogTableSource).not.toContain('position: fixed');
    expect(mirrorSyncLogTableSource).toContain("positioning: 'container'");
    expect(mirrorSyncLogTableSource).toContain('padding-bottom: 18px');
    expect(mirrorSyncLogTableSource).toContain(':max-height="tableMaxHeight"');

    const wrapper = mount(MirrorSyncLogTable, {
      global: {
        plugins: [ElementPlus],
        stubs: tableStubs,
      },
      props: {
        logs: [createLog()],
        refreshing: false,
      },
    });

    expect(wrapper.find('.sync-log-table-shell .sync-log-floating-horizontal').exists()).toBe(true);
    const maxHeight = wrapper.getComponent(tableStubs.ElTable).props('maxHeight');
    expect(Number(maxHeight)).toBeGreaterThanOrEqual(220);
  });
});
