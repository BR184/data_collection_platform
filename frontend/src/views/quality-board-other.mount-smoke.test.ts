import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import QualityBoardOtherView from './QualityBoardOtherView.vue';

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

describe('QualityBoardOtherView mount smoke', () => {
  it('loads auxiliary charts and renders the secondary dashboard', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/code-review/multi-board/source-options')) {
        return jsonResponse([{ label: 'CC', value: 'cc' }]);
      }
      if (url.includes('/api/code-review/multi-board/overview')) {
        return jsonResponse({
          source: 'cc',
          sourceLabel: 'CC',
          mergeRequestCount: 8,
          completedCount: 6,
          pendingCount: 2,
          averageCommentRate: 18,
          totalDefectCount: 7,
          totalAddedLines: 320,
          defectDensityPerKloc: 21.88,
          averageReviewDurationMinutes: 16,
          averageAddedLines: 40,
          moduleRows: [],
          ownerRows: [
            {
              rowKey: '张三',
              rowLabel: '张三',
              mergeRequestCount: 3,
              completedCount: 2,
              averageCommentRate: 16,
              totalDefectCount: 4,
              totalAddedLines: 156,
              defectDensityPerKloc: 25.64,
              averageReviewDurationMinutes: 15,
              averageAddedLines: 52,
            },
          ],
        });
      }
      if (url.includes('/api/statistic-boards/customer-issue-response-efficiency')) {
        return jsonResponse(createBoard([{ rowKey: 'module-a', rowLabel: '支付中心', cells: [cell('response_rate', 92)] }]));
      }
      if (url.includes('/api/statistic-boards/customer-issue-by-function')) {
        return jsonResponse(createBoard([{ rowKey: 'module-a||func-a', rowLabel: '支付中心 / 登录', cells: [cell('total', 6)] }]));
      }
      if (url.includes('/api/statistic-boards/system-test-delay-analysis')) {
        return jsonResponse(createBoard([{ rowKey: 'delay-a', rowLabel: '方案卡点', cells: [cell('total', 3)] }]));
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/quality-board/other-board', component: QualityBoardOtherView, meta: { pageKey: 'quality-board-other-board' } }],
    });

    await router.push('/quality-board/other-board');
    await router.isReady();

    const wrapper = mount(QualityBoardOtherView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('专题辅助视图');
    expect(wrapper.text()).toContain('客户问题响应率');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(4);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
