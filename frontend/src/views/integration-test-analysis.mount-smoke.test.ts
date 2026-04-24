import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import IntegrationTestAnalysisView from './IntegrationTestAnalysisView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('IntegrationTestAnalysisView mount smoke', () => {
  it('mounts without route errors and opens module detail drawer', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/api/integration-tests/project-options')) {
          return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT' }]);
        }
        if (url.includes('/api/integration-tests/phase-options')) {
          return jsonResponse([
            { projectId: 325, projectName: 'CC_PRODUCT', testingPhase: 'R1 Integration', issueCount: 12 },
          ]);
        }
        if (url.includes('/api/integration-tests/summary')) {
          return jsonResponse({
            projectId: 325,
            testingPhase: 'R1 Integration',
            moduleCount: 1,
            totalIssueCount: 2,
            factRefreshedAt: '2026-04-24T10:00:00',
            rows: [
              {
                moduleName: 'Sketch',
                issueCount: 2,
                executeCase: 10,
                passCase: 8,
                notPassCase: 1,
                notPassCaseNow: 1,
                problemCase: 1,
                exceptionCount: 0,
                passRate: 80,
                illegalCount: 1,
              },
            ],
          });
        }
        if (url.includes('/api/integration-tests/details')) {
          return jsonResponse({
            records: [
              {
                issueIid: 101,
                issuableReference: '#101',
                title: 'Integration sample',
                functionName: 'Function A',
                functionLabels: 'NewFeature',
                executor: 'Alice',
                executeCase: 10,
                passCase: 8,
                notPassCase: 1,
                notPassCaseNow: 1,
                problemCase: 1,
                exceptionCount: 0,
                passRate: 80,
                legal: false,
                parseStatus: 'PARTIAL',
                validationReason: 'Need to confirm execute/pass counts',
                noteUpdatedAt: '2026-04-24T09:30:00',
              },
            ],
            total: 1,
            page: 1,
            size: 20,
            sortField: 'noteUpdatedAt',
            sortOrder: 'desc',
          });
        }
        return jsonResponse({});
      }),
    );

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/integration-test/home', component: IntegrationTestAnalysisView }],
    });

    await router.push('/integration-test/home?projectId=325&testingPhase=R1%20Integration');
    await router.isReady();

    const wrapper = mount(IntegrationTestAnalysisView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.text()).toContain('CC_PRODUCT');

    const trigger = wrapper.get('.integration-summary-table .el-button');
    await trigger.trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Sketch');
    expect(document.body.textContent).toContain('Integration sample');
    expect(document.body.textContent).toContain('Need to confirm execute/pass counts');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
