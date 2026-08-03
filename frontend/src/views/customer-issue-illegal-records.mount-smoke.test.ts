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
            projectNames: [{ label: 'CC_PRODUCT', value: 'CC_PRODUCT' }],
            moduleNames: [{ label: 'Sketch', value: 'Sketch' }],
            functionNames: [],
            illegalReasons: [{ label: 'Module mismatch', value: 'Module mismatch' }],
            severityLevels: [{ label: 'S1', value: 'S1' }],
            priorityLevels: [{ label: 'P0', value: 'P0' }],
            issueStates: [{ label: 'opened', value: 'opened' }],
            bugStatuses: [{ label: 'Open', value: 'Open' }],
            categories: [{ label: 'Bug', value: 'Bug' }],
            authorNames: [{ label: 'Alice', value: 'Alice' }],
            assigneeNames: [],
            milestoneTitles: [{ label: 'R1', value: 'R1' }],
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

    await router.push('/customer-issues/illegal-records?projectId=9');
    await router.isReady();

    const wrapper = mount(CustomerIssueIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();
    await vi.waitFor(() => {
      expect(wrapper.exists()).toBe(true);
      expect(wrapper.text()).toContain('Illegal sample');
      expect(wrapper.text()).toContain('R1');
    });

    const detailButtons = wrapper.findAll('button.customer-illegal-row-action-button');
    expect(detailButtons.length).toBeGreaterThan(0);
    await detailButtons.at(-1)!.trigger('click');
    await flushPromises();

    await vi.waitFor(() => {
      expect(document.body.textContent).toContain('Illegal sample');
      expect(document.body.textContent).toContain('Module mismatch');
      expect(document.body.textContent).toContain('CC_PRODUCT');
    });

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
          functionNames: [],
          illegalReasons: [],
          severityLevels: [],
          priorityLevels: [],
          issueStates: [],
          bugStatuses: [],
          categories: [],
          authorNames: [],
          assigneeNames: [],
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

    await router.push('/customer-issues/illegal-records?projectId=9&keyword=illegal&illegalReason=Module%20mismatch&sortBy=updatedAt&sortOrder=desc');
    await router.isReady();

    const wrapper = mount(CustomerIssueIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await vi.waitFor(() => {
      expect(wrapper.findAll('button').some((button) => button.text().includes('下载查询数据'))).toBe(true);
    });
    const exportButton = wrapper.findAll('button').find((button) => button.text().includes('下载查询数据'));
    expect(exportButton).toBeDefined();
    await exportButton!.trigger('click');
    await flushPromises();

    await vi.waitFor(() => {
      const exportCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/customer-issues/illegal-records/export'));
      expect(String(exportCall?.[0])).toContain('projectId=325');
      expect(String(exportCall?.[0])).toContain('keyword=illegal');
      expect(String(exportCall?.[0])).toContain('illegalReason=Module+mismatch');
      expect(String(exportCall?.[0])).not.toContain('page=');
    });

    wrapper.unmount();
    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
