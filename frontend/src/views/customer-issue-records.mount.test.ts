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
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';

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
  plannedMergeVersionBranches: [],
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
      sourceInstance: 'default',
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

async function mountView(
  topic: 'cc-product' | 'delay' = 'cc-product',
  query: Record<string, string> = {},
) {
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
  await router.push({ path, query });
  await router.isReady();
  const wrapper = mount(CustomerIssueRecordsView, {
    attachTo: document.body,
    global: { plugins: [router, ElementPlus] },
  });
  return { router, wrapper };
}

let mountedWrapper: VueWrapper | null = null;
let mountedRouter: Router | null = null;
const identities = new Map<string, ReadViewMetadata>();

function recordsReadSpy() {
  const implementation = vi.fn<typeof api.getCustomerIssueRecords>();
  vi.spyOn(api, 'getCustomerIssueRecords').mockImplementation(async params => {
    const result = await implementation(params);
    if (params.read) params.read.onReadView(identities.get(params.read.readContextId)!);
    return result;
  });
  return implementation;
}

function optionsReadSpy() {
  const implementation = vi.fn<typeof api.getCustomerIssueRecordFilterOptions>();
  vi.spyOn(api, 'getCustomerIssueRecordFilterOptions').mockImplementation(async (topic, project, read) => {
    const result = await implementation(topic, project, read);
    if (read) read.onReadView(identities.get(read.readContextId)!);
    return result;
  });
  return implementation;
}

