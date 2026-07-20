import { defineComponent } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import { authState } from '../composables/auth-state';
import QualityBoardRdView from './QualityBoardRdView.vue';

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

const apiMock = vi.hoisted(() => ({
  getQualityBoardRdFilterOptions: vi.fn(),
  getDashboard: vi.fn(),
  getRules: vi.fn(),
  export: vi.fn(),
}));

vi.mock('../api', () => ({
  api: apiMock,
}));

const passthroughStub = defineComponent({
  template: '<div><slot /></div>',
});

describe('QualityBoardRdView mount smoke', () => {
  afterEach(() => {
    vi.clearAllMocks();
    document.body.innerHTML = '';
  });

  it('renders the eight metrics and five legacy chart categories', async () => {
    authState.currentUser = {
      username: 'admin',
      displayName: '管理员',
      roleCodes: ['SUPER_ADMIN'],
      roleNames: ['超级管理员'],
      permissions: ['quality.rd.view', 'quality.rd.export'],
      authenticated: true,
    };
    authState.initialized = true;
    authState.loading = false;
    authState.error = '';
    apiMock.getQualityBoardRdFilterOptions.mockResolvedValue({
          defaultProjectName: 'CC2026R4',
          projectOptions: [{ label: 'CC2026R4', value: 'CC2026R4' }],
          codeReviewSourceOptions: [
            { label: 'CC', value: 'cc' },
            { label: 'DGM', value: 'dgm' },
          ],
    });
    apiMock.getDashboard.mockResolvedValue({
      dashboardKey: 'quality-rd',
      title: '研发质量看板',
      subtitle: '汇总评审、代码走查、集成测试与系统测试质量指标。',
      metrics: [
        ['demand-review-density', '需求评审缺陷密度'],
        ['design-review-density', '设计评审缺陷密度'],
        ['code-review-density-cc', 'CC代码走查缺陷密度'],
        ['code-review-density-dgm', 'DGM代码走查缺陷密度'],
        ['integration-pass-rate', '集成测试通过率'],
        ['release-leakage-rate', '发布缺陷遗留率'],
        ['development-leakage-rate', '开发缺陷遗留率'],
        ['new-issue-fix-rate', '新发缺陷修复率'],
      ].map(([key, title]) => ({
        key,
        title,
        value: 1,
        displayValue: '1.00',
        unit: '',
        ruleKey: `quality-rd.${key}`,
        detail: null,
        export: null,
      })),
      charts: [
        '按走查人统计代码走查缺陷密度',
        '按被走查人统计代码走查缺陷密度',
        '按修复人统计缺陷数',
        '代码提交频次',
        '指派人剩余缺陷数量',
      ].map((title, index) => ({
        key: `chart-${index}`,
        title,
        subtitle: '',
        option: { series: [{ type: 'bar', data: [1] }] },
        ruleKey: `quality-rd.chart-${index}`,
        detail: null,
        export: { exportKey: `chart-${index}`, label: '导出' },
      })),
    });
    apiMock.getRules.mockResolvedValue({ dashboardKey: 'quality-rd', rules: [] });

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/quality-board/rd-quality-board', component: QualityBoardRdView }],
    });
    await router.push('/quality-board/rd-quality-board');
    await router.isReady();

    const wrapper = mount(QualityBoardRdView, {
      attachTo: document.body,
      global: {
        plugins: [router],
        stubs: {
          'el-button': passthroughStub,
          'el-link': passthroughStub,
          'el-option': passthroughStub,
          'el-radio-button': passthroughStub,
          'el-radio-group': passthroughStub,
          'el-select': passthroughStub,
        },
      },
    });
    await flushPromises();

    expect(apiMock.getDashboard).toHaveBeenCalledWith('quality-rd', {
      projectName: 'CC2026R4',
      codeReviewSource: 'cc',
    });
    expect(wrapper.findAll('.dashboard-metric-card')).toHaveLength(8);
    expect(wrapper.text()).toContain('DGM代码走查缺陷密度');
    expect(wrapper.text()).toContain('按走查人统计代码走查缺陷密度');
    expect(wrapper.text()).toContain('按被走查人统计代码走查缺陷密度');
    expect(wrapper.text()).toContain('按修复人统计缺陷数');
    expect(wrapper.text()).toContain('代码提交频次');
    expect(wrapper.text()).toContain('指派人剩余缺陷数量');
    expect(wrapper.findAll('.dashboard-chart-card')).toHaveLength(5);
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(5);

    wrapper.unmount();
  });
});
