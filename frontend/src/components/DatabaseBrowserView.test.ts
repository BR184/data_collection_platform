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

  // R01 跨层回归：数据库浏览器内联编辑器读取/回写 remark 与全部可编辑字段。补齐声明列后，
  // 五场景逐一核对完整 11 字段 payload（只覆盖用户实际编辑的标题或备注），防止评分被误写。
  describe('collect_form_records 编辑器保存契约', () => {
    // 与 collectFormRow 一致的固定输入 → 期望保存 payload（camelCase 11 字段）。
    const BASE_ROW = {
      formTitle: '原始标题',
      reviewer: '张三',
      reviewDurationMinutes: 60,
      specificationScore: 1,
      logicScore: 2,
      performanceScore: 3,
      designScore: 4,
      otherScore: 5,
      deleted: false,
    };

    beforeEach(() => {
      mocks.api.getDatabaseTables.mockResolvedValue([
        { tableName: 'collect_form_records', label: '采集表单记录', syncStatus: 'IDLE', lastSyncTime: null, tableKind: 'LOCAL', refreshable: false },
      ]);
      mocks.api.updateCollectFormRecord.mockResolvedValue(undefined);
    });

    function expectedPayload(overrides: Record<string, unknown>) {
      return { id: 7, ...BASE_ROW, remark: '必须保留的原备注', ...overrides };
    }

    async function clickSaveAndExpect(expected: Record<string, unknown>) {
      await clickDialogButton('保存修改');
      await flushPromises();
      expect(mocks.api.updateCollectFormRecord).toHaveBeenCalledTimes(1);
      // 精确比较：任何额外/缺失字段或评分被改写都会失败，不像 toMatchObject 只断言子集。
      expect(mocks.api.updateCollectFormRecord.mock.calls[0][0]).toEqual(expected);
    }

    it('只改标题并保存时保留原备注与全部评分', async () => {
      const wrapper = await mountCollectForm({});
      await openCollectFormEditor(wrapper);
      await setDialogField('.db-edit-form input', '新标题');
      await clickSaveAndExpect(expectedPayload({ formTitle: '新标题' }));
      wrapper.unmount();
    });

    it('不改动任何字段直接保存时完整 payload 与行数据一致', async () => {
      const wrapper = await mountCollectForm({});
      await openCollectFormEditor(wrapper);
      await clickSaveAndExpect(expectedPayload({}));
      wrapper.unmount();
    });

    it('主动清空备注时提交空串，其余字段保持', async () => {
      const wrapper = await mountCollectForm({});
      await openCollectFormEditor(wrapper);
      await setDialogField('.db-edit-form textarea', '');
      await clickSaveAndExpect(expectedPayload({ remark: '' }));
      wrapper.unmount();
    });

    it('原备注为 null 时沿用表单空串语义', async () => {
      const wrapper = await mountCollectForm({ remark: null });
      await openCollectFormEditor(wrapper);
      await clickSaveAndExpect(expectedPayload({ remark: '' }));
      wrapper.unmount();
    });

    it('原备注为空串时保存仍为空串', async () => {
      const wrapper = await mountCollectForm({ remark: '' });
      await openCollectFormEditor(wrapper);
      await clickSaveAndExpect(expectedPayload({ remark: '' }));
      wrapper.unmount();
    });
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

async function openCollectFormEditor(wrapper: Awaited<ReturnType<typeof mountCollectForm>>) {
  const editButton = wrapper.findAll('button').find((button) => button.text().includes('编辑'));
  expect(editButton).toBeTruthy();
  await editButton!.trigger('click');
  await flushPromises();
  await flushPromises();
}

async function setDialogField(selector: string, value: string) {
  const element = document.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement | null;
  expect(element).toBeTruthy();
  element!.value = value;
  element!.dispatchEvent(new Event('input', { bubbles: true }));
  await flushPromises();
}

async function clickDialogButton(label: string) {
  const button = Array.from(document.querySelectorAll('.el-dialog button')).find((item) => item.textContent?.includes(label));
  expect(button).toBeTruthy();
  button!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await flushPromises();
}
