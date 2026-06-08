
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import ReviewDataManagementView from './ReviewDataManagementView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ success: true, data })) } as Response);
}

describe('ReviewDataManagementView mount smoke', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('mounts without route errors and opens the help guide drawer', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reviewOwners: [], reviewTypes: [], reviewExperts: [],
          reviewVersions: [], problemStatuses: [], reviewCategories: [], problemCategories: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'review_data',
          schemaHash: 'review-data-hash',
          groups: [
            {
              groupKey: 'module',
              label: '评审模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'eq',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/review-data/records?')) {
        return jsonResponse({
          records: [], total: 0, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc',
          summary: { totalRecords: 0, totalProblemItems: 0, averageReviewScalePages: 0, averageProblemCount: 0 },
        });
      }
      return jsonResponse({});
    }));

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/review-data/home', component: ReviewDataManagementView }],
    });
    await router.push('/review-data/home');
    await router.isReady();
    const wrapper = mount(ReviewDataManagementView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.find('.tag-group-filter-bar').exists()).toBe(true);
    expect(wrapper.find('[data-testid="tag-group-filter-toggle"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="review-advanced-filter-toggle"]').attributes('aria-expanded')).toBe('false');
    expect(wrapper.get('[data-testid="review-advanced-filter-toggle"]').text()).toContain('指标与例外条件');
    expect(wrapper.get('.review-data-advanced-filter-body').isVisible()).toBe(false);
    const trigger = wrapper.get('[data-testid="review-rule-explanation-trigger"]');
    await trigger.trigger('click');
    await flushPromises();
    expect(document.body.textContent).toContain('评审缺陷密度');
    expect(document.body.textContent).toContain('帮助指南');
    expect(document.body.textContent).toContain('评审问题 = 当前筛选结果中每条记录的问题总计之和');
    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('defers the first row request when an auto-restorable tag snapshot exists', async () => {
    window.localStorage.setItem('tag-groups:review-data:default', JSON.stringify({
      schemaVersion: 1,
      snapshots: [
        {
          schemaVersion: 1,
          id: 'snapshot-a',
          schemaHash: 'review-data-hash',
          tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
          fixedFilters: {},
          savedAt: '2026-06-08T14:30:00.000Z',
          expiresAt: '2026-07-08T14:30:00.000Z',
          pinned: true,
        },
      ],
      activeSnapshotId: 'snapshot-a',
    }));
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reviewOwners: [], reviewTypes: [], reviewExperts: [],
          reviewVersions: [], problemStatuses: [], reviewCategories: [], problemCategories: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'review_data',
          schemaHash: 'review-data-hash',
          groups: [
            {
              groupKey: 'module',
              label: '评审模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'eq',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/review-data/records?')) {
        return jsonResponse({
          records: [], total: 0, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc',
          summary: { totalRecords: 0, totalProblemItems: 0, averageReviewScalePages: 0, averageProblemCount: 0 },
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchMock);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/review-data/home', component: ReviewDataManagementView }],
    });
    await router.push('/review-data/home');
    await router.isReady();
    const wrapper = mount(ReviewDataManagementView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    const recordCalls = fetchMock.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes('/api/review-data/records?'));
    expect(recordCalls.some((url) => url.endsWith('/api/review-data/records?page=1&size=20'))).toBe(false);
    expect(recordCalls.some((url) => url.includes('tagSelections='))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
