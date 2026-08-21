import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BiDashboardView from './BiDashboardView.vue';
import { biDashboardApi } from '../../api-client/bi-dashboard-api';
import type { BiPageResponse } from './data/types';

vi.mock('../../api-client/bi-dashboard-api', () => ({
  biDashboardApi: {
    loadVersions: vi.fn(),
    loadRequirements: vi.fn(),
    loadDesign: vi.fn(),
    loadCoding: vi.fn(),
    loadUnitTest: vi.fn(),
    loadIntegrationTest: vi.fn(),
    loadSystemTest: vi.fn(),
  },
}));

describe('BI dashboard page-level failure', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(biDashboardApi.loadVersions).mockResolvedValue({
      defaultVersionId: 11,
      versions: [{ id: 11, businessKey: 'CC2026R3', displayName: 'CC2026R3', sortOrder: 1 }],
    });
    vi.mocked(biDashboardApi.loadCoding).mockResolvedValue(pageFailure());
  });

  it('shows one recoverable error instead of incomplete chart placeholders', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{
        path: '/bi-dashboard/coding',
        component: BiDashboardView,
        meta: { pageKey: 'bi-dashboard-coding' },
      }],
    });
    await router.push('/bi-dashboard/coding?productVersionId=11');
    await router.isReady();

    const wrapper = mount(BiDashboardView, {
      global: {
        plugins: [router],
        stubs: {
          'el-alert': true,
          'el-button': { template: '<button><slot /></button>' },
          'el-option': true,
          'el-result': {
            props: ['title', 'subTitle'],
            template: '<section><h2>{{ title }}</h2><p>{{ subTitle }}</p><slot name="extra" /></section>',
          },
          'el-segmented': true,
          'el-select': true,
          'el-tag': true,
          'el-tooltip': { template: '<span><slot /></span>' },
        },
      },
    });
    await flushPromises();

    expect(wrapper.text()).toContain('BI 页面加载失败');
    expect(wrapper.text()).toContain('BI 页面数据加载失败，请稍后重试');
    expect(wrapper.text()).toContain('重新加载');
    expect(wrapper.text()).not.toContain('数据暂不完整');
    expect(wrapper.text()).not.toContain('来源版本 未接入');
    expect(wrapper.text()).not.toContain('规则 未提供');
    wrapper.unmount();
  });

  it('keeps a dedicated bottom scroll-safe boundary for the final chart', async () => {
    vi.mocked(biDashboardApi.loadCoding).mockResolvedValue(readyPage());
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{
        path: '/bi-dashboard/coding',
        component: BiDashboardView,
        meta: { pageKey: 'bi-dashboard-coding' },
      }],
    });
    await router.push('/bi-dashboard/coding?productVersionId=11');
    await router.isReady();

    const wrapper = mount(BiDashboardView, {
      global: {
        plugins: [router],
        stubs: {
          CodingStageContent: true,
          'el-alert': true,
          'el-button': { template: '<button><slot /></button>' },
          'el-option': true,
          'el-result': true,
          'el-segmented': true,
          'el-select': true,
          'el-tag': true,
          'el-tooltip': { template: '<span><slot /></span>' },
        },
      },
    });
    await flushPromises();

    const dashboard = wrapper.get('main.bi-dashboard');
    expect(dashboard.attributes('data-bottom-scroll-safe')).toBe('true');
    expect(dashboard.attributes('style')).toBeUndefined();
    wrapper.unmount();
  });
});

function pageFailure(): BiPageResponse<never> {
  return {
    pageKey: 'coding',
    status: 'ERROR',
    sourceVersion: '',
    snapshotId: '',
    ruleVersion: '',
    generatedAt: '2026-08-05T00:00:00Z',
    sections: [{
      key: 'page',
      label: '页面数据',
      status: 'ERROR',
      message: 'BI 页面数据加载失败，请稍后重试',
    }],
    traces: [],
    data: null,
  };
}

function readyPage(): BiPageResponse<never> {
  return {
    ...pageFailure(),
    status: 'READY',
    sections: [],
    data: null,
  };
}
