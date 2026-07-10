import { defineComponent } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import { authState } from '../composables/auth-state';
import QualityBoardOtherView from './QualityBoardOtherView.vue';

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
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('QualityBoardOtherView mount smoke', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
  });

  it('renders the six legacy Other Board data categories without DGM switching', async () => {
    authState.currentUser = {
      username: 'admin',
      displayName: '管理员',
      role: 'ADMIN',
      authenticated: true,
    };
    authState.initialized = true;
    authState.loading = false;
    authState.error = '';
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/quality-board/rd/project-options')) {
        return jsonResponse({
          defaultProjectName: 'CC2026R4',
          options: [{ label: 'CC2026R4', value: 'CC2026R4' }],
        });
      }
      if (url.includes('/api/quality-board/other/overview')) {
        return jsonResponse({
          projectName: 'CC2026R4',
          functionDefectCountRows: [{ name: '建模', value: 12 }],
          functionDefectDensityRows: [{ name: '建模', value: 0.8 }],
          qualityRankingRows: [{ name: '成员A', value: 1.2 }],
          memberUnresolvedRateRows: [{ name: '成员A', value: 8.5 }],
          releaseLeakageRateRows: [{ name: 'CC2026R4', value: 10 }],
          developmentLeakageRateRows: [{ name: 'CC2026R4', value: 88 }],
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
          'el-button': passthroughStub,
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

    wrapper.unmount();
  });
});
