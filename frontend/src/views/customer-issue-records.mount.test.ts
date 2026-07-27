import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { createRouter, createWebHashHistory, type Router } from 'vue-router';
import ElementPlus from 'element-plus';
import { api } from '../api';
import type {
  CustomerIssueRecordFilterOptionsResponse,
  CustomerIssueRecordListResponse,
  RealtimeWorkspaceStatusResponse,
} from '../types/api';
import CustomerIssueRecordsView from './CustomerIssueRecordsView.vue';

const emptyFilterOptions: CustomerIssueRecordFilterOptionsResponse = {
  projectNames: [],
  moduleNames: [],
  functionNames: [],
  customerNames: [],
  reasonCategories: [],
  severityLevels: [],
  priorityLevels: [],
  issueStates: [],
  bugStatuses: [],
  categories: [],
  authorNames: [],
  handlerNames: [],
  assigneeNames: [],
  testingPhases: [],
  fixUsers: [],
  delayCauses: [],
  milestoneTitles: [],
};

const availableStatus: RealtimeWorkspaceStatusResponse = {
  workspaceKey: 'customer-issue-records',
  supported: true,
  status: 'SUCCESS',
  message: '',
  refreshing: false,
  lastSyncedAt: '2026-07-23T08:00:00Z',
};

function recordsResponse(title: string): CustomerIssueRecordListResponse {
  return {
    records: [{
      issueId: 1,
      issueIid: 101,
      projectId: 325,
      projectName: 'CC_PRODUCT',
      title,
      customerNames: '示例客户',
      issueState: 'opened',
      severityLevel: '一般',
      priorityLevel: 'P2',
      bugStatus: '处理中',
      category: '缺陷',
      reasonCategory: '代码问题',
      milestoneTitle: 'CC2026R4',
      authorName: '提交人',
      handlerName: '处理人',
      assigneeName: '指派人',
      testingPhase: '新增需求',
      fixUser: '修复人',
      moduleNames: '装配',
      functionName: '功能示例',
      delayIssue: false,
      delayReason: '',
      delayCause: '',
      responseDelayed: false,
      resolveDelayed: false,
      illegal: false,
      illegalReason: '',
      plannedResolutionText: '',
      plannedMergeVersionBranch: '',
      labels: [],
    }],
    total: 1,
    page: 1,
    size: 20,
    sortField: 'updatedAt',
    sortOrder: 'desc',
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function mountView(topic: 'cc-product' | 'delay' = 'cc-product') {
  const path = topic === 'delay'
    ? '/customer-issues/delay-issues'
    : '/customer-issues/cc-product-issues';
  const router = createRouter({
    history: createWebHashHistory(),
    routes: [
      {
        path: '/customer-issues/cc-product-issues',
        component: CustomerIssueRecordsView,
        meta: { pageKey: 'customer-issues-cc-product-issues' },
      },
      {
        path: '/customer-issues/delay-issues',
        component: CustomerIssueRecordsView,
        meta: { pageKey: 'customer-issues-delay-issues' },
      },
    ],
  });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(CustomerIssueRecordsView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  });
  return { router, wrapper };
}

let mountedWrapper: VueWrapper | null = null;
let mountedRouter: Router | null = null;

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
});

