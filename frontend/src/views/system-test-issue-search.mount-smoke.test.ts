import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import SystemTestIssueSearchView from './SystemTestIssueSearchView.vue';

const issueTagGroups = {
  domain: 'issue',
  schemaHash: 'issue-hash',
  groups: [
    {
      groupKey: 'phase',
      label: 'Testing Phase',
      selectionMode: 'multiple',
      sortOrder: 10,
      matchStrategyName: 'eq',
      values: [
        { valueKey: 'R1', label: 'R1 Phase', valueType: 'standard', sortOrder: 10, disabled: false },
        { valueKey: 'R2', label: 'R2 Phase', valueType: 'standard', sortOrder: 20, disabled: false },
      ],
    },
  ],
};

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('SystemTestIssueSearchView mount smoke', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

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

  it('exports current filtered issue records as csv', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/question-metrics/issues/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('issue_iid,title\n809,Sample\n') } as Response);
      }
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
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:csv'), revokeObjectURL: vi.fn() });
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/question-metrics/issue-search', component: SystemTestIssueSearchView }],
    });
    await router.push('/question-metrics/issue-search?projectId=1001&sourceInstance=cc&keyword=sample&moduleName=Sketch&sortBy=updatedAt&sortOrder=desc');
    await router.isReady();
    const wrapper = mount(SystemTestIssueSearchView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const exportCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/question-metrics/issues/export'));
    const filterOptionsCall = fetchMock.mock.calls.find(([url]) =>
      String(url).includes('/api/question-metrics/issues/filter-options'),
    );
    expect(String(filterOptionsCall?.[0])).toContain('sourceInstance=cc');
    expect(String(exportCall?.[0])).toContain('projectId=1001');
    expect(String(exportCall?.[0])).toContain('sourceInstance=cc');
    expect(String(exportCall?.[0])).toContain('keyword=sample');
    expect(String(exportCall?.[0])).toContain('moduleName=Sketch');
    expect(String(exportCall?.[0])).not.toContain('page=');

    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('reloads issue options and rows when sourceInstance changes', async () => {
    const fetchMock = vi.fn((url: string) => {
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
    });
    vi.stubGlobal('fetch', fetchMock);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/question-metrics/issue-search', component: SystemTestIssueSearchView }],
    });
    await router.push('/question-metrics/issue-search?sourceInstance=cc');
    await router.isReady();
    mount(SystemTestIssueSearchView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    fetchMock.mockClear();
    await router.push('/question-metrics/issue-search?sourceInstance=dgm');
    await flushPromises();

    expect(fetchMock.mock.calls.some(([url]) =>
      String(url).includes('/api/question-metrics/issues/filter-options?sourceInstance=dgm'),
    )).toBe(true);
    expect(fetchMock.mock.calls.some(([url]) =>
      String(url).includes('/api/question-metrics/issues?page=1&size=20&sourceInstance=dgm'),
    )).toBe(true);

    vi.unstubAllGlobals();
  });

  it('defers the first issue row request when an auto-restorable tag snapshot exists', async () => {
    window.localStorage.setItem('tag-groups:issue:default', JSON.stringify({
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
    const fetchMock = vi.fn((url: string) => {
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
    });
    vi.stubGlobal('fetch', fetchMock);

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

    const rowCalls = fetchMock.mock.calls
      .map(([url]) => String(url))
      .filter((url) => url.includes('/api/question-metrics/issues?'));
    expect(rowCalls.some((url) => url.endsWith('/api/question-metrics/issues?page=1&size=20'))).toBe(false);
    expect(rowCalls.some((url) => url.includes('tagSelections='))).toBe(true);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('keeps testing phase fixed filters and tag groups synchronized through the URL', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/question-metrics/issues/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          testingPhases: [
            { label: 'R1 Phase', value: 'R1' },
            { label: 'R2 Phase', value: 'R2' },
          ],
          authorNames: [],
          assigneeNames: [],
          issueStates: [],
          severityLevels: [],
          bugStatuses: [],
          categories: [],
          milestoneTitles: [],
        });
      }
      if (url.includes('/api/tag-groups')) {
        return jsonResponse(issueTagGroups);
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
    });
    vi.stubGlobal('fetch', fetchMock);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/question-metrics/issue-search', component: SystemTestIssueSearchView }],
    });
    await router.push('/question-metrics/issue-search?testingPhase=R1');
    await router.isReady();
    const wrapper = mount(SystemTestIssueSearchView, {
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.get('[data-testid="tag-group-filter-toggle"]').trigger('click');
    const selectedR1 = wrapper.findAll('.tag-group-filter-value')
      .find((button) => button.text().includes('R1 Phase'));
    expect(selectedR1?.classes()).toContain('is-selected');

    wrapper.findComponent({ name: 'BaseRecordTable' }).vm.$emit('filter-change', {
      key: 'testingPhase',
      value: 'R2',
    });
    await flushPromises();
    expect(JSON.parse(String(router.currentRoute.value.query.tagSelections))).toEqual([
      { groupKey: 'phase', valueKeys: ['R2'] },
    ]);

    wrapper.findComponent({ name: 'TagGroupFilter' }).vm.$emit('change', [
      { groupKey: 'phase', valueKeys: ['R1'] },
    ]);
    await flushPromises();
    expect(router.currentRoute.value.query.testingPhase).toBe('R1');

    await router.push('/question-metrics/issue-search?testingPhase=R2');
    await flushPromises();
    expect(JSON.parse(String(router.currentRoute.value.query.tagSelections))).toEqual([
      { groupKey: 'phase', valueKeys: ['R2'] },
    ]);

    wrapper.findComponent({ name: 'BaseRecordTable' }).vm.$emit('clear-filter', 'testingPhase');
    await flushPromises();
    expect(router.currentRoute.value.query.testingPhase).toBeUndefined();
    expect(JSON.parse(String(router.currentRoute.value.query.tagSelections))).toEqual([]);

    await router.push(`/question-metrics/issue-search?tagSelections=${encodeURIComponent(JSON.stringify([
      { groupKey: 'phase', valueKeys: ['R1'] },
    ]))}`);
    await flushPromises();
    expect(router.currentRoute.value.query.testingPhase).toBe('R1');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
