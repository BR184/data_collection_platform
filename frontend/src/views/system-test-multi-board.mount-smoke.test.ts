import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import SystemTestMultiBoardView from './SystemTestMultiBoardView.vue';
import { buildMultiBoardChartOption } from './system-test-multi-board';
import type { SystemTestIssueMultiBoardChartResponse } from '../types/api';

vi.mock('../components/charts/EChartPanel.vue', () => ({
  default: defineComponent({
    name: 'EChartPanel',
    template: '<div data-testid="echart-panel" />',
  }),
}));

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

function createChart(
  key: string,
  title: string,
): SystemTestIssueMultiBoardChartResponse {
  const point = {
    name: '支付中心',
    value: 12,
    pointKey: `${key}:payment`,
    detailViewKey: 'system-test-issues',
    detailParams: { moduleName: '支付中心' },
  };
  return {
    key,
    ruleKey: key,
    title,
    description: `按当前范围统计${title}。`,
    chartType: 'bar',
    detailViewKey: 'system-test-issues',
    detailParams: {},
    exportName: title,
    categories: [point.name],
    series: [{ name: '缺陷数量', data: [point] }],
    points: [point],
    metadata: { scope: 'CrownCAD / CC2026R3' },
  };
}

function createMultiBoard() {
  const charts = [
    ['severity-level', '缺陷严重程度分析'],
    ['phase-severity', '测试阶段缺陷分布'],
    ['module-severity', '模块缺陷分布'],
    ['major-cause', '缺陷原因占比分析'],
    ['cause-detail', '缺陷原因明细'],
    ['module-repair-rate', '模块修复率'],
    ['open-issue', '未关闭缺陷占比'],
    ['fix-user-severity', '修复人缺陷分布'],
    ['extension-module', '申请延期模块分析'],
    ['delay-cause', '延期原因分析'],
    ['rollback-module', '回退模块分析'],
  ].map(([key, title]) => createChart(key, title));

  return {
    scope: {
      projectId: 9,
      projectName: 'CrownCAD',
      testingPhase: 'CC2026R3',
      expandedTestingPhases: ['CC2026R3'],
      scopeLabel: 'CrownCAD / CC2026R3',
    },
    projectOptions: [{ label: 'CrownCAD', value: '9' }],
    testingPhaseOptions: [{ label: 'CC2026R3', value: 'CC2026R3' }],
    summaryCards: [{
      key: 'total',
      ruleKey: 'summary-total',
      label: '系统测试缺陷',
      value: '12',
      tone: 'default',
    }],
    rules: [
      {
        key: 'summary-total',
        title: '系统测试缺陷',
        formula: 'COUNT(issue)',
        scope: 'CrownCAD / CC2026R3',
        target: null,
        description: '当前范围内的系统测试缺陷。',
      },
      ...charts.map((chart) => ({
        key: chart.ruleKey,
        title: chart.title,
        formula: 'COUNT(issue)',
        scope: 'CrownCAD / CC2026R3',
        target: null,
        description: chart.description,
      })),
    ],
    charts,
  };
}

describe('SystemTestMultiBoardView mount smoke', () => {
  it('keeps drill-down metadata and stacked bar segment radius in generated chart data', () => {
    const chart: SystemTestIssueMultiBoardChartResponse = {
      key: 'phase-severity',
      ruleKey: 'phase-severity',
      title: '缺陷阶段分析',
      description: '按测试阶段统计各严重程度缺陷数量。',
      chartType: 'stackedBar',
      detailViewKey: 'system-test-issues',
      detailParams: {},
      exportName: '缺陷阶段分析',
      categories: ['CC2026R3第一轮'],
      series: [
        {
          name: '一级缺陷',
          data: [{
            name: 'CC2026R3第一轮',
            value: 3,
            pointKey: 'phase:1',
            detailViewKey: 'system-test-issues',
            detailParams: { testingPhase: 'CC2026R3第一轮', metricSeverity: '一级缺陷' },
          }],
        },
        {
          name: '二级缺陷',
          data: [{
            name: 'CC2026R3第一轮',
            value: 7,
            pointKey: 'phase:2',
            detailViewKey: 'system-test-issues',
            detailParams: { testingPhase: 'CC2026R3第一轮', metricSeverity: '二级缺陷' },
          }],
        },
      ],
      points: [],
      metadata: { scope: 'CrownCAD / CC2026R3' },
    };

    const option = buildMultiBoardChartOption(chart) as {
      series: Array<{ data: Array<{ pointKey: string; itemStyle: { borderRadius: number[] } }> }>;
    };

    expect(option.series[0].data[0]).toEqual(expect.objectContaining({
      pointKey: 'phase:1',
      itemStyle: expect.objectContaining({ borderRadius: [0, 0, 0, 0] }),
    }));
    expect(option.series[1].data[0]).toEqual(expect.objectContaining({
      pointKey: 'phase:2',
      itemStyle: expect.objectContaining({ borderRadius: [6, 6, 0, 0] }),
    }));
  });

  it('loads project options and renders the dashboard shell', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/question-metrics/multi-board')) {
        return jsonResponse(createMultiBoard());
      }
      return jsonResponse(null);
    });
    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/question-metrics/multi-board',
          component: SystemTestMultiBoardView,
          meta: { pageKey: 'question-metrics-multi-board' },
        },
        { path: '/question-metrics/home', component: { template: '<div />' } },
        { path: '/question-metrics/phase-statistics', component: { template: '<div />' } },
        { path: '/question-metrics/defect-cause', component: { template: '<div />' } },
        { path: '/question-metrics/delay-analysis', component: { template: '<div />' } },
      ],
    });

    await router.push('/question-metrics/multi-board?projectId=9&testingPhase=CC2026R3');
    await router.isReady();

    const wrapper = mount(SystemTestMultiBoardView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('议题多元看板');
    expect(wrapper.text()).toContain('模块缺陷分布');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(11);
    expect(wrapper.text()).toContain('导出');
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('projectId=9'))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