afterEach(() => {
  mountedWrapper?.unmount();
  mountedWrapper = null;
  mountedRouter = null;
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('CustomerIssueRecordsView loading lifecycle', () => {
  it('shows records without waiting for filter options or realtime status', async () => {
    const filters = deferred<CustomerIssueRecordFilterOptionsResponse>();
    const status = deferred<RealtimeWorkspaceStatusResponse>();
    vi.spyOn(api, 'getCustomerIssueRecordFilterOptions').mockReturnValue(filters.promise);
    vi.spyOn(api, 'getCustomerIssueRecords').mockResolvedValue(recordsResponse('列表先返回'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockReturnValue(status.promise);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();

    expect(api.getCustomerIssueRecords).toHaveBeenCalledTimes(1);
    expect(mountedWrapper.text()).toContain('列表先返回');
    expect(mountedWrapper.find('.page-state-shell__skeleton').exists()).toBe(false);

    filters.resolve(emptyFilterOptions);
    status.resolve(availableStatus);
    await flushPromises();
  });

  it('keeps a filter error visible and retries only the failed resource', async () => {
    const filterSpy = vi.spyOn(api, 'getCustomerIssueRecordFilterOptions')
      .mockRejectedValueOnce(new Error('筛选服务不可用'))
      .mockResolvedValueOnce(emptyFilterOptions);
    const recordsSpy = vi.spyOn(api, 'getCustomerIssueRecords').mockResolvedValue(recordsResponse('列表仍可使用'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();

    expect(mountedWrapper.text()).toContain('筛选服务不可用');
    expect(mountedWrapper.text()).toContain('重试筛选项');
    expect(mountedWrapper.text()).toContain('列表仍可使用');

    const retryButton = mountedWrapper.findAll('button').find((button) => button.text().includes('重试筛选项'));
    await retryButton?.trigger('click');
    await flushPromises();

    expect(filterSpy).toHaveBeenCalledTimes(2);
    expect(recordsSpy).toHaveBeenCalledTimes(1);
    expect(mountedWrapper.text()).not.toContain('筛选服务不可用');
  });

  it('keeps a table error visible and replaces it after an explicit retry', async () => {
    vi.spyOn(api, 'getCustomerIssueRecordFilterOptions').mockResolvedValue(emptyFilterOptions);
    const recordsSpy = vi.spyOn(api, 'getCustomerIssueRecords')
      .mockRejectedValueOnce(new Error('列表服务不可用'))
      .mockResolvedValueOnce(recordsResponse('重试成功'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();

    expect(mountedWrapper.text()).toContain('列表服务不可用');
    expect(mountedWrapper.text()).toContain('重试列表');

    const retryButton = mountedWrapper.findAll('button').find((button) => button.text().includes('重试列表'));
    await retryButton?.trigger('click');
    await flushPromises();

    expect(recordsSpy).toHaveBeenCalledTimes(2);
    expect(mountedWrapper.text()).toContain('重试成功');
    expect(mountedWrapper.text()).not.toContain('列表服务不可用');
  });

  it('keeps the newest query result when an older request finishes later', async () => {
    const firstRequest = deferred<CustomerIssueRecordListResponse>();
    const secondRequest = deferred<CustomerIssueRecordListResponse>();
    vi.spyOn(api, 'getCustomerIssueRecordFilterOptions').mockResolvedValue(emptyFilterOptions);
    const recordsSpy = vi.spyOn(api, 'getCustomerIssueRecords')
      .mockReturnValueOnce(firstRequest.promise)
      .mockReturnValueOnce(secondRequest.promise);
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();
    expect(recordsSpy).toHaveBeenCalledTimes(1);

    await mountedRouter.replace('/customer-issues/cc-product-issues?keyword=最新查询');
    await flushPromises();
    expect(recordsSpy).toHaveBeenCalledTimes(2);

    secondRequest.resolve(recordsResponse('最新结果'));
    await flushPromises();
    firstRequest.resolve(recordsResponse('过期结果'));
    await flushPromises();

    expect(mountedWrapper.text()).toContain('最新结果');
    expect(mountedWrapper.text()).not.toContain('过期结果');
  });

  it('keeps the delay topic default milestone as an explicit loading dependency', async () => {
    const filters = deferred<CustomerIssueRecordFilterOptionsResponse>();
    vi.spyOn(api, 'getCustomerIssueRecordFilterOptions').mockReturnValue(filters.promise);
    const recordsSpy = vi.spyOn(api, 'getCustomerIssueRecords').mockResolvedValue(recordsResponse('延期结果'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView('delay'));
    await flushPromises();

    expect(recordsSpy).not.toHaveBeenCalled();

    filters.resolve({
      ...emptyFilterOptions,
      milestoneTitles: [{ label: 'CC2026R4', value: 'CC2026R4' }],
    });
    await flushPromises();

    expect(mountedRouter.currentRoute.value.query.milestoneTitle).toBe('CC2026R4');
    expect(recordsSpy).toHaveBeenCalledTimes(1);
    expect(recordsSpy).toHaveBeenCalledWith(expect.objectContaining({
      topic: 'delay',
      milestoneTitle: 'CC2026R4',
    }));
  });
});
