import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { useAcceptedPageView } from '../../composables/useAcceptedPageView';
import { readViewsApi } from '../../api-client/read-views-api';
import type { ReadViewMetadata } from '../../types/api/read-view';
import { getPagePath } from '../../feature-manifest';
import { navigateAnalyticsDetail } from './analytics-read-navigation';

const metadata = (id: string): ReadViewMetadata => ({ readContextId: id, pageKey: 'quality-rd', scopeKey: 'scope',
  sourceVersion: 'old-complete', businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
  leaseExpiresAt: '2099-01-01T00:00:00Z', pendingUpdates: 0, sourceState: 'CURRENT', escalated: false });
afterEach(() => { vi.restoreAllMocks(); window.history.replaceState(null, ''); });
async function setup() {
  let number = 0;
  const page = useAcceptedPageView({ pageKey: 'quality-rd',
    views: { acquire: async () => metadata(`parent-${++number}`), renew: async id => metadata(id), release: async () => undefined },
    load: async (_scope, read) => { read.onReadView(metadata(read.readContextId)); return { projectName: 'accepted-project' }; } });
  await page.prepare({});
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/main', component: { template: '<div />' } },
    { path: '/detail/:detailViewKey', name: 'quality-rd-analytics-detail', component: { template: '<div />' } },
  ] });
  await router.push('/main'); await router.isReady();
  return { page, router };
}
const action = { viewKey: 'quality-code-review-records', params: { topic: 'annotation', source: 'dgm' } };

describe('analytics complete-view navigation', () => {
  it('forks the accepted point before navigating and excludes unapplied query or arbitrary source IDs', async () => {
    const { page, router } = await setup();
    const fork = vi.spyOn(readViewsApi, 'fork').mockResolvedValue(metadata('child-view'));
    const push = vi.spyOn(router, 'push');
    await navigateAnalyticsDetail(page, router, 'quality-rd', action, data => ({ projectName: data.projectName }));
    await flushPromises();
    expect(fork).toHaveBeenCalledWith('parent-1', { source: 'dgm', topic: 'annotation' }, expect.any(String), 'quality-rd');
    expect(push).toHaveBeenCalledWith(expect.objectContaining({ query: expect.objectContaining({ projectName: 'accepted-project' }),
      state: { analyticsReadContextId: 'child-view', analyticsReadScope: { source: 'dgm', topic: 'annotation' }, readViewPageKey: 'quality-rd' } }));
    page.dispose();
  });
  it('releases an unused child lease when the parent frame changes during fork', async () => {
    const { page, router } = await setup();
    let resolve!: (value: ReadViewMetadata) => void;
    vi.spyOn(readViewsApi, 'fork').mockImplementation(() => new Promise(res => { resolve = res; }));
    const release = vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    const navigation = navigateAnalyticsDetail(page, router, 'quality-rd', action, data => ({ projectName: data.projectName }));
    await flushPromises(); await page.prepare({}); resolve(metadata('unused-child'));
    await expect(navigation).rejects.toThrow('页面已换版');
    expect(release).toHaveBeenCalledWith('unused-child'); expect(router.currentRoute.value.path).toBe('/main');
    page.dispose();
  });
  it('does not bypass a denied fork by navigating with a fresh unrelated identity', async () => {
    const { page, router } = await setup();
    vi.spyOn(readViewsApi, 'fork').mockRejectedValue(new Error('fork denied'));
    await expect(navigateAnalyticsDetail(page, router, 'quality-rd', action, data => ({ projectName: data.projectName }))).rejects.toThrow('fork denied');
    expect(router.currentRoute.value.path).toBe('/main'); page.dispose();
  });
  it('copies a permitted business destination into a separately authorized page lease', async () => {
    const { page, router } = await setup();
    router.addRoute({ path: getPagePath('customer-issues-cc-product-issues')!, component: { template: '<div />' },
      meta: { pageKey: 'customer-issues-cc-product-issues' } });
    const fork = vi.spyOn(readViewsApi, 'fork').mockResolvedValue({ ...metadata('child-records'), pageKey: 'customer-issues-cc-product-issues' });
    await navigateAnalyticsDetail(page, router, 'quality-rd', { viewKey: 'customer-issue-records', params: {} }, () => ({}));
    expect(fork).toHaveBeenCalledWith('parent-1', {}, expect.any(String), 'customer-issues-cc-product-issues');
    page.dispose();
  });
});
