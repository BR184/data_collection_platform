import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { api } from '../api';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata, ReadViewTransport } from '../types/api/read-view';
import type { CodeReviewIllegalRecordRowResponse } from '../types/api';
import { createDefaultCodeReviewFilterOptions } from './code-review-illegal-records-view-helpers';
import CodeReviewIllegalRecordsView from './CodeReviewIllegalRecordsView.vue';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = ''; });
describe('code review illegal complete page reads', () => {
  it.each([true, false])('freezes mode %s and retains old list and split detail on failed preparation', async compatibilityRead => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    const identities = new Map<string, ReadViewMetadata>();
    vi.spyOn(readViewsApi, 'acquire').mockImplementation(async () => {
      const readContextId = crypto.randomUUID();
      const metadata: ReadViewMetadata = { readContextId, pageKey: 'code-review-illegal-records', scopeKey: 'fixed',
        sourceVersion: readContextId, businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
        leaseExpiresAt: '2099-01-01T00:00:00Z', pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
      identities.set(readContextId, metadata); return metadata;
    });
    vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    const disclose = (read?: ReadViewTransport) => read?.onReadView(identities.get(read.readContextId)!);
    const liveMode = vi.spyOn(api, 'getCodeReviewMatchModeStatus');
    const otherPage = vi.spyOn(api, 'getCodeReviewMultiBoardSourceOptions');
    vi.spyOn(api, 'getCodeReviewIllegalRecordContext').mockImplementation(async read => {
      disclose(read); return { compatibilityRead, sources: [{ label: 'DGM', value: 'dgm' }] };
    });
    vi.spyOn(api, 'getCodeReviewIllegalRecordFilterOptions').mockImplementation(async (_p, _s, _n, _r, read) => {
      disclose(read); return createDefaultCodeReviewFilterOptions();
    });
    vi.spyOn(api, 'getCodeReviewIllegalRecordRealtimeStatus').mockResolvedValue({} as Awaited<ReturnType<typeof api.getCodeReviewIllegalRecordRealtimeStatus>>);
    const row: CodeReviewIllegalRecordRowResponse = { requestType: 'merge_request', sourceInstance: 'dgm', projectId: 0,
      mergeRequestId: 22273, mergeRequestIid: 22273, legacySourceId: '702', mergeRequestContent: '完整旧拆分记录',
      owner: 'owner', projectName: 'project', repositoryName: 'DGM', author: 'author', mergedBy: 'merger', moduleName: 'module',
      targetBranch: 'dev', illegalTypes: ['未扫描'], reviewerNames: 'reviewer', assigneeNames: 'assignee', reviewStatus: '',
      reviewExceptionReason: '', scanStatus: '', annotationRateResult: '', bugCountResult: '', functionName: '' };
    const result = (title: string) => ({ records: [{ ...row, mergeRequestContent: title }], total: 1, page: 1, size: 40, sortField: 'mergedAt', sortOrder: 'desc' as const });
    const records = vi.spyOn(api, 'getCodeReviewIllegalRecords').mockImplementation(async (_query, read) => {
      disclose(read); return result('完整旧拆分记录');
    });
    let rejectDetail!: (error: Error) => void;
    const detail = vi.spyOn(api, 'getCodeReviewIllegalRecord').mockImplementation(() => new Promise((_resolve, reject) => { rejectDetail = reject; }));
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/illegal', component: CodeReviewIllegalRecordsView }] });
    await router.push('/illegal?source=dgm'); await router.isReady();
    const wrapper = mount(CodeReviewIllegalRecordsView, { attachTo: document.body, global: { plugins: [router, ElementPlus] } });
    try {
      await flushPromises(); expect(wrapper.text()).toContain('完整旧拆分记录');
      expect(liveMode).not.toHaveBeenCalled(); expect(otherPage).not.toHaveBeenCalled();
      await wrapper.findAll('button').filter(button => button.text() === '详情').at(-1)!.trigger('click'); await flushPromises();
      records.mockImplementation(async (_query, read) => { disclose(read); return result('未接受的新拆分记录'); });
      await router.replace('/illegal?source=dgm&keyword=next'); await flushPromises();
      expect(wrapper.text()).toContain('完整旧拆分记录'); expect(wrapper.text()).not.toContain('未接受的新拆分记录');
      expect(document.body.textContent).toContain('完整旧拆分记录');
      expect(detail).toHaveBeenCalledWith(expect.objectContaining({ sourceInstance: 'dgm', projectId: 0, mergeRequestId: 22273,
        legacySourceId: '702' }), expect.objectContaining({ readContextId: expect.any(String) }));
      rejectDetail(new Error('new split detail failed')); await flushPromises();
      expect(wrapper.text()).toContain('完整旧拆分记录'); expect(wrapper.text()).not.toContain('未接受的新拆分记录');
    } finally { wrapper.unmount(); }
  });
});
