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
  it('mounts without route errors, opens module detail drawer and exports detail csv', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/integration-tests/project-options')) {
        return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT' }]);
      }
      if (url.includes('/api/integration-tests/phase-options')) {
        return jsonResponse([
          { projectId: 325, projectName: 'CC_PRODUCT', testingPhase: 'R1 Integration', issueCount: 12 },
          { projectId: 325, projectName: 'CC_PRODUCT', testingPhase: 'R2 Integration', issueCount: 16 },
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
      if (url.includes('/api/integration-tests/details/export')) {
        return Promise.resolve({
          ok: true,
          text: () => Promise.resolve('"Issue","Title"\n"#101","Integration sample"\n'),
        } as Response);
      }
      if (
        url.includes('/api/integration-tests/module-function/export') ||
        url.includes('/api/integration-tests/comparison/export')
      ) {
        return Promise.resolve({
          ok: true,
          blob: () => Promise.resolve(new Blob(['xlsx'])),
          text: () => Promise.resolve(''),
        } as Response);
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
    });
    vi.stubGlobal(
      'fetch',
      fetchMock,
    );
    const createObjectUrl = vi.fn(() => 'blob:integration-export');
    const revokeObjectUrl = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    vi.stubGlobal('URL', { createObjectURL: createObjectUrl, revokeObjectURL: revokeObjectUrl });

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
    expect(document.body.textContent).toContain('校验口径：执行用例总数 = 通过用例数 + 本次未通过用例数');

    const exportButton = wrapper.findAll('button').find((button) => button.text().includes('导出明细'));
    expect(exportButton).toBeTruthy();
    await exportButton!.trigger('click');
    await flushPromises();

    const exportCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/integration-tests/details/export'));
    expect(exportCall?.[0]).toContain('projectId=325');
    expect(exportCall?.[0]).toContain('testingPhase=R1+Integration');
    expect(exportCall?.[0]).toContain('moduleName=Sketch');
    expect(createObjectUrl).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();

    const moduleExportButton = wrapper.findAll('button').find((button) => button.text().includes('导出集成测试数据'));
    expect(moduleExportButton).toBeTruthy();
    await moduleExportButton!.trigger('click');
    await flushPromises();

    const moduleExportCall = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/api/integration-tests/module-function/export'),
    );
    expect(moduleExportCall?.[0]).toContain('projectId=325');
    expect(moduleExportCall?.[0]).toContain('testingPhase=R1+Integration');

    const comparisonButton = wrapper.findAll('button').find((button) => button.text().includes('导出横向对比'));
    expect(comparisonButton).toBeTruthy();
    await comparisonButton!.trigger('click');
    await flushPromises();
    const confirmComparisonButton = Array.from(document.body.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === '导出',
    );
    expect(confirmComparisonButton).toBeTruthy();
    confirmComparisonButton!.click();
    await flushPromises();

    const comparisonExportCall = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/api/integration-tests/comparison/export'),
    );
    expect(comparisonExportCall?.[0]).toContain('projectId=325');
    expect(comparisonExportCall?.[0]).toContain('basePhase=R2+Integration');
    expect(comparisonExportCall?.[0]).toContain('targetPhase=R1+Integration');
    expect(createObjectUrl).toHaveBeenCalledTimes(3);
    expect(click).toHaveBeenCalledTimes(3);

    wrapper.unmount();
    click.mockRestore();
    vi.unstubAllGlobals();
  });
});
