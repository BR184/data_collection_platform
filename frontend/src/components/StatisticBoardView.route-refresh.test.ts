import { defineComponent } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { api } from '../api';
import StatisticBoardView from './StatisticBoardView.vue';
import type {
  IssueScopeCatalogResponse,
  IssueScopeGroupResponse,
  RealtimeWorkspaceStatusResponse,
  StatisticBoardControlOptions,
  StatisticBoardResponse,
  StatisticDetailResponse,
} from '../types/api';
import type { StatisticBoardQueryParams } from '../api-client/statistic-boards-api';

const PAGE_PATH = '/customer-issues/customer-statistics';
const PAGE_KEY = 'customer-issues-customer-statistics';
const BOARD_KEY = 'customer-issue-customer-statistics';
const MILESTONE = 'CC2026R1';
const TOTAL_ROW_KEY = '{"total":true}';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function createBoard(filters: Record<string, string> = {}, rowLabel = '总计'): StatisticBoardResponse {
  return {
    definition: {
      boardKey: BOARD_KEY,
      title: '客户统计',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '客户',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
    },
    appliedFilters: {},
    rows: [{
      rowKey: TOTAL_ROW_KEY,
      rowLabel,
      cells: [{
        columnKey: 'defect_total',
        numericValue: 1,
        displayValue: '1',
        drilldown: true,
        detailViewKey: null,
        detailParams: {
          rowKey: TOTAL_ROW_KEY,
          metric: 'defect_total',
          population: 'COUNTED',
          groupBy: 'CUSTOMER',
          total: 'true',
          sourceVersion: 'generation-1',
          businessDate: '2026-09-23',
          ...(filters.customer ? { customer: filters.customer } : {}),
          ...(filters.customerKind ? { customerKind: filters.customerKind } : {}),
          ...(filters.module ? { module: filters.module } : {}),
          ...(filters.moduleKind ? { moduleKind: filters.moduleKind } : {}),
          ...(filters.function ? { function: filters.function } : {}),
          ...(filters.functionKind ? { functionKind: filters.functionKind } : {}),
        },
      }],
    }],
    meta: {
      generatedAt: '2026-09-23T00:00:00Z',
      queryDurationMs: 1,
      rowCount: 1,
      columnCount: 1,
      drilldownColumnCount: 1,
    },
  };
}

function createControlOptions(): StatisticBoardControlOptions {
  return {
    scopeKey: MILESTONE,
    sourceVersion: 'generation-1',
    scopeReadable: true,
    reason: '',
    groups: [
      { key: 'customer', options: [{ kind: 'VALUE', value: '客户甲', label: '客户甲' }] },
      { key: 'module', options: [{ kind: 'VALUE', value: '模块甲', label: '模块甲' }] },
      { key: 'function', options: [{ kind: 'VALUE', value: '功能甲', label: '功能甲' }] },
    ],
  };
}

function createDetail(): StatisticDetailResponse {
  return {
    title: '明细',
    description: '',
    collections: [{ key: 'COUNTED', label: '计数集合', description: '' }],
    collection: 'COUNTED',
    columns: [],
    records: [],
    total: 0,
    page: 1,
    size: 10,
    sortField: 'updatedAt',
    sortOrder: 'descending',
    quickFilterOptions: {},
  };
}

function createStatus(): RealtimeWorkspaceStatusResponse {
  return {
    workspaceKey: BOARD_KEY,
    supported: true,
    status: 'SUCCESS',
    message: '',
    refreshing: false,
    lastSyncedAt: null,
  };
}

const catalog: IssueScopeCatalogResponse = {
  id: 10,
  projectId: 325,
  projectName: 'CC_PRODUCT',
  dimension: 'MILESTONE',
  dimensionName: '里程碑',
  enabled: true,
  remark: '',
  groupCount: 1,
  unassignedValueCount: 0,
};

const milestoneGroup: IssueScopeGroupResponse = {
  id: 11,
  catalogId: 10,
  projectId: 325,
  projectName: 'CC_PRODUCT',
  dimension: 'MILESTONE',
  businessKey: MILESTONE,
  displayName: MILESTONE,
  sortOrder: 0,
  enabled: true,
  remark: '',
  issueCount: 1,
  members: [],
};

const Stub = defineComponent({
  template: '<div><slot /></div>',
});

