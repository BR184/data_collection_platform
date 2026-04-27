import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import CodeReviewMultiBoardView from './CodeReviewMultiBoardView.vue';

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

describe('CodeReviewMultiBoardView mount smoke', () => {
  it('loads source options and renders chart-first overview', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/code-review/multi-board/source-options')) {
        return jsonResponse([
          { label: 'CC', value: 'cc' },
          { label: 'DGM', value: 'dgm' },
        ]);
      }
      if (url.includes('/api/code-review/multi-board/overview')) {
        return jsonResponse({
          source: 'cc',
          sourceLabel: 'CC',
          mergeRequestCount: 6,
          completedCount: 4,
          pendingCount: 2,
          averageCommentRate: 18.25,
          totalDefectCount: 7,
          totalAddedLines: 280,
          defectDensityPerKloc: 25,
          averageReviewDurationMinutes: 16.5,
          averageAddedLines: 58,
          moduleRows: [
            {
              rowKey: '支付中心',
              rowLabel: '支付中心',
              mergeRequestCount: 3,
              completedCount: 2,
              averageCommentRate: 16.4,
              totalDefectCount: 4,
              totalAddedLines: 156,
              defectDensityPerKloc: 25.64,
              averageReviewDurationMinutes: 15.3,
              averageAddedLines: 52,
            },
          ],
          ownerRows: [
            {
              rowKey: '张三',
              rowLabel: '张三',
              mergeRequestCount: 2,
              completedCount: 2,
              averageCommentRate: 21.2,
              totalDefectCount: 1,
              totalAddedLines: 96,
              defectDensityPerKloc: 10.42,
              averageReviewDurationMinutes: 14.5,
              averageAddedLines: 49,
            },
          ],
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/code-review/multi-board',
          component: CodeReviewMultiBoardView,
          meta: { pageKey: 'code-review-multi-board' },
        },
      ],
    });

    await router.push('/code-review/multi-board?source=cc');
    await router.isReady();

    const wrapper = mount(CodeReviewMultiBoardView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('代码走查质量概览');
    expect(wrapper.text()).toContain('模块缺陷密度');
    expect(wrapper.text()).toContain('支付中心');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(4);
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('source=cc'))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
