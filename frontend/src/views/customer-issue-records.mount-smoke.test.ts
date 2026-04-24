import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import CustomerIssueRecordsView from './CustomerIssueRecordsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('CustomerIssueRecordsView mount smoke', () => {
  it('mounts the delay topic route and opens the detail drawer', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/records/filter-options')) {
        return jsonResponse({
          projectNames: ['CC_PRODUCT'],
          moduleNames: ['Sketch'],
          reasonCategories: ['Design'],
          severityLevels: ['S2'],
          priorityLevels: ['P1'],
          issueStates: ['opened'],
          bugStatuses: ['Open'],
          categories: ['Bug'],
          milestoneTitles: ['R1'],
        });
      }
      if (url.includes('/api/customer-issues/records?')) {
        return jsonResponse({
          records: [
            {
              issueIid: 201,
              title: 'Delay sample',
              projectName: 'CC_PRODUCT',
              moduleNames: 'Sketch',
              reasonCategory: 'Design',
              severityLevel: 'S2',
              priorityLevel: 'P1',
              issueState: 'opened',
              milestoneTitle: 'R1',
              authorName: 'Alice',
              assigneeName: 'Bob',
              createdAt: '2026-04-24T09:00:00',
              updatedAt: '2026-04-24T10:00:00',
              delayIssue: true,
              responseDelayed: true,
              resolveDelayed: false,
              illegal: false,
              illegalReason: null,
              labels: ['delay'],
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
    });

    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: '/customer-issues/delay-issues',
          component: CustomerIssueRecordsView,
          meta: { pageKey: 'customer-issues-delay-issues' },
        },
      ],
    });

    await router.push('/customer-issues/delay-issues?projectId=325');
    await router.isReady();

    const wrapper = mount(CustomerIssueRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.exists()).toBe(true);
    expect(fetchSpy.mock.calls.some(([url]) => String(url).includes('topic=delay'))).toBe(true);
    expect(wrapper.text()).toContain('Delay sample');

    await wrapper.get('.customer-record-detail-trigger').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Delay sample');
    expect(document.body.textContent).toContain('Sketch');
    expect(document.body.textContent).toContain('申请延期');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
