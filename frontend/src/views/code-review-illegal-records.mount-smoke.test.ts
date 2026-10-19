import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import CodeReviewIllegalRecordsView from './CodeReviewIllegalRecordsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ success: true, data })) } as Response);
}

describe('CodeReviewIllegalRecordsView mount smoke', () => {
  it('mounts without route errors', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/code-review/illegal-records/filter-options')) {
        return jsonResponse({
          requestTypes: [],
          projects: [],
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
          owners: [],
          mergedBys: [],
          moduleNames: [],
          projectNames: [],
        });
      }
      if (url.includes('/api/code-review/illegal-records/rule-explanation')) {
        return jsonResponse({
          boardKey: 'code-review-illegal-records',
          supported: true,
          title: '代码走查非法记录规则说明',
          version: 'v1',
          scopeDescription: 'scope',
          summary: 'summary',
          flowSteps: [],
          metricDefinitions: [],
          unsupportedReason: null,
        });
      }
      if (url.includes('/api/code-review/illegal-records/status')) {
        return jsonResponse({
          workspaceKey: 'code-review-illegal-records',
          ready: true,
          refreshing: false,
          lastSyncedAt: '2026-04-21T10:00:00',
          lastSucceededAt: '2026-04-21T10:00:00',
          message: '',
        });
      }
      if (url.includes('/api/code-review/illegal-records?')) {
        return jsonResponse({
          records: [],
          total: 0,
          page: 1,
          size: 20,
          sortField: 'mergedAt',
          sortOrder: 'desc',
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchMock);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/code-review/illegal-records', component: CodeReviewIllegalRecordsView }],
    });
    await router.push('/code-review/illegal-records');
    await router.isReady();
    const wrapper = mount(CodeReviewIllegalRecordsView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/rule-explanation'))).toBe(false);
    vi.unstubAllGlobals();
  });

  it('exports current filtered illegal records as csv', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/code-review/illegal-records/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('项目,模块\nProject X,支付模块\n') } as Response);
      }
      if (url.includes('/api/code-review/illegal-records/filter-options')) {
        return jsonResponse({
          requestTypes: [],
          projects: [],
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
          owners: [],
          mergedBys: [],
          moduleNames: [],
          projectNames: [],
        });
      }
      if (url.includes('/api/code-review/illegal-records/rule-explanation')) {
        return jsonResponse({
          boardKey: 'code-review-illegal-records',
          supported: true,
          title: '代码走查非法记录规则说明',
          version: 'v1',
          scopeDescription: 'scope',
          summary: 'summary',
          flowSteps: [],
          metricDefinitions: [],
          unsupportedReason: null,
        });
      }
      if (url.includes('/api/code-review/illegal-records/status')) {
        return jsonResponse({
          workspaceKey: 'code-review-illegal-records',
          ready: true,
          refreshing: false,
          lastSyncedAt: '2026-04-21T10:00:00',
          lastSucceededAt: '2026-04-21T10:00:00',
          message: '',
        });
      }
      if (url.includes('/api/code-review/illegal-records?')) {
        return jsonResponse({
          records: [],
          total: 0,
          page: 1,
          size: 20,
          sortField: 'mergedAt',
          sortOrder: 'desc',
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchMock);
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/code-review/illegal-records', component: CodeReviewIllegalRecordsView }],
    });
    await router.push('/code-review/illegal-records?projectId=325&keyword=sample&sortBy=mergedAt&sortOrder=desc');
    await router.isReady();
    const wrapper = mount(CodeReviewIllegalRecordsView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('下载代码走查非法数据'))?.trigger('click');
    await flushPromises();

    await vi.waitFor(() => {
      const exportCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/code-review/illegal-records/export'));
      expect(String(exportCall?.[0])).toContain('repositoryName=CrownCAD');
      expect(String(exportCall?.[0])).toContain('keyword=sample');
      expect(String(exportCall?.[0])).not.toContain('projectId=');
    });

    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('re-reads the opened record by complete identity when the page reloads', async () => {
    const record = {
      requestType: '缺陷',
      sourceInstance: 'cc',
      mergeRequestId: 9001,
      mergeRequestIid: 88,
      projectId: 325,
      mergeRequestContent: '合并请求内容',
      mergeRequestLink: null,
      owner: 'owner',
      projectName: 'CC_PRODUCT',
      repositoryName: 'CrownCAD',
      mergedAt: '2026-04-21T10:00:00',
      author: '被走查人',
      mergedBy: '合并人',
      moduleName: 'Sketch',
      targetBranch: 'main',
      illegalTypes: ['Module mismatch'],
      reviewerNames: '走查人',
      assigneeNames: '被指派人',
      reviewStatus: 'Open',
      reviewDurationMinutes: 30,
      reviewExceptionReason: null,
      codeWalkthroughDate: '2026-04-20',
      scanStatus: 'Open',
      scanBugCount: 0,
      annotationRateResult: '',
      bugCountResult: '',
      functionName: '功能示例',
      clangAddedLineCount: 0,
      legacySourceId: null,
    };
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/code-review/illegal-records/cc/325/9001')) {
        return jsonResponse(null);
      }
      if (url.includes('/api/code-review/illegal-records/filter-options')) {
        return jsonResponse({
          requestTypes: [],
          projects: [],
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
          owners: [],
          mergedBys: [],
          moduleNames: [],
          projectNames: [],
        });
      }
      if (url.includes('/api/code-review/illegal-records/status')) {
        return jsonResponse({
          workspaceKey: 'code-review-illegal-records',
          ready: true,
          refreshing: false,
          lastSyncedAt: '2026-04-21T10:00:00',
          lastSucceededAt: '2026-04-21T10:00:00',
          message: '',
        });
      }
      if (url.includes('/api/code-review/illegal-records?')) {
        return jsonResponse({
          records: [record],
          total: 1,
          page: 1,
          size: 20,
          sortField: 'mergedAt',
          sortOrder: 'desc',
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchMock);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/code-review/illegal-records', component: CodeReviewIllegalRecordsView }],
    });
    await router.push('/code-review/illegal-records?source=cc');
    await router.isReady();
    const wrapper = mount(CodeReviewIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await vi.waitFor(() => {
      expect(wrapper.findAll('.el-table__row').length).toBeGreaterThan(0);
    });
    await wrapper.findAll('.el-table__row .code-review-row-action-button').at(-1)!.trigger('click');
    await flushPromises();
    await vi.waitFor(() => {
      expect(document.body.textContent).toContain('详细内容');
    });

    await router.push({ path: '/code-review/illegal-records', query: { source: 'cc', keyword: 'sample' } });
    await flushPromises();

    expect(
      fetchMock.mock.calls.some(([url]) => String(url).includes('/api/code-review/illegal-records/cc/325/9001')),
    ).toBe(true);
    await vi.waitFor(() => {
      expect(document.body.textContent).toContain('该记录已不在当前筛选范围内');
    });

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
