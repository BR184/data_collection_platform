import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createRouter, createWebHashHistory } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DatabaseBrowserView from './DatabaseBrowserView.vue';

const mocks = vi.hoisted(() => ({
  api: {
    getDatabaseTables: vi.fn(),
    getDatabaseTableRows: vi.fn(),
    refreshDatabaseTable: vi.fn(),
  },
  message: {
    success: vi.fn(),
    info: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
}));

vi.mock('../api', () => ({ api: mocks.api }));
vi.mock('../element-plus-services', () => ({ ElMessage: mocks.message }));

function tableOption() {
  return {
    tableName: 'ods_gitlab_issues',
    label: 'GitLab issues',
    syncStatus: 'IDLE',
    lastSyncTime: null,
    tableKind: 'MIRROR',
    refreshable: true,
  };
}

function rowsResponse() {
  return {
    tableName: 'ods_gitlab_issues',
    label: 'GitLab issues',
    columns: [{ key: 'id', label: 'ID', sortable: true }],
    rows: [{ id: 1 }],
    total: 1,
    page: 1,
    size: 20,
    syncStatus: 'IDLE',
    lastSyncTime: null,
    tableKind: 'MIRROR',
    refreshable: true,
  };
}

/** 加载回路的最短展示时间，与 useRouteTableState 的默认 minLoadingMs 一致。 */
const REFRESH_MIN_DISPLAY_MS = 220;

/** 用可控时钟依次排空网络续点并跨过最短展示时间，让刷新结果在确定时刻落定。 */
async function settleRouteTableLoad() {
  await flushPromises();
  await vi.advanceTimersByTimeAsync(REFRESH_MIN_DISPLAY_MS);
  await flushPromises();
}

async function mountView() {
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/database-browser', component: DatabaseBrowserView }],
  });
  await router.push('/database-browser?table=ods_gitlab_issues');
  await router.isReady();
  const wrapper = mount(DatabaseBrowserView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('DatabaseBrowserView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
    mocks.api.getDatabaseTables.mockResolvedValue([tableOption()]);
    mocks.api.getDatabaseTableRows.mockResolvedValue(rowsResponse());
  });

  it.each([
    ['QUEUED', '刷新请求已提交'],
    ['RUNNING', '刷新请求已提交'],
    ['DEDUPED', '刷新请求已合并'],
  ])('does not report table data as refreshed when refresh request returns %s', async (status, message) => {
    // 刷新成功提示只有在网络完成并跨过最短展示时间之后才允许出现，用可控时钟显式驱动这两段等待。
    vi.useFakeTimers();
    let wrapper: ReturnType<typeof mount> | undefined;
    try {
      mocks.api.refreshDatabaseTable.mockResolvedValue({
        accepted: true,
        runId: 88,
        status,
        message: null,
        sourceTables: ['issues'],
        plannedTasks: 1,
      });
      wrapper = await mountView();

      const refreshButton = wrapper.findAll('button').find((button) => button.text().includes('重新加载表数据'));
      expect(refreshButton).toBeTruthy();
      await refreshButton!.trigger('click');
      await settleRouteTableLoad();

      expect(mocks.message.success).toHaveBeenCalledWith(expect.stringContaining(message));
      expect(mocks.message.success).not.toHaveBeenCalledWith(expect.stringContaining('当前表数据已刷新'));
    } finally {
      wrapper?.unmount();
      vi.useRealTimers();
    }
  });

  it('reports table data as refreshed only after the load commits for a SUCCESS refresh', async () => {
    vi.useFakeTimers();
    let wrapper: ReturnType<typeof mount> | undefined;
    try {
      mocks.api.refreshDatabaseTable.mockResolvedValue({
        accepted: true,
        runId: 88,
        status: 'SUCCESS',
        message: null,
        sourceTables: ['issues'],
        plannedTasks: 1,
      });
      wrapper = await mountView();

      const refreshButton = wrapper.findAll('button').find((button) => button.text().includes('重新加载表数据'));
      await refreshButton!.trigger('click');
      await settleRouteTableLoad();

      expect(mocks.message.success).toHaveBeenCalledWith('当前表数据已刷新，同步任务 #88');
    } finally {
      wrapper?.unmount();
      vi.useRealTimers();
    }
  });

  it('旧表单记录只读展示，不提供已退役的内联编辑入口', async () => {
    mocks.api.getDatabaseTables.mockResolvedValue([
      { tableName: 'collect_form_records', label: '采集表单记录', syncStatus: 'IDLE', lastSyncTime: null, tableKind: 'LOCAL', refreshable: false },
    ]);
    const wrapper = await mountCollectForm({});
    expect(wrapper.text()).toContain('原始标题');
    expect(wrapper.text()).toContain('必须保留的原备注');
    expect(wrapper.findAll('button').some((button) => button.text().includes('编辑'))).toBe(false);
    wrapper.unmount();
  });
});

function collectFormRow(overrides: Record<string, unknown>) {
  return {
    id: 7,
    form_title: '原始标题',
    reviewer: '张三',
    review_duration_minutes: 60,
    specification_score: 1,
    logic_score: 2,
    performance_score: 3,
    design_score: 4,
    other_score: 5,
    remark: '必须保留的原备注',
    deleted: false,
    gitlab_base_url: 'http://172.22.10.233',
    project_id: 88,
    request_iid: 12,
    resource_type: 'merge_request',
    resource_id: '12',
    template_code: 'code_review',
    created_at: null,
    updated_at: null,
    ...overrides,
  };
}

async function mountCollectForm(rowOverrides: Record<string, unknown>) {
  mocks.api.getDatabaseTableRows.mockResolvedValue({
    tableName: 'collect_form_records',
    label: '采集表单记录',
    columns: [
      { key: 'id', label: 'ID', sortable: true },
      { key: 'form_title', label: '表单标题', sortable: true },
      { key: 'remark', label: '备注', sortable: true },
    ],
    rows: [collectFormRow(rowOverrides)],
    total: 1,
    page: 1,
    size: 20,
    syncStatus: 'IDLE',
    lastSyncTime: null,
    tableKind: 'LOCAL',
    refreshable: false,
  });
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [{ path: '/database-browser', component: DatabaseBrowserView }],
  });
  await router.push('/database-browser?table=collect_form_records');
  await router.isReady();
  const wrapper = mount(DatabaseBrowserView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}
