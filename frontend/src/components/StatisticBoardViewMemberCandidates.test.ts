import { defineComponent } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter, type Router } from 'vue-router';
import { api } from '../api';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';
import StatisticBoardView from './StatisticBoardView.vue';
import type {
  IssueScopeCatalogResponse,
  IssueScopeGroupResponse,
  RealtimeWorkspaceStatusResponse,
  StatisticBoardControlOptions,
  StatisticBoardResponse,
} from '../types/api';

/**
 * 首访候选就绪回归。
 *
 * <p>旧实现用 `watch(() => [enabled, scopeSignature], ...)` 监听每次重建都会换身份的数组，
 * 导致主表报告后只要筛选草稿被等值替换，候选就被判为未就绪、客户/模块/功能选择不可用
 * （现场表现为首次进入不可选、切行维度后恢复）。这里锁定：候选就绪只取决于已接受整页，
 * 与草稿重建无关。
 */
const PAGE_PATH = '/customer-issues/customer-statistics';
const PAGE_KEY = 'customer-issues-customer-statistics';
const BOARD_KEY = 'customer-issue-customer-statistics';
const MILESTONE = 'CC2026R1';
const TOTAL_ROW_KEY = '{"total":true}';

function createBoard(): StatisticBoardResponse {
  return {
    definition: {
      boardKey: BOARD_KEY,
      title: '客户统计',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '客户',
      // 与后端本板 filterFields() 一致：快速筛选行里既有字段类筛选，也有并入的成员控制项。
      filters: [
        { key: 'milestoneTitle', label: '里程碑', type: 'text', operators: ['contains'], options: [] },
        { key: 'severityLevel', label: '严重程度', type: 'select', operators: ['eq'], options: [] },
        { key: 'priorityLevel', label: '优先级', type: 'select', operators: ['eq'], options: [] },
        { key: 'bugStatus', label: '测试状态', type: 'text', operators: ['contains'], options: [] },
        { key: 'category', label: '议题类别', type: 'text', operators: ['contains'], options: [] },
        { key: 'authorName', label: '议题提交人', type: 'text', operators: ['contains'], options: [] },
        { key: 'assigneeName', label: '议题处理人', type: 'text', operators: ['contains'], options: [] },
        { key: 'issueState', label: '议题状态', type: 'select', operators: ['eq'], options: [] },
      ],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
    },
    appliedFilters: {},
    rows: [{
      rowKey: TOTAL_ROW_KEY,
      rowLabel: '总计',
      cells: [{
        columnKey: 'defect_total',
        numericValue: 1,
        displayValue: '1',
        drilldown: true,
        detailViewKey: null,
        detailParams: { rowKey: TOTAL_ROW_KEY, metric: 'defect_total', population: 'COUNTED', groupBy: 'CUSTOMER', total: 'true' },
      }],
    }],
    meta: { generatedAt: '2026-09-23T00:00:00Z', queryDurationMs: 1, rowCount: 1, columnCount: 1, drilldownColumnCount: 1 },
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

function createStatus(): RealtimeWorkspaceStatusResponse {
  return { workspaceKey: BOARD_KEY, supported: true, status: 'SUCCESS', message: '', refreshing: false, lastSyncedAt: null };
}

const catalog: IssueScopeCatalogResponse = {
  id: 10, projectId: 325, projectName: 'CC_PRODUCT', dimension: 'MILESTONE', dimensionName: '里程碑',
  enabled: true, remark: '', groupCount: 1, unassignedValueCount: 0,
};

const milestoneGroup: IssueScopeGroupResponse = {
  id: 11, catalogId: 10, projectId: 325, projectName: 'CC_PRODUCT', dimension: 'MILESTONE',
  businessKey: MILESTONE, displayName: MILESTONE, sortOrder: 0, enabled: true, remark: '', issueCount: 1, members: [],
};

const Stub = defineComponent({ template: '<div><slot /></div>' });
const TableStub = defineComponent({
  props: { paginatedRows: { type: Array, default: () => [] } },
  template: '<div data-testid="statistic-board-rows">{{ paginatedRows.map((row) => row.rowLabel).join(",") }}</div>',
});

let wrappers: VueWrapper[] = [];

beforeEach(() => {
  let identity = 0;
  const metadata = (id: string): ReadViewMetadata => ({ readContextId: id, pageKey: BOARD_KEY,
    scopeKey: 'scope', sourceVersion: 'generation-1', businessDate: '2026-09-23',
    leaseExpiresAt: '2099-09-23T12:30:00Z', dataAsOf: '2026-09-23T00:00:00Z',
    pendingUpdates: 0, sourceState: 'CURRENT', escalated: false });
  vi.spyOn(readViewsApi, 'acquire').mockImplementation(async () => metadata(`view-${++identity}`));
  vi.spyOn(readViewsApi, 'renew').mockImplementation(async id => metadata(id));
  vi.spyOn(readViewsApi, 'release').mockResolvedValue();
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  window.localStorage.clear();
  window.sessionStorage.clear();
  vi.spyOn(api, 'getIssueScopeCatalogs').mockResolvedValue([catalog]);
  vi.spyOn(api, 'getIssueScopeGroups').mockResolvedValue([milestoneGroup]);
  vi.spyOn(api, 'getStatisticBoardControlOptions').mockImplementation(async (_key, params) => {
    params?.read?.onReadView(metadata(params.read.readContextId));
    return createControlOptions();
  });
  vi.spyOn(api, 'getStatisticBoard').mockImplementation(async () => createBoard());
  vi.spyOn(api, 'getStatisticBoardRealtimeStatus').mockResolvedValue(createStatus());
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

/** 成员下拉被禁用时会带优先级禁用样式；这就是"首访不可选"的界面表征。 */
function disabledFilterControls(wrapper: VueWrapper) {
  return wrapper.findAll('.record-filter-control--priority-disabled').length;
}

describe('StatisticBoardView member candidates', () => {
  it('renders each member control inside the shared quick-filter row and keeps the row collapsed by default', async () => {
    const { wrapper } = await mountView();

    // 默认收起快速筛选行，与全平台行为保持一致
    expect(wrapper.find('.table-function-bar--quick-visible').exists()).toBe(false);

    // 成员项只由成员组件渲染；工具栏自身的字段快速筛选不应再渲染一次（fixture 声明 7 个字段）。
    expect(wrapper.get('.table-function-bar__quick [data-testid="member-filters"]')
      .findAll('.record-filter-control')).toHaveLength(3);
    expect(wrapper.findAll('.record-filter-control')).toHaveLength(10);
  });

  it('keeps member candidates usable on first entry and after an equivalent draft rebuild', async () => {
    const { router, wrapper } = await mountView();

    expect(disabledFilterControls(wrapper)).toBe(0);
    expect(wrapper.find('[data-testid="member-candidates-state"]').exists()).toBe(false);

    // filterLogic 只改变路由，不改变实际业务条件：主表与候选返回同一版本，但草稿会被等值重建。
    await navigate(router, { filterLogic: 'AND' });
    expect(api.getStatisticBoard).toHaveBeenCalledTimes(2);

    expect(disabledFilterControls(wrapper)).toBe(0);
    expect(wrapper.find('[data-testid="member-candidates-state"]').exists()).toBe(false);
  });

  it('keeps member candidates usable while reloading with a selected member', async () => {
    const { router, wrapper } = await mountView({ customer: '客户甲', customerKind: 'VALUE' });

    expect(disabledFilterControls(wrapper)).toBe(0);

    await navigate(router, { customer: '客户甲', customerKind: 'VALUE', module: '模块甲', moduleKind: 'VALUE' });

    expect(disabledFilterControls(wrapper)).toBe(0);
    expect(wrapper.find('[data-testid="member-candidates-state"]').exists()).toBe(false);
  });

  it('keeps the accepted screen when a later preparation fails instead of disabling member selectors', async () => {
    const { router, wrapper } = await mountView();
    vi.mocked(api.getStatisticBoard).mockRejectedValueOnce(new Error('成员候选准备失败'));

    await navigate(router, { customer: '客户甲', customerKind: 'VALUE' });

    // 旧完整画面继续服务，成员候选仍是就绪的；失败只以错误提示暴露。
    expect(wrapper.get('[data-testid="statistic-board-rows"]').text()).toBe('总计');
    expect(disabledFilterControls(wrapper)).toBe(0);
    expect(wrapper.text()).toContain('成员候选准备失败');
  });
});
