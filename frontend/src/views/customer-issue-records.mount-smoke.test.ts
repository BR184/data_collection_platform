import { beforeEach, describe, expect, it, vi } from 'vitest';
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
  beforeEach(() => {
    window.localStorage.clear();
  });

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
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'issue',
          schemaHash: 'issue-hash',
          groups: [
            {
              groupKey: 'module',
              label: '模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'split_exact_comma',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
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
    expect(wrapper.text()).toContain('里程碑');

    await wrapper.get('.customer-record-detail-trigger').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('Delay sample');
    expect(document.body.textContent).toContain('Sketch');
    expect(document.body.textContent).toContain('申请延期');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('exports current filtered customer issue records as csv', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/records/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('issue_iid,title\n201,Delay sample\n') } as Response);
      }
      if (url.includes('/api/customer-issues/records/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          reasonCategories: [],
          severityLevels: [],
          priorityLevels: [],
          issueStates: [],
          bugStatuses: [],
          categories: [],
          milestoneTitles: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'issue',
          schemaHash: 'issue-hash',
          groups: [
            {
              groupKey: 'module',
              label: '模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'split_exact_comma',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/customer-issues/records?')) {
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
          path: '/customer-issues/delay-issues',
          component: CustomerIssueRecordsView,
          meta: { pageKey: 'customer-issues-delay-issues' },
        },
      ],
    });

    await router.push('/customer-issues/delay-issues?projectId=325&keyword=delay&reasonCategory=Design&sortBy=updatedAt&sortOrder=desc');
    await router.isReady();

    const wrapper = mount(CustomerIssueRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const exportCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/customer-issues/records/export'));
    expect(String(exportCall?.[0])).toContain('topic=delay');
    expect(String(exportCall?.[0])).toContain('projectId=325');
    expect(String(exportCall?.[0])).toContain('keyword=delay');
    expect(String(exportCall?.[0])).toContain('reasonCategory=Design');
    expect(String(exportCall?.[0])).not.toContain('page=');

    wrapper.unmount();
    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('renders issue tag groups and exports customer records with tag selections', async () => {
    const tagSelections = encodeURIComponent(JSON.stringify([{ groupKey: 'module', valueKeys: ['sketch'] }]));
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/records/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('issue_iid,title\n201,Tagged sample\n') } as Response);
      }
      if (url.includes('/api/customer-issues/records/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reasonCategories: [], severityLevels: [],
          priorityLevels: [], issueStates: [], bugStatuses: [], categories: [], milestoneTitles: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'issue',
          schemaHash: 'issue-hash',
          groups: [
            {
              groupKey: 'module',
              label: '模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'split_exact_comma',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/customer-issues/records?')) {
        return jsonResponse({
          records: [], total: 0, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc',
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
          path: '/customer-issues/delay-issues',
          component: CustomerIssueRecordsView,
          meta: { pageKey: 'customer-issues-delay-issues' },
        },
      ],
    });

    await router.push(`/customer-issues/delay-issues?projectId=325&tagSelections=${tagSelections}`);
    await router.isReady();

    const wrapper = mount(CustomerIssueRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    expect(wrapper.find('.tag-group-filter-bar').exists()).toBe(true);
    expect(wrapper.text()).toContain('模块：草图');

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const listCall = fetchSpy.mock.calls
      .map(([url]) => String(url))
      .find((url) => url.includes('/api/customer-issues/records?'));
    const exportCall = fetchSpy.mock.calls.find(([url]) => String(url).includes('/api/customer-issues/records/export'));
    expect(listCall).toContain('tagSelections=');
    expect(String(exportCall?.[0])).toContain('tagSelections=');

    wrapper.unmount();
    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('defers the first customer record request when an auto-restorable tag snapshot exists', async () => {
    window.localStorage.setItem('tag-groups:customer-issue:delay:default', JSON.stringify({
      schemaVersion: 1,
      snapshots: [
        {
          schemaVersion: 1,
          id: 'snapshot-a',
          schemaHash: 'issue-hash',
          tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
          fixedFilters: {},
          savedAt: '2026-06-08T14:30:00.000Z',
          expiresAt: '2026-07-08T14:30:00.000Z',
          pinned: true,
        },
      ],
      activeSnapshotId: 'snapshot-a',
    }));
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/records/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reasonCategories: [], severityLevels: [],
          priorityLevels: [], issueStates: [], bugStatuses: [], categories: [], milestoneTitles: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'issue',
          schemaHash: 'issue-hash',
          groups: [
            {
              groupKey: 'module',
              label: '模块',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'split_exact_comma',
              values: [
                { valueKey: 'sketch', label: '草图', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/customer-issues/records?')) {
        return jsonResponse({
          records: [], total: 0, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc',
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

    const recordCalls = fetchSpy.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes('/api/customer-issues/records?'));
    expect(recordCalls.some((url) => url.includes('tagSelections='))).toBe(true);
    expect(recordCalls.some((url) => url.includes('page=1&size=20') && !url.includes('tagSelections='))).toBe(false);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('does not auto-restore a snapshot after the user clears the last tag selection', async () => {
    window.localStorage.setItem('tag-groups:customer-issue:delay:default', JSON.stringify({
      schemaVersion: 1,
      snapshots: [
        {
          schemaVersion: 1,
          id: 'snapshot-a',
          schemaHash: 'issue-hash',
          tagSelections: [{ groupKey: 'module', valueKeys: ['sketch'] }],
          fixedFilters: {},
          savedAt: '2026-06-08T14:30:00.000Z',
          expiresAt: '2026-07-08T14:30:00.000Z',
          pinned: true,
        },
      ],
      activeSnapshotId: 'snapshot-a',
    }));
    const tagSelections = encodeURIComponent(JSON.stringify([{ groupKey: 'module', valueKeys: ['sketch'] }]));
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/customer-issues/records/filter-options')) {
        return jsonResponse({
          projectNames: [], moduleNames: [], reasonCategories: [], severityLevels: [],
          priorityLevels: [], issueStates: [], bugStatuses: [], categories: [], milestoneTitles: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse({
          domain: 'issue',
          schemaHash: 'issue-hash',
          groups: [
            {
              groupKey: 'module',
              label: '妯″潡',
              selectionMode: 'multiple',
              sortOrder: 10,
              matchStrategyName: 'split_exact_comma',
              values: [
                { valueKey: 'sketch', label: '鑽夊浘', valueType: 'standard', sortOrder: 10, disabled: false },
              ],
            },
          ],
        });
      }
      if (url.includes('/api/customer-issues/records?')) {
        return jsonResponse({
          records: [], total: 0, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc',
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
    await router.push(`/customer-issues/delay-issues?projectId=325&tagSelections=${tagSelections}`);
    await router.isReady();

    const wrapper = mount(CustomerIssueRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', []);
    await flushPromises();

    expect(router.currentRoute.value.query.tagSelections).toBe('[]');
    const recordCalls = fetchSpy.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes('/api/customer-issues/records?'));
    expect(recordCalls.at(-1)).not.toContain('tagSelections=');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