const TableStub = defineComponent({
  props: { paginatedRows: { type: Array, default: () => [] } },
  template: '<div data-testid="statistic-board-rows">{{ paginatedRows.map((row) => row.rowLabel).join(",") }}</div>',
});

let wrappers: VueWrapper[] = [];
let apiMocks: {
  getStatisticBoard: MockInstance<typeof api.getStatisticBoard>;
  getStatisticBoardDetails: MockInstance<typeof api.getStatisticBoardDetails>;
  getStatisticBoardRealtimeStatus: MockInstance<typeof api.getStatisticBoardRealtimeStatus>;
};

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.spyOn(api, 'getIssueScopeCatalogs').mockResolvedValue([catalog]);
  vi.spyOn(api, 'getIssueScopeGroups').mockResolvedValue([milestoneGroup]);
  vi.spyOn(api, 'getStatisticBoardControlOptions').mockResolvedValue(createControlOptions());
  apiMocks = {
    getStatisticBoard: vi.spyOn(api, 'getStatisticBoard').mockImplementation(
      async (_boardKey: string, _params?: StatisticBoardQueryParams) => createBoard(),
    ),
    getStatisticBoardDetails: vi.spyOn(api, 'getStatisticBoardDetails').mockResolvedValue(createDetail()),
    getStatisticBoardRealtimeStatus: vi.spyOn(api, 'getStatisticBoardRealtimeStatus').mockResolvedValue(createStatus()),
  };
});

afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount());
  wrappers = [];
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function mountView(query: Record<string, string> = {}) {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: PAGE_PATH, component: StatisticBoardView, props: { boardKey: BOARD_KEY }, meta: { pageKey: PAGE_KEY } }],
  });
  await router.push({ path: PAGE_PATH, query: { milestoneTitle: MILESTONE, ...query } });
  await router.isReady();
  const wrapper = mount(StatisticBoardView, {
    props: { boardKey: BOARD_KEY },
    global: {
      plugins: [router, ElementPlus],
      stubs: {
        BaseStatisticTable: TableStub,
        DataScopeBar: Stub,
        PageSettingsDialog: Stub,
        StatisticBoardRuleExplanationDrawer: Stub,
        StatisticBoardToolbar: Stub,
        StatisticBoardDetailDialog: Stub,
      },
    },
  });
  wrappers.push(wrapper);
  await flushPromises();
  return { router, wrapper };
}

async function navigate(router: Router, query: Record<string, string>) {
  await router.push({ path: PAGE_PATH, query: { milestoneTitle: MILESTONE, ...query } });
  await flushPromises();
}

