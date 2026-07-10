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
  getQualityBoardRdProjectOptions: vi.fn(),
  getQualityBoardRdDashboard: vi.fn(),
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
      role: 'ADMIN',
      authenticated: true,
    };
    authState.initialized = true;
    authState.loading = false;
    authState.error = '';
    apiMock.getQualityBoardRdProjectOptions.mockResolvedValue({
          defaultProjectName: 'CC2026R4',
          options: [{ label: 'CC2026R4', value: 'CC2026R4' }],
    });
    apiMock.getQualityBoardRdDashboard.mockResolvedValue({
          summary: {
            projectName: 'CC2026R4',
            demandReviewReportDensity: 0.3,
            designReviewReportDensity: 0.4,
            codeWalkThroughDefectDensityCc: 3.2,
            codeWalkThroughDefectDensityDgm: 4.1,
            integrationPassRate: 92,
            defectLeakageRate: 10,
            defectEliminationRate: 91,
            newIssueFixRate: 88,
            metrics: [],
          },
          codeReviewSource: 'cc',
          codeReviewSourceOptions: [
            { label: 'CC', value: 'cc' },
            { label: 'DGM', value: 'dgm' },
          ],
          assigneeDefectDensityRows: [{ name: '走查人A', value: 3.1 }],
          authorDefectDensityRows: [{ name: '作者A', value: 2.8 }],
          fixUserSeverityRows: [{ name: '修复人A', level1: 1, level2: 2, level3: 3, suggestion: 1, total: 7 }],
          frequencyCodeSubmissionRows: [{ name: '提交人A', value: 12 }],
          defectRepairUserRows: [{ name: '指派人A', value: 5 }],
    });

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

    expect(wrapper.findAll('.quality-board-rd__summary-card')).toHaveLength(8);
    expect(wrapper.text()).toContain('DGM代码走查缺陷密度');
    expect(wrapper.text()).toContain('按走查人统计代码走查缺陷密度');
    expect(wrapper.text()).toContain('按被走查人统计代码走查缺陷密度');
    expect(wrapper.text()).toContain('按修复人统计缺陷数');
    expect(wrapper.text()).toContain('代码提交频次');
    expect(wrapper.text()).toContain('指派人剩余缺陷数量');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(5);

    wrapper.unmount();
  });
});
