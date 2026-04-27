import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import SystemTestMultiBoardView from './SystemTestMultiBoardView.vue';

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

function createBoard(rows: Array<{ rowKey: string; rowLabel: string; cells: Array<{ columnKey: string; numericValue: number; displayValue: string; drilldown: boolean; detailParams: Record<string, string>; }> }>) {
  return {
    definition: {
      boardKey: 'test-board',
      title: 'test',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '统计对象',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
      emptyText: '',
    },
    appliedFilters: {},
    appliedFilterGroup: null,
    rows,
    meta: {
      generatedAt: '2026-04-27T10:00:00',
      queryDurationMs: 12,
      rowCount: rows.length,
      columnCount: 0,
      drilldownColumnCount: 0,
    },
  };
}

function cell(columnKey: string, numericValue: number) {
  return {
    columnKey,
    numericValue,
    displayValue: String(numericValue),
    drilldown: false,
    detailParams: {},
  };
}

describe('SystemTestMultiBoardView mount smoke', () => {
  it('loads project options and renders the dashboard shell', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/question-metrics/issues/filter-options')) {
        return jsonResponse({
          projectNames: [{ label: 'CC2026R3', value: 'CC2026R3' }],
          moduleNames: [],
          testingPhases: [],
          authorNames: [],
          assigneeNames: [],
          issueStates: [],
          severityLevels: [],
          bugStatuses: [],
          categories: [],
          milestoneTitles: [],
        });
      }
      if (url.includes('/api/statistic-boards/system-test-defect-summary')) {
        return jsonResponse(
          createBoard([
            {
              rowKey: 'module-a',
              rowLabel: '支付中心',
              cells: [cell('module_total', 12), cell('fix_rate', 80)],
            },
            {
              rowKey: '__total__',
              rowLabel: '总计',
              cells: [
                cell('level1_total', 3),
                cell('level2_total', 5),
                cell('level3_total', 2),
                cell('suggestion_total', 1),
                cell('module_total', 12),
                cell('open_count', 3),
                cell('solved_count', 8),
                cell('extension_count', 2),
              ],
            },
          ]),
        );
      }
      if (url.includes('/api/statistic-boards/system-test-phase-statistics')) {
        return jsonResponse(
          createBoard([
            {
              rowKey: 'phase-1',
              rowLabel: '第一轮系统测试',
              cells: [cell('level1', 2), cell('level2', 3), cell('level3', 1), cell('suggestion', 1)],
            },
          ]),
        );
      }
      if (url.includes('/api/statistic-boards/system-test-defect-cause')) {
        return jsonResponse(
          createBoard([
            {
              rowKey: '__total__',
              rowLabel: '总计',
              cells: [
                cell('requirement_understanding', 2),
                cell('new_requirement', 1),
                cell('implementation_logic', 4),
                cell('environment_deployment', 1),
                cell('algorithm_mechanism', 1),
                cell('other_reason', 1),
              ],
            },
          ]),
        );
      }
      if (url.includes('/api/statistic-boards/system-test-delay-analysis')) {
        return jsonResponse(
          createBoard([
            { rowKey: 'delay-1', rowLabel: '方案卡点', cells: [cell('total', 3)] },
            { rowKey: '__total__', rowLabel: '总计', cells: [cell('total', 3)] },
          ]),
        );
      }
      return jsonResponse({});
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

    await router.push('/question-metrics/multi-board?projectName=CC2026R3');
    await router.isReady();

    const wrapper = mount(SystemTestMultiBoardView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('系统测试质量概览');
    expect(wrapper.text()).toContain('模块缺陷 Top 8');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(6);
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('projectName=CC2026R3'))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
