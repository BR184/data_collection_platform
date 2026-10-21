import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { analyticsDashboardApi } from '../api-client/analytics-dashboard-api';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';
import type { AnalyticsDashboardDetailResponse } from '../types/api';
import AnalyticsDashboardDetailPage from './AnalyticsDashboardDetailPage.vue';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = ''; window.history.replaceState(null, ''); });
const metadata = (id: string): ReadViewMetadata => ({ readContextId: id, pageKey: 'quality-rd', scopeKey: 'scope',
  sourceVersion: 'complete-parent-version', businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
  leaseExpiresAt: '2099-01-01T00:00:00Z', pendingUpdates: 0, sourceState: 'CURRENT', escalated: false });
const response = (title: string): AnalyticsDashboardDetailResponse => ({ dashboardKey: 'quality-rd', viewKey: 'quality-code-review-records',
  title, columns: [{ key: 'title', label: '标题' }], records: [{ title }], total: 20, page: 1, size: 10, exports: [], filters: [] });

describe('analytics detail complete page reads', () => {
  it.each([false, true])('accepts handoff %s and keeps old detail during pagination preparation and failure', async handoff => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    const parentChild = '11111111-1111-4111-8111-111111111111';
    if (handoff) window.history.replaceState({ analyticsReadContextId: parentChild, analyticsReadScope: { source: 'dgm', topic: 'annotation' }, readViewPageKey: 'quality-rd' }, '');
    const acquire = vi.spyOn(readViewsApi, 'acquire').mockImplementation(async () => metadata(crypto.randomUUID()));
    const renew = vi.spyOn(readViewsApi, 'renew').mockImplementation(async id => metadata(id));
    const release = vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    const details = vi.spyOn(analyticsDashboardApi, 'getDetails').mockImplementation(async (_board, _view, _query, read) => {
      read!.onReadView(metadata(read!.readContextId)); return response('父版本完整详情');
    });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/details/:detailViewKey', component: AnalyticsDashboardDetailPage,
      meta: { analyticsDashboardKey: 'quality-rd' } }] });
    await router.push('/details/quality-code-review-records?source=dgm&topic=annotation'); await router.isReady();
    const wrapper = mount(AnalyticsDashboardDetailPage, { attachTo: document.body, global: { plugins: [router, ElementPlus] } });
    try {
      await flushPromises(); expect(wrapper.text()).toContain('父版本完整详情');
      if (handoff) {
        expect(renew).toHaveBeenCalledWith(parentChild, expect.any(AbortSignal)); expect(acquire).not.toHaveBeenCalled();
        expect(details).toHaveBeenCalledWith('quality-rd', 'quality-code-review-records', expect.any(Object), expect.objectContaining({ readContextId: parentChild }));
      }
      let reject!: (error: Error) => void;
      details.mockImplementation(() => new Promise((_resolve, fail) => { reject = fail; }));
      await router.replace('/details/quality-code-review-records?source=dgm&topic=annotation&page=2'); await flushPromises();
      expect(wrapper.text()).toContain('父版本完整详情');
      expect(wrapper.text()).not.toContain('当前范围暂无详情数据');
      reject(new Error('page two preparation failed')); await flushPromises();
      expect(wrapper.text()).toContain('父版本完整详情'); expect(wrapper.text()).toContain('page two preparation failed');
    } finally { wrapper.unmount(); await flushPromises(); }
    if (handoff) expect(release).toHaveBeenCalledWith(parentChild);
  });

  it('keeps reading the page on an insecure origin without randomUUID', async () => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    // 内网明文 IP 访问时浏览器不提供 randomUUID，只提供 getRandomValues。
    vi.stubGlobal('crypto', {
      getRandomValues: (array: Uint8Array) => { array.fill(0x11); return array; },
    });
    const acquire = vi.spyOn(readViewsApi, 'acquire')
      .mockImplementation(async () => metadata('22222222-2222-4222-8222-222222222222'));
    vi.spyOn(readViewsApi, 'renew').mockImplementation(async id => metadata(id));
    vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    vi.spyOn(analyticsDashboardApi, 'getDetails').mockImplementation(async (_board, _view, _query, read) => {
      read!.onReadView(metadata(read!.readContextId)); return response('不安全上下文完整详情');
    });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/details/:detailViewKey', component: AnalyticsDashboardDetailPage,
      meta: { analyticsDashboardKey: 'quality-rd' } }] });
    await router.push('/details/quality-code-review-records?source=dgm&topic=annotation'); await router.isReady();
    const wrapper = mount(AnalyticsDashboardDetailPage, { attachTo: document.body, global: { plugins: [router, ElementPlus] } });
    try {
      await flushPromises();
      expect(wrapper.text()).toContain('不安全上下文完整详情');
      expect(acquire).toHaveBeenCalledTimes(1);
      expect(String(acquire.mock.calls[0][2])).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    } finally { wrapper.unmount(); await flushPromises(); }
  });
});
