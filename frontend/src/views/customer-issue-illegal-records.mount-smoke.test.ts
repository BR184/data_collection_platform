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
    expect(wrapper.text()).toContain('里程碑');

    await wrapper.get('.customer-illegal-detail-trigger').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Illegal sample');
    expect(document.body.textContent).toContain('Module mismatch');
    expect(document.body.textContent).toContain('CC_PRODUCT');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('exports current filtered customer issue illegal records as csv', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/illegal-records/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('issue_iid,illegal_reason\n301,Module mismatch\n') } as Response);
      }
      if (url.includes('/api/customer-issues/illegal-records/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          illegalReasons: [],
          severityLevels: [],
          priorityLevels: [],
          issueStates: [],
          bugStatuses: [],
          categories: [],
          milestoneTitles: [],
        });
      }
      if (url.includes('/api/customer-issues/illegal-records?')) {
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
    });
    vi.stubGlobal('fetch', fetchSpy);
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() });
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

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

    await router.push('/customer-issues/illegal-records?projectId=325&keyword=illegal&illegalReason=Module%20mismatch&sortBy=updatedAt&sortOrder=desc');
    await router.isReady();

    const wrapper = mount(CustomerIssueIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const exportCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/customer-issues/illegal-records/export'));
    expect(String(exportCall?.[0])).toContain('projectId=325');
    expect(String(exportCall?.[0])).toContain('keyword=illegal');
    expect(String(exportCall?.[0])).toContain('illegalReason=Module+mismatch');
    expect(String(exportCall?.[0])).not.toContain('page=');

    wrapper.unmount();
    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
