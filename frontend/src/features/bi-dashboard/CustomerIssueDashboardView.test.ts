import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { biDashboardApi } from '../../api-client/bi-dashboard-api';
import CustomerIssueDashboardView from './CustomerIssueDashboardView.vue';
import type { BiCustomerIssuePageData, BiPageResponse } from './data/types';

vi.mock('../../api-client/bi-dashboard-api', () => ({
  biDashboardApi: { loadCustomerIssues: vi.fn() },
}));

const BiChartPanelStub = defineComponent({
  name: 'BiChartPanel',
  props: ['title', 'downloadContext'],
  setup(props) {
    return () => h('article', { 'data-chart-title': props.title }, [h('h3', String(props.title))]);
  },
});
const BiMetricStripStub = defineComponent({
  name: 'BiMetricStrip',
  props: ['items'],
  setup(props) {
    return () => h('section', props.items.map((item: { label: string }) => h('span', { class: 'metric-label' }, item.label)));
  },
});
const SelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue', 'disabled', 'loading', 'placeholder'],
  emits: ['change'],
  template: '<div v-bind="$attrs"><slot /></div>',
});
const OptionStub = defineComponent({
  name: 'ElOption',
  props: ['label', 'value'],
  template: '<span :data-option-label="label" :data-option-value="value">{{ label }}</span>',
});
const ButtonStub = defineComponent({ name: 'ElButton', emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' });
const AlertStub = defineComponent({ props: ['title'], template: '<div role="alert">{{ title }}<slot /></div>' });
const ResultStub = defineComponent({ props: ['title', 'subTitle'], template: '<section><h2>{{ title }}</h2><p>{{ subTitle }}</p><slot name="extra" /></section>' });

describe('CustomerIssueDashboardView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(biDashboardApi.loadCustomerIssues).mockResolvedValue(readyResponse());
  });

  it('renders ten defect metrics, four demand metrics and eight independently registered charts', async () => {
    const { wrapper } = await mountAt('/bi-dashboard/customer-issues');

    expect(wrapper.findAll('[data-chart-title]')).toHaveLength(8);
    expect(wrapper.findAll('[data-chart-title]').map((item) => item.attributes('data-chart-title'))).toEqual([
      '模块缺陷数', '缺陷严重级别分布', '模块与缺陷严重级别', '缺陷原因分布',
      '申请延期分析', '按指派人统计缺陷数', '缺陷日增与日修复', '模块需求分布',
    ]);
    expect(wrapper.text()).toContain('缺陷指标');
    expect(wrapper.text()).toContain('需求指标');
    expect(wrapper.text()).not.toContain('业务日');
    expect(wrapper.text()).not.toContain('来源版本');
    expect(wrapper.text()).not.toContain('统计范围');
    expect(wrapper.findAll('.metric-label')).toHaveLength(14);
    expect(wrapper.findAllComponents(BiChartPanelStub).map((panel) => panel.props('downloadContext').chartInstanceId))
      .toEqual([
        'customer-issue-module-defects', 'customer-issue-severity-distribution', 'customer-issue-module-severity',
        'customer-issue-cause-distribution', 'customer-issue-delay-analysis', 'customer-issue-assignee-workload',
        'customer-issue-daily-trend', 'customer-issue-module-demand',
      ]);
    expect(wrapper.findAllComponents(BiChartPanelStub)[0]?.props('downloadContext').scope).toMatchObject({
      rangeType: 'CUSTOMER_ISSUE', businessDate: '2026-09-24', customerKind: 'ALL',
    });
    wrapper.unmount();
  });

  it('distinguishes missing members from a real same-name member by visible labels and typed values', async () => {
    const { wrapper, router } = await mountAt('/bi-dashboard/customer-issues');
    const visibleOptions = wrapper.findAll('[data-option-label]').map((option) => ({
      label: option.attributes('data-option-label'),
      value: option.attributes('data-option-value'),
    }));

    expect(visibleOptions).toContainEqual({ label: '未标注客户', value: 'MISSING' });
    expect(visibleOptions).toContainEqual({ label: 'missing', value: 'VALUE:missing' });

    const customerControl = wrapper.findAllComponents(SelectStub)
      .find((control) => control.attributes('aria-label') === '客户筛选');
    expect(customerControl).toBeDefined();
    await customerControl!.vm.$emit('change', 'VALUE:missing');
    await flushPromises();

    expect(router.currentRoute.value.query).toMatchObject({ customerKind: 'VALUE', customer: 'missing' });
    expect(biDashboardApi.loadCustomerIssues).toHaveBeenLastCalledWith(expect.objectContaining({
      customer: { kind: 'VALUE', value: 'missing' },
    }));
    wrapper.unmount();
  });

  it('hides old-range candidates while the next response is pending and ignores an older response', async () => {
    let resolveRangeB!: (value: BiPageResponse<BiCustomerIssuePageData>) => void;
    let resolveRangeC!: (value: BiPageResponse<BiCustomerIssuePageData>) => void;
    vi.mocked(biDashboardApi.loadCustomerIssues)
      .mockResolvedValueOnce(readyResponse('mile-a', ['old-range-customer']))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRangeB = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveRangeC = resolve; }));
    const { wrapper, router } = await mountAt('/bi-dashboard/customer-issues');

    expect(wrapper.find('[data-option-label="old-range-customer"]').exists()).toBe(true);
    await router.push({ path: '/bi-dashboard/customer-issues', query: { milestoneBusinessKey: 'mile-b' } });
    await flushPromises();

    expect(wrapper.find('[data-option-label="old-range-customer"]').exists()).toBe(false);
    const customerControl = wrapper.findAllComponents(SelectStub)
      .find((control) => control.attributes('aria-label') === '客户筛选');
    expect(customerControl?.props('disabled')).toBe(true);

    await router.push({ path: '/bi-dashboard/customer-issues', query: { milestoneBusinessKey: 'mile-c' } });
    await flushPromises();
    resolveRangeC(readyResponse('mile-c', ['current-customer']));
    await flushPromises();
    resolveRangeB(readyResponse('mile-b', ['stale-customer']));
    await flushPromises();

    const milestoneControl = wrapper.findAllComponents(SelectStub)
      .find((control) => control.attributes('aria-label') === '客户里程碑范围');
    expect(milestoneControl?.props('modelValue')).toBe('mile-c');
    expect(wrapper.find('[data-option-label="current-customer"]').exists()).toBe(true);
    expect(wrapper.find('[data-option-label="stale-customer"]').exists()).toBe(false);
    wrapper.unmount();
  });

  it('rejects malformed deep-link selector shapes without requesting an unfiltered page', async () => {
    const { wrapper } = await mountAt('/bi-dashboard/customer-issues?customerKind=VALUE&customer=');

    expect(biDashboardApi.loadCustomerIssues).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('customer 的 VALUE 类型必须携带成员值');
    expect(wrapper.text()).toContain('清除非法筛选');
    wrapper.unmount();
  });

  it('reloads on browser back and forward with the URL filter identity', async () => {
    const { wrapper, router } = await mountAt('/bi-dashboard/customer-issues');
    await router.push({ path: '/bi-dashboard/customer-issues', query: { customerKind: 'MISSING' } });
    await flushPromises();
    expect(biDashboardApi.loadCustomerIssues).toHaveBeenLastCalledWith(expect.objectContaining({
      customer: { kind: 'MISSING' },
    }));

    await router.back();
    await flushPromises();
    expect(router.currentRoute.value.query.customerKind).toBeUndefined();
    expect(biDashboardApi.loadCustomerIssues).toHaveBeenLastCalledWith(expect.objectContaining({
      customer: { kind: 'ALL' },
    }));
    wrapper.unmount();
  });
});

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/bi-dashboard/customer-issues', component: CustomerIssueDashboardView }],
  });
  await router.push(path);
  await router.isReady();
  const wrapper = mount(CustomerIssueDashboardView, {
    global: {
      plugins: [router],
      stubs: {
        BiChartPanel: BiChartPanelStub,
        BiMetricStrip: BiMetricStripStub,
        'el-select': SelectStub,
        'el-option': OptionStub,
        'el-button': ButtonStub,
        'el-alert': AlertStub,
        'el-result': ResultStub,
        'el-tooltip': { template: '<span><slot /></span>' },
      },
    },
  });
  await flushPromises();
  return { wrapper, router };
}

