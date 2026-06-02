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
    updateCollectFormRecord: vi.fn(),
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
    mocks.api.refreshDatabaseTable.mockResolvedValue({
      accepted: true,
      runId: 88,
      status,
      message: null,
      sourceTables: ['issues'],
      plannedTasks: 1,
    });
    const wrapper = await mountView();

    const refreshButton = wrapper.findAll('button').find((button) => button.text().includes('重新加载表数据'));
    expect(refreshButton).toBeTruthy();
    await refreshButton!.trigger('click');
    await flushPromises();

    expect(mocks.message.success).toHaveBeenCalledWith(expect.stringContaining(message));
    expect(mocks.message.success).not.toHaveBeenCalledWith(expect.stringContaining('当前表数据已刷新'));
    wrapper.unmount();
  });
});
