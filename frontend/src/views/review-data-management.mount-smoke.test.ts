
import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import ReviewDataManagementView from './ReviewDataManagementView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({ ok: true, text: () => Promise.resolve(JSON.stringify({ success: true, data })) } as Response);
}

describe('ReviewDataManagementView mount smoke', () => {
  it('mounts without route errors and opens the help guide drawer', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reviewOwners: [], reviewTypes: [], reviewExperts: [],
          reviewVersions: [], problemStatuses: [], reviewCategories: [], problemCategories: [],
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
    const trigger = wrapper.get('[data-testid="review-rule-explanation-trigger"]');
    await trigger.trigger('click');
    await flushPromises();
    expect(document.body.textContent).toContain('评审缺陷密度');
    expect(document.body.textContent).toContain('帮助指南');
    expect(document.body.textContent).toContain('评审问题 = 当前筛选结果中每条记录的问题总计之和');
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