function readyResponse(
  milestone = 'mile-default',
  customerNames: string[] = ['missing'],
): BiPageResponse<BiCustomerIssuePageData> {
  const statuses = [
    'source-consistency', 'defect-overview', 'today-activity', 'module-defects', 'severity-distribution',
    'module-severity', 'cause-distribution', 'delay-analysis', 'assignee-workload', 'requirement-overview',
    'module-requirements', 'daily-defect-trend',
  ];
  const uniqueCustomerNames = [...new Set([...customerNames, 'missing'])];
  const customers: BiCustomerIssuePageData['customers'] = uniqueCustomerNames.map((value) => ({
    kind: 'VALUE' as const, value, displayName: value,
  }));
  customers.push({ kind: 'MISSING', value: null, displayName: '未标注客户' });
  const metric = (key: string, label: string) => ({
    key, label, value: 1, numerator: 1, denominator: null, percentage: null, unit: '个',
  });
  const data: BiCustomerIssuePageData = {
    milestones: [
      { businessKey: 'mile-default', displayName: '默认客户里程碑' },
      { businessKey: 'mile-a', displayName: '旧范围' },
      { businessKey: 'mile-b', displayName: '范围B' },
      { businessKey: 'mile-c', displayName: '范围C' },
    ],
    selectedMilestoneBusinessKey: milestone,
    selectedMilestoneDisplayName: milestone,
    businessDate: '2026-09-24',
    customers,
    modules: [{ kind: 'VALUE', value: '模块A', displayName: '模块A' }],
    functions: [{ kind: 'VALUE', value: '功能A', displayName: '功能A' }],
    defectMetrics: Array.from({ length: 10 }, (_, index) => metric(`d-${index}`, `缺陷指标${index + 1}`)),
    requirementMetrics: Array.from({ length: 4 }, (_, index) => metric(`r-${index}`, `需求指标${index + 1}`)),
    moduleDefects: [{ module: '模块A', fixedCount: 1, unfixedCount: 0, totalCount: 1 }],
    severityDistribution: [{ severity: '一级', count: 1 }],
    moduleSeverity: [{ module: '模块A', severity: '一级', count: 1 }],
    causeDistribution: [{ groupId: 'coding', groupName: '编码问题', causeId: 'logic', causeName: '逻辑错误', count: 1 }],
    unclassifiedCauseCount: 0,
    delayAnalysis: [{ reason: '未标注延期原因', severity: 'LEVEL1', count: 1 }],
    assigneeWorkload: [{ assignee: '张三', fixedCount: 1, unfixedCount: 0, totalCount: 1 }],
    moduleDemand: [{ module: '模块A', resolvedCount: 1, unresolvedCount: 0, totalCount: 1 }],
    dailyTrend: [{ date: '2026-09-24', createdCount: 1, fixedCount: 1 }],
    filteredFactCount: 1,
  };
  return {
    pageKey: 'customer-issues',
    status: 'READY',
    sourceVersion: `source-${milestone}`,
    snapshotId: `source-${milestone}`,
    ruleVersion: 'customer-issue-bi@2026-09-24-v1',
    generatedAt: '2026-09-24T00:00:00Z',
    sections: statuses.map((key) => ({ key, label: key, status: 'READY', message: '' })),
    traces: [],
    data,
  };
}
