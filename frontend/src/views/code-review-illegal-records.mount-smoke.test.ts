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
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
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
          repositoryNames: [],
          illegalTypes: [],
          targetBranches: [],
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
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() });
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

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const exportCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/code-review/illegal-records/export'));
    expect(String(exportCall?.[0])).toContain('projectId=325');
    expect(String(exportCall?.[0])).toContain('keyword=sample');

    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
