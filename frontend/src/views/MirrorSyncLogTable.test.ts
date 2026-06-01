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
    expect(mirrorSyncLogTableSource).toContain('.sync-log-table-shell.is-scrollbar-awake');
    expect(mirrorSyncLogTableSource).toContain('.sync-log-floating-horizontal');
    expect(mirrorSyncLogTableSource).toContain('.el-table__body-wrapper .el-scrollbar__bar.is-horizontal');
    expect(mirrorSyncLogTableSource).not.toContain('max-height="280"');
    expect(mirrorSyncLogTableSource).toContain('@expand-change="handleExpandChange"');
    expect(mirrorSyncLogTableSource).toContain('tableRef.value?.doLayout?.()');
  });
});
