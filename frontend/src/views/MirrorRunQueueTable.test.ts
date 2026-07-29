import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';
import { defineComponent, h, inject, provide } from 'vue';
import MirrorRunQueueTable from './MirrorRunQueueTable.vue';
import type { SyncRunDiagnosticsResponse, SyncRunTableDiagnostics } from '../types/api';

const tableRowsKey = Symbol('tableRows');

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
      return () => h('div', { class: 'queue-table' }, slots.default?.());
    },
  }),
  ElTableColumn: defineComponent({
    props: {
      label: String,
      prop: String,
    },
    setup(props, { slots }) {
      const rows = inject<SyncRunTableDiagnostics[]>(tableRowsKey, []);
      return () =>
        h('div', { class: 'queue-table-column' }, [
          h('span', props.label),
          ...rows.map((row) =>
            h('span', slots.default ? slots.default({ row }) : String(row[props.prop as keyof SyncRunTableDiagnostics] ?? '')),
          ),
        ]);
    },
  }),
};

function createDiagnostics(): SyncRunDiagnosticsResponse {
  return {
    configId: 1,
    sourceInstance: 'default',
    generatedAt: '2026-05-15T10:01:00',
    tableCount: 1,
    dirtyTableCount: 1,
    pendingTaskCount: 1,
    runningTaskCount: 1,
    retryingTaskCount: 0,
    failedTaskCount: 0,
    timedOutTaskCount: 0,
    historicalFailedTaskCount: 2,
    historicalTimedOutTaskCount: 1,
    tables: [
      {
        sourceTable: 'issues',
        mirrorTable: 'ods_gitlab_issues',
        primaryKeyColumns: 'id',
        rowStrategy: 'INCREMENTAL',
        syncEnabled: true,
        dirty: true,
        dirtyReason: 'row_count_drift',
        currentTaskStatus: 'RUNNING',
        currentTaskHeartbeatAt: '2026-05-15T10:06:00',
        currentTaskLeaseUntil: '2026-05-15T10:10:00',
      },
    ],
  };
}

describe('MirrorRunQueueTable', () => {
  it('renders task heartbeat and lease expiry diagnostics', () => {
    const wrapper = mount(MirrorRunQueueTable, {
      global: {
        plugins: [ElementPlus],
        stubs: tableStubs,
      },
      props: {
        diagnostics: createDiagnostics(),
      },
    });

    expect(wrapper.text()).toContain('最近心跳');
    expect(wrapper.text()).toContain('租约到期');
    expect(wrapper.text()).toContain('issues');
    expect(wrapper.text()).toContain('2026-05-15 10:06:00');
    expect(wrapper.text()).toContain('2026-05-15 10:10:00');
  });
});