describe('StatisticBoardView route refresh chain', () => {
  it.each([
    ['customer', 'customerKind', '客户甲'],
    ['module', 'moduleKind', '模块甲'],
    ['function', 'functionKind', '功能甲'],
  ])('preserves invalid %s URL parameters across reload, back, and forward navigation', async (key, kindKey, value) => {
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) => {
      if (params?.filters?.[kindKey] === 'FUTURE') {
        return Promise.reject(new Error('成员筛选参数无效'));
      }
      return Promise.resolve(createBoard(params?.filters));
    });
    const { router } = await mountView({ [key]: value, [kindKey]: 'FUTURE' });

    expect(apiMocks.getStatisticBoard.mock.calls[0]?.[1]?.filters)
      .toMatchObject({ [key]: value, [kindKey]: 'FUTURE' });
    expect(apiMocks.getStatisticBoard.mock.calls[0]?.[1]?.filters?.[kindKey]).not.toBe('VALUE');

    await navigate(router, { [key]: value, [kindKey]: 'VALUE' });
    router.back();
    await flushPromises();
    expect(router.currentRoute.value.query[kindKey]).toBe('FUTURE');
    expect(apiMocks.getStatisticBoard.mock.calls.at(-1)?.[1]?.filters)
      .toMatchObject({ [key]: value, [kindKey]: 'FUTURE' });

    router.forward();
    await flushPromises();
    expect(router.currentRoute.value.query[kindKey]).toBe('VALUE');
    expect(apiMocks.getStatisticBoard.mock.calls.at(-1)?.[1]?.filters)
      .toMatchObject({ [key]: value, [kindKey]: 'VALUE' });
  });

  it('only synchronizes the current route total drilldown after requests return out of order', async () => {
    const customerA = deferred<StatisticBoardResponse>();
    const customerB = deferred<StatisticBoardResponse>();
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) => {
      const selected = params?.filters?.customer;
      if (selected === '客户甲') {
        return customerA.promise;
      }
      if (selected === '客户乙') {
        return customerB.promise;
      }
      return Promise.resolve(createBoard());
    });
    const { router } = await mountView();
    apiMocks.getStatisticBoardRealtimeStatus.mockClear();
    apiMocks.getStatisticBoardDetails.mockClear();

    const detailQuery = {
      detailVisible: '1',
      detailRowKey: TOTAL_ROW_KEY,
      detailColumnKey: 'defect_total',
      detailCollection: 'COUNTED',
    };
    await navigate(router, { ...detailQuery, customer: '客户甲', customerKind: 'VALUE' });
    await navigate(router, { ...detailQuery, customer: '客户乙', customerKind: 'VALUE' });

    customerA.resolve(createBoard({ customer: '客户甲', customerKind: 'VALUE' }, '客户甲旧范围'));
    await flushPromises();

    expect(apiMocks.getStatisticBoardRealtimeStatus).not.toHaveBeenCalled();
    expect(apiMocks.getStatisticBoardDetails).not.toHaveBeenCalled();

    customerB.resolve(createBoard({ customer: '客户乙', customerKind: 'VALUE' }, '客户乙当前范围'));
    await flushPromises();

    expect(apiMocks.getStatisticBoardRealtimeStatus).toHaveBeenCalledTimes(1);
    expect(apiMocks.getStatisticBoardDetails).toHaveBeenCalledTimes(1);
    expect(apiMocks.getStatisticBoardDetails.mock.calls[0]?.[1].filters)
      .toMatchObject({ customer: '客户乙', customerKind: 'VALUE' });
  });

  it('keeps all three typed member filters on the total-row drilldown', async () => {
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) =>
      Promise.resolve(createBoard(params?.filters ?? {})),
    );
    const { router } = await mountView({
      customer: '客户甲',
      customerKind: 'VALUE',
      module: '模块甲',
      moduleKind: 'VALUE',
      function: '功能甲',
      functionKind: 'VALUE',
      detailVisible: '1',
      detailRowKey: TOTAL_ROW_KEY,
      detailColumnKey: 'defect_total',
      detailCollection: 'COUNTED',
    });

    expect(router.currentRoute.value.query.customer).toBe('客户甲');
    expect(apiMocks.getStatisticBoardDetails).toHaveBeenCalledTimes(1);
    expect(apiMocks.getStatisticBoardDetails.mock.calls[0]?.[1].filters).toMatchObject({
      customer: '客户甲',
      customerKind: 'VALUE',
      module: '模块甲',
      moduleKind: 'VALUE',
      function: '功能甲',
      functionKind: 'VALUE',
      population: 'COUNTED',
    });
  });

  it('does not synchronize detail after a stale route status request resolves', async () => {
    const oldStatus = deferred<RealtimeWorkspaceStatusResponse>();
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) =>
      Promise.resolve(createBoard(params?.filters ?? {})),
    );
    apiMocks.getStatisticBoardRealtimeStatus.mockImplementation((_boardKey, params) =>
      params?.filters?.customer === '客户甲' ? oldStatus.promise : Promise.resolve(createStatus()),
    );
    const { router } = await mountView();
    apiMocks.getStatisticBoardDetails.mockClear();

    const detailQuery = {
      detailVisible: '1',
      detailRowKey: TOTAL_ROW_KEY,
      detailColumnKey: 'defect_total',
      detailCollection: 'COUNTED',
    };
    await navigate(router, { ...detailQuery, customer: '客户甲', customerKind: 'VALUE' });
    expect(apiMocks.getStatisticBoardRealtimeStatus.mock.calls.at(-1)?.[1]?.filters)
      .toMatchObject({ customer: '客户甲', customerKind: 'VALUE' });

    await navigate(router, { ...detailQuery, customer: '客户乙', customerKind: 'VALUE' });
    expect(apiMocks.getStatisticBoardDetails).toHaveBeenCalledTimes(1);
    expect(apiMocks.getStatisticBoardDetails.mock.calls[0]?.[1].filters)
      .toMatchObject({ customer: '客户乙', customerKind: 'VALUE' });

    oldStatus.resolve(createStatus());
    await flushPromises();
    expect(apiMocks.getStatisticBoardDetails).toHaveBeenCalledTimes(1);
  });

  it('does not run an old route refresh after the detail dialog closes', async () => {
    const pendingBoard = deferred<StatisticBoardResponse>();
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) =>
      params?.filters?.customer === '客户甲' ? pendingBoard.promise : Promise.resolve(createBoard()),
    );
    const { router } = await mountView();
    apiMocks.getStatisticBoardRealtimeStatus.mockClear();
    apiMocks.getStatisticBoardDetails.mockClear();

    const detailQuery = {
      detailVisible: '1',
      detailRowKey: TOTAL_ROW_KEY,
      detailColumnKey: 'defect_total',
      detailCollection: 'COUNTED',
      customer: '客户甲',
      customerKind: 'VALUE',
    };
    await navigate(router, detailQuery);
    await navigate(router, { customer: '客户甲', customerKind: 'VALUE' });
    pendingBoard.resolve(createBoard({ customer: '客户甲', customerKind: 'VALUE' }, '客户甲'));
    await flushPromises();

    expect(apiMocks.getStatisticBoardRealtimeStatus).not.toHaveBeenCalled();
    expect(apiMocks.getStatisticBoardDetails).not.toHaveBeenCalled();
  });

  it('invalidates a pending route task while the selected data scope is unavailable', async () => {
    const oldScopeBoard = deferred<StatisticBoardResponse>();
    const restoredScopeBoard = deferred<StatisticBoardResponse>();
    const fallbackRequested = deferred<() => void>();
    let memberLoadCount = 0;
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) => {
      if (params?.filters?.customer === '客户甲') {
        memberLoadCount += 1;
        return memberLoadCount === 1 ? oldScopeBoard.promise : restoredScopeBoard.promise;
      }
      return Promise.resolve(createBoard());
    });
    const { router } = await mountView();
    apiMocks.getStatisticBoardRealtimeStatus.mockClear();
    apiMocks.getStatisticBoardDetails.mockClear();

    await navigate(router, { customer: '客户甲', customerKind: 'VALUE' });
    let releaseFallback: (() => void) | null = null;
    router.beforeEach((to, from) => {
      if (to.query.milestoneTitle === MILESTONE && from.query.milestoneTitle === 'NOT_IN_SCOPE') {
        return new Promise<boolean>((resolve) => {
          releaseFallback = () => resolve(true);
          fallbackRequested.resolve(() => { releaseFallback?.(); });
        });
      }
      return true;
    });
    await router.push({
      path: PAGE_PATH,
      query: { milestoneTitle: 'NOT_IN_SCOPE', customer: '客户甲', customerKind: 'VALUE' },
    });
    const release = await fallbackRequested.promise;

    oldScopeBoard.resolve(createBoard({ customer: '客户甲', customerKind: 'VALUE' }, '失效范围'));
    await flushPromises();

    expect(apiMocks.getStatisticBoardRealtimeStatus).not.toHaveBeenCalled();
    expect(apiMocks.getStatisticBoardDetails).not.toHaveBeenCalled();
    expect(router.currentRoute.value.query.milestoneTitle).toBe('NOT_IN_SCOPE');
    release();
    await flushPromises();

    expect(router.currentRoute.value.query.milestoneTitle).toBe(MILESTONE);
    restoredScopeBoard.resolve(createBoard({ customer: '客户甲', customerKind: 'VALUE' }, '当前范围'));
    await flushPromises();
  });

  it('invalidates pending route status and detail synchronization when the page unmounts', async () => {
    const pendingBoard = deferred<StatisticBoardResponse>();
    apiMocks.getStatisticBoard.mockImplementation((_boardKey, params) =>
      params?.filters?.customer === '客户甲' ? pendingBoard.promise : Promise.resolve(createBoard()),
    );
    const { router, wrapper } = await mountView();
    apiMocks.getStatisticBoardRealtimeStatus.mockClear();
    apiMocks.getStatisticBoardDetails.mockClear();

    await navigate(router, {
      customer: '客户甲',
      customerKind: 'VALUE',
      detailVisible: 'true',
      detailRowKey: TOTAL_ROW_KEY,
      detailColumnKey: 'defect_total',
      detailCollection: 'COUNTED',
    });
    wrapper.unmount();
    pendingBoard.resolve(createBoard({ customer: '客户甲', customerKind: 'VALUE' }, '离开页面后的旧结果'));
    await flushPromises();

    expect(apiMocks.getStatisticBoardRealtimeStatus).not.toHaveBeenCalled();
    expect(apiMocks.getStatisticBoardDetails).not.toHaveBeenCalled();
  });
});
