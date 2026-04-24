import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import CustomerIssueIllegalRecordsView from './CustomerIssueIllegalRecordsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('CustomerIssueIllegalRecordsView mount smoke', () => {
  it('mounts without route errors and opens the detail drawer', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/api/customer-issues/illegal-records/filter-options')) {
          return jsonResponse({
            projectNames: ['CC_PRODUCT'],
            moduleNames: ['Sketch'],
            illegalReasons: ['Module mismatch'],
            severityLevels: ['S1'],
            priorityLevels: ['P0'],
            issueStates: ['opened'],
            bugStatuses: ['Open'],
            categories: ['Bug'],
            milestoneTitles: ['R1'],
          });
        }
        if (url.includes('/api/customer-issues/illegal-records?')) {
          return jsonResponse({
            records: [
              {
                issueIid: 301,
                title: 'Illegal sample',
                illegalReason: 'Module mismatch',
                projectName: 'CC_PRODUCT',
                moduleNames: 'Sketch',
                severityLevel: 'S1',
                priorityLevel: 'P0',
                issueState: 'opened',
                milestoneTitle: 'R1',
                authorName: 'Alice',
                createdAt: '2026-04-24T08:00:00',
                updatedAt: '2026-04-24T09:00:00',
                labels: ['illegal'],
                closedAt: null,
              },
            ],
            total: 1,
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
      routes: [
        {
          path: '/customer-issues/illegal-records',
          component: CustomerIssueIllegalRecordsView,
          meta: { pageKey: 'customer-issues-illegal-records' },
        },
      ],
    });

    await router.push('/customer-issues/illegal-records?projectId=325');
    await router.isReady();

    const wrapper = mount(CustomerIssueIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.text()).toContain('Illegal sample');

    await wrapper.get('.customer-illegal-detail-trigger').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Illegal sample');
    expect(document.body.textContent).toContain('Module mismatch');
    expect(document.body.textContent).toContain('CC_PRODUCT');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