beforeEach(() => {
  identities.clear();
  vi.spyOn(readViewsApi, 'acquire').mockImplementation(async (pageKey) => {
    const id = crypto.randomUUID();
    const metadata: ReadViewMetadata = { readContextId: id, pageKey, scopeKey: id, sourceVersion: id,
      businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z', pendingUpdates: 0,
      leaseExpiresAt: '2099-01-01T00:00:00Z', sourceState: 'CURRENT', escalated: false };
    identities.set(id, metadata);
    return metadata;
  });
  vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
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
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('CustomerIssueRecordsView loading lifecycle', () => {
  it('lease metadata renewal keeps an opened detail intact', async () => {
    vi.useFakeTimers();
    optionsReadSpy().mockResolvedValue(emptyFilterOptions);
    recordsReadSpy().mockResolvedValue(recordsResponse('续约后仍可用的议题'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);
    vi.spyOn(readViewsApi, 'renew').mockImplementation(async id => identities.get(id)!);
    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();
    await mountedWrapper.findAll('.customer-record-detail-trigger').at(-1)!.trigger('click');
    await flushPromises();
    await vi.advanceTimersByTimeAsync(60_000);
    await flushPromises();
    expect(readViewsApi.renew).toHaveBeenCalled();
    expect(document.body.querySelector('.customer-record-drawer')?.textContent).toContain('续约后仍可用的议题');
  });
  it('retains the old list and opened detail until the complete new detail is prepared', async () => {
    optionsReadSpy().mockResolvedValue(emptyFilterOptions);
    const records = recordsReadSpy().mockResolvedValue(recordsResponse('旧完整议题'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);
    const pending = deferred<ReturnType<typeof recordsResponse>['records'][number] | null>();
    const detail = vi.spyOn(api, 'getCustomerIssueRecord').mockImplementation(async (_source, _project, _root, read) => {
      const result = await pending.promise;
      read.onReadView(identities.get(read.readContextId)!);
      return result;
    });
    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();
    await mountedWrapper.findAll('.customer-record-detail-trigger').at(-1)!.trigger('click');
    await flushPromises();
    records.mockResolvedValue(recordsResponse('新完整议题'));
    const refresh = mountedWrapper.findAll('button').find(button => button.text() === '刷新');
    await refresh!.trigger('click');
    await flushPromises();
    expect(mountedWrapper.text()).toContain('旧完整议题');
    expect(mountedWrapper.text()).not.toContain('新完整议题');
    expect(document.body.textContent).toContain('旧完整议题');
    expect(detail).toHaveBeenCalledWith('default', 325, 1, expect.objectContaining({ readContextId: expect.any(String) }));
    pending.resolve(recordsResponse('新完整议题').records[0]);
    await flushPromises();
    expect(mountedWrapper.text()).toContain('新完整议题');
    expect(document.body.textContent).not.toContain('旧完整议题');
  });

  it('a failed new preparation keeps old records and candidates usable', async () => {
    const options = optionsReadSpy().mockResolvedValue({ ...emptyFilterOptions,
      projectNames: [{ label: '旧候选项目', value: '旧候选项目' }] });
    const records = recordsReadSpy().mockResolvedValue(recordsResponse('可继续使用的完整议题'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);
    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();
    records.mockRejectedValue(new Error('后台事实发布未完成'));
    options.mockResolvedValue({ ...emptyFilterOptions, projectNames: [{ label: '未接受候选', value: '未接受候选' }] });
    const refresh = mountedWrapper.findAll('button').find(button => button.text() === '刷新');
    await refresh!.trigger('click');
    await flushPromises();
    expect(mountedWrapper.text()).toContain('可继续使用的完整议题');
    expect(mountedWrapper.text()).toContain('后台事实发布未完成');
    expect(mountedWrapper.findAll('.customer-record-detail-trigger').at(-1)!.exists()).toBe(true);
    expect(mountedWrapper.text()).not.toContain('未接受候选');
  });
  it('sends every CC_PRODUCT range filter and renders distinguishable time controls', async () => {
    optionsReadSpy().mockResolvedValue({
      ...emptyFilterOptions,
      plannedMergeVersionBranches: [{ label: 'CC2026R4', value: 'CC2026R4' }],
    });
    const recordsSpy = recordsReadSpy()
      .mockResolvedValue(recordsResponse('筛选结果'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView('cc-product', {
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      plannedMergeVersionBranch: 'CC2026R4',
      retentionHoursMin: '24',
      retentionHoursMax: '72',
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    }));
    await flushPromises();

    expect(recordsSpy).toHaveBeenCalledWith(expect.objectContaining({
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      plannedMergeVersionBranch: 'CC2026R4',
      retentionHoursMin: 24,
      retentionHoursMax: 72,
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    }));
    const quickFilterButton = mountedWrapper.findAll('button')
      .find((button) => button.text().includes('快速筛选'));
    expect(quickFilterButton, '缺少快速筛选入口').toBeDefined();
    await quickFilterButton?.trigger('click');
    await flushPromises();
    for (const placeholder of [
      '计划解决时间开始',
      '计划解决时间结束',
      '提交时间开始',
      '提交时间结束',
      '更新时间开始',
      '更新时间结束',
      '最小滞留小时',
      '最大滞留小时',
    ]) {
      expect(
        mountedWrapper.find(`input[placeholder="${placeholder}"]`).exists(),
        `缺少快速筛选占位符：${placeholder}`,
      ).toBe(true);
    }
  });

  it('does not send CC_PRODUCT-only filters to the delay topic', async () => {
    optionsReadSpy().mockResolvedValue(emptyFilterOptions);
    const recordsSpy = recordsReadSpy()
      .mockResolvedValue(recordsResponse('延期筛选结果'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView('delay', {
      milestoneTitle: 'CC2026R4',
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      plannedMergeVersionBranch: 'CC2026R4',
      retentionHoursMin: '24',
      retentionHoursMax: '72',
    }));
    await flushPromises();

    const params = recordsSpy.mock.calls[0]?.[0];
    expect(params).not.toHaveProperty('plannedResolutionAtStart');
    expect(params).not.toHaveProperty('plannedResolutionAtEnd');
    expect(params).not.toHaveProperty('plannedMergeVersionBranch');
    expect(params).not.toHaveProperty('retentionHoursMin');
    expect(params).not.toHaveProperty('retentionHoursMax');
  });

  it('accepts records and candidates together without waiting for operational status', async () => {
    const filters = deferred<CustomerIssueRecordFilterOptionsResponse>();
    const status = deferred<RealtimeWorkspaceStatusResponse>();
    optionsReadSpy().mockReturnValue(filters.promise);
    recordsReadSpy().mockResolvedValue(recordsResponse('列表先返回'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockReturnValue(status.promise);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();

    expect(api.getCustomerIssueRecords).toHaveBeenCalledTimes(1);
    expect(mountedWrapper.text()).not.toContain('列表先返回');

    filters.resolve(emptyFilterOptions);
    await flushPromises();
    expect(mountedWrapper.text()).toContain('列表先返回');
    status.resolve(availableStatus);
    await flushPromises();
  });

  it('keeps preparation failure visible and retries the complete batch', async () => {
    const filterSpy = optionsReadSpy()
      .mockRejectedValueOnce(new Error('筛选服务不可用'))
      .mockResolvedValueOnce(emptyFilterOptions);
    const recordsSpy = recordsReadSpy().mockResolvedValue(recordsResponse('列表仍可使用'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView());
    await flushPromises();

    expect(mountedWrapper.text()).toContain('筛选服务不可用');
    expect(mountedWrapper.text()).toContain('重试列表');
    expect(mountedWrapper.text()).not.toContain('列表仍可使用');

    const retryButton = mountedWrapper.findAll('button').find((button) => button.text().includes('重试列表'));
    await retryButton?.trigger('click');
    await flushPromises();

    expect(filterSpy).toHaveBeenCalledTimes(2);
    expect(recordsSpy).toHaveBeenCalledTimes(2);
    expect(mountedWrapper.text()).not.toContain('筛选服务不可用');
  });

  it('keeps a table error visible and replaces it after an explicit retry', async () => {
    optionsReadSpy().mockResolvedValue(emptyFilterOptions);
    const recordsSpy = recordsReadSpy()
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
    optionsReadSpy().mockResolvedValue(emptyFilterOptions);
    const recordsSpy = recordsReadSpy()
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

  it('prepares the frozen default before making the selected milestone explicit', async () => {
    const filters = deferred<CustomerIssueRecordFilterOptionsResponse>();
    optionsReadSpy().mockReturnValue(filters.promise);
    const recordsSpy = recordsReadSpy().mockResolvedValue(recordsResponse('延期结果'));
    vi.spyOn(api, 'getCustomerIssueRecordRealtimeStatus').mockResolvedValue(availableStatus);

    ({ router: mountedRouter, wrapper: mountedWrapper } = await mountView('delay'));
    await flushPromises();

    expect(recordsSpy).toHaveBeenCalledTimes(1);
    expect(mountedWrapper.text()).not.toContain('延期结果');

    filters.resolve({
      ...emptyFilterOptions,
      milestoneTitles: [{ label: 'CC2026R4', value: 'CC2026R4' }],
    });
    await flushPromises();

    expect(mountedRouter.currentRoute.value.query.milestoneTitle).toBe('CC2026R4');
    expect(recordsSpy.mock.calls.length).toBeGreaterThanOrEqual(2);
    expect(recordsSpy).toHaveBeenCalledWith(expect.objectContaining({
      topic: 'delay',
      milestoneTitle: 'CC2026R4',
    }));
  });
});
