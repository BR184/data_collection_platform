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

const metadata = { readContextId: '11111111-1111-4111-8111-111111111111', pageKey: 'code-review-multi',
  scopeKey: 'fixed', sourceVersion: 'published', businessDate: '2026-10-13',
  dataAsOf: '2026-10-13T00:00:00Z', leaseExpiresAt: '2099-01-01T00:00:00Z',
  pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data, readView: metadata })),
  } as Response);
}

describe('CodeReviewMultiBoardView mount smoke', () => {
  it('loads isolated source/project scopes and renders all eight legacy topics', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.startsWith('/api/read-views')) return jsonResponse(metadata);
      if (url.includes('/api/code-review/multi-board/source-options')) {
        return jsonResponse([
          { label: 'CC', value: 'cc' },
          { label: 'DGM', value: 'dgm' },
        ]);
      }
      if (url.includes('/api/code-review/multi-board/project-options')) {
        return jsonResponse([{ label: 'CC2026R3', value: 'CC2026R3' }]);
      }
      if (url.includes('/api/analytics-dashboards/code-review-multi/rules')) {
        return jsonResponse({
          dashboardKey: 'code-review-multi',
          rules: Array.from({ length: 8 }, (_, index) => ({
            key: `rule-${index}`,
            title: `规则 ${index}`,
            formula: '测试公式',
          })),
        });
      }
      if (url.includes('/api/analytics-dashboards/code-review-multi')) {
        return jsonResponse({
          dashboardKey: 'code-review-multi',
          title: '代码走查多元看板',
          subtitle: 'CC / CC2026R3',
          metrics: [],
          charts: Array.from({ length: 8 }, (_, index) => ({
            key: `topic-${index}`,
            title: index === 0 ? '模块千行缺陷率' : `专题 ${index}`,
            option: {},
            ruleKey: `rule-${index}`,
            detail: { viewKey: 'code-review-statistics', params: { topic: `topic-${index}` } },
            export: { exportKey: `topic-${index}`, label: '导出' },
          })),
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

    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('代码走查多元看板');
      expect(wrapper.text()).toContain('模块千行缺陷率');
      expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(8);
    });
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('source=cc'))).toBe(true);
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('projectName=CC2026R3'))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
