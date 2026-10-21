import { defineComponent } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import { authState } from '../composables/auth-state';
import QualityBoardOtherView from './QualityBoardOtherView.vue';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';

const metadata: ReadViewMetadata = { readContextId: '00000000-0000-0000-0000-000000000013', pageKey: 'quality-board-other',
  scopeKey: 'all', sourceVersion: 'complete-v1', businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
  leaseExpiresAt: '2099-01-01T00:00:00Z', sourceState: 'CURRENT', pendingUpdates: 0, escalated: false };

vi.mock('../components/base/PageStateShell.vue', () => ({
  default: defineComponent({
    name: 'PageStateShell',
    template: '<div><slot /></div>',
  }),
}));

vi.mock('../components/charts/EChartPanel.vue', () => ({
  default: defineComponent({
    name: 'EChartPanel',
    template: '<div data-testid="echart-panel" />',
  }),
}));

const passthroughStub = defineComponent({
  template: '<div><slot /></div>',
});

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data, readView: metadata })),
  } as Response);
}

describe('QualityBoardOtherView mount smoke', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('renders the six legacy Other Board data categories without DGM switching', async () => {
    vi.spyOn(readViewsApi, 'acquire').mockResolvedValue(metadata);
    vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    let failPreparation = false;
    authState.currentUser = {
      username: 'admin',
      displayName: '管理员',
      roleCodes: ['SUPER_ADMIN'],
      roleNames: ['超级管理员'],
      permissions: ['quality.other.view', 'quality.other.export'],
      authenticated: true,
    };
    authState.initialized = true;
    authState.loading = false;
    authState.error = '';
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/quality-board/other/project-options')) {
        return jsonResponse({
          defaultProjectName: 'CC2026R4',
          options: [{ label: 'CC2026R4', value: 'CC2026R4' }],
        });
      }
      if (url.includes('/api/analytics-dashboards/quality-board-other/rules')) {
        if (failPreparation) return Promise.resolve({ ok: true,
          text: async () => JSON.stringify({ success: false, message: '新版本准备失败' }) } as Response);
        return jsonResponse({
          dashboardKey: 'quality-board-other',
          rules: [],
        });
      }
      if (url.includes('/api/analytics-dashboards/quality-board-other')) {
        const chart = (key: string, title: string) => ({
          key,
          title,
          subtitle: '',
          option: { series: [{ type: 'bar', data: [1] }] },
          ruleKey: key,
          detail: { viewKey: `other-${key}`, params: {} },
          export: { exportKey: `${key}-excel`, label: '导出' },
        });
        return jsonResponse({
          dashboardKey: 'quality-board-other',
          title: failPreparation ? '未接受的新质量看板' : '质量专题分析',
          subtitle: '六类专题',
          metrics: [],
          charts: [
            chart('function-defect-count', '功能缺陷数量'),
            chart('function-defect-density', '功能缺陷密度'),
            chart('quality-ranking', '质量达人榜'),
            chart('member-unresolved-rate', '成员未修复缺陷率'),
            chart('release-leakage-rate', '发布缺陷遗留率'),
            chart('development-leakage-rate', '开发缺陷遗留率'),
          ],
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/quality-board/other-board', component: QualityBoardOtherView }],
    });
    await router.push('/quality-board/other-board');
    await router.isReady();

    const wrapper = mount(QualityBoardOtherView, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: {
          'el-button': defineComponent({ template: '<button><slot /></button>' }),
          'el-option': passthroughStub,
          'el-select': passthroughStub,
        },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('功能缺陷数量');
    expect(wrapper.text()).toContain('功能缺陷密度');
    expect(wrapper.text()).toContain('质量达人榜');
    expect(wrapper.text()).toContain('成员未修复缺陷率');
    expect(wrapper.text()).toContain('发布缺陷遗留率');
    expect(wrapper.text()).toContain('开发缺陷遗留率');
    expect(wrapper.text()).not.toContain('DGM');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(6);
    const dashboardRequest = fetchSpy.mock.calls
      .map(([url]) => String(url))
      .find((url) => url.includes('/api/analytics-dashboards/quality-board-other?'));
    expect(dashboardRequest).toContain('functionCountProjectName=CC2026R4');
    expect(dashboardRequest).toContain('functionDensityProjectName=CC2026R4');
    expect(dashboardRequest).toContain('qualityRankingProjectName=CC2026R4');
    expect(dashboardRequest).toContain('memberUnresolvedProjectName=CC2026R4');
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('/quality-board/other/overview'))).toBe(false);

    failPreparation = true;
    const refresh = wrapper.findAll('button').find(button => button.text().includes('刷新'))!;
    await refresh.trigger('click'); await flushPromises();
    expect(wrapper.findAll('.dashboard-chart-card')).toHaveLength(6);
    expect(wrapper.text()).not.toContain('未接受的新质量看板');
    expect(wrapper.text()).toContain('新版本准备失败');
    wrapper.unmount();
  });
});
