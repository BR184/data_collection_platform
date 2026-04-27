import { describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import SystemTestIssueSearchView from './SystemTestIssueSearchView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('SystemTestIssueSearchView mount smoke', () => {
  it('mounts without route errors', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/api/question-metrics/issues/filter-options')) {
          return jsonResponse({
            projectNames: [],
            moduleNames: [],
            testingPhases: [],
            authorNames: [],
            assigneeNames: [],
            issueStates: [],
            severityLevels: [],
            bugStatuses: [],
            categories: [],
            milestoneTitles: [],
          });
        }
        if (url.includes('/api/question-metrics/issues?')) {
          return jsonResponse({
            records: [],
            total: 0,
            page: 1,
            size: 20,
            sortField: 'updatedAt',
            sortOrder: 'desc',
          });
        }
        return jsonResponse({});
      }),
    );

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/question-metrics/issue-search', component: SystemTestIssueSearchView }],
    });
    await router.push('/question-metrics/issue-search');
    await router.isReady();
    const wrapper = mount(SystemTestIssueSearchView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.text()).toContain('测试阶段');
    vi.unstubAllGlobals();
  });
});
