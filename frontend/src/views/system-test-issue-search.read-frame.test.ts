import { defineComponent } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { api } from '../api';
import { readViewsApi } from '../api-client/read-views-api';
import type { ReadViewMetadata } from '../types/api/read-view';
import type { SystemTestIssueSearchListResponse, SystemTestIssueSearchFilterOptionsResponse } from '../types/api';
import SystemTestIssueSearchView from './SystemTestIssueSearchView.vue';

vi.mock('../components/base/BaseRecordTable.vue', () => ({ default: defineComponent({
  props: ['rows', 'primaryFilters', 'loading', 'page'],
  template: '<section><p v-for="row in rows" :key="row.identityKey">{{ row.title }}</p><span>{{ JSON.stringify(primaryFilters) }}</span><i>{{ loading }}</i></section>',
}) }));

const options: SystemTestIssueSearchFilterOptionsResponse = { projectNames: [], moduleNames: [], functionNames: [],
  testingPhases: [], authorNames: [], assigneeNames: [], issueStates: [], severityLevels: [], bugStatuses: [], categories: [], milestoneTitles: [] };
function records(title: string): SystemTestIssueSearchListResponse {
  return { records: [{ issueId: 1, issueIid: 101, sourceInstance: 'default', projectId: 2, projectName: '项目', title,
    issueState: 'opened', testingPhase: '', severityLevel: '', priorityLevel: '', bugStatus: '', category: '', milestoneTitle: '',
    delayCause: '', authorName: '', assigneeName: '', moduleNames: '', functionName: '', labels: [] }],
  page: 1, size: 20, total: 1, sortField: 'updatedAt', sortOrder: 'desc' };
}

afterEach(() => { vi.restoreAllMocks(); });

describe('system issue complete page reads', () => {
  it('keeps the old rows and candidates during a new preparation and after failure', async () => {
    const metadata: ReadViewMetadata = { readContextId: crypto.randomUUID(), pageKey: 'system-test-issues', scopeKey: 'all',
      sourceVersion: 'complete', businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z',
      leaseExpiresAt: '2099-01-01T00:00:00Z', pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
    vi.spyOn(readViewsApi, 'acquire').mockResolvedValue(metadata);
    vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
    vi.spyOn(api, 'getSystemTestIssueSearchRealtimeStatus').mockResolvedValue({ workspaceKey: 'system-test-issues', supported: true,
      refreshing: false, status: 'SUCCESS', message: '', lastSyncedAt: null });
    const readRecords = vi.spyOn(api, 'getSystemTestIssueSearchRecords').mockImplementation(async params => {
      params.read!.onReadView(metadata); return records('完整旧议题');
    });
    vi.spyOn(api, 'getSystemTestIssueSearchFilterOptions').mockResolvedValue({ ...options,
      moduleNames: [{ label: '完整旧模块', value: '完整旧模块' }] });
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/issues', component: SystemTestIssueSearchView }] });
    await router.push('/issues'); await router.isReady();
    const wrapper = mount(SystemTestIssueSearchView, { global: { plugins: [router] } });
    await flushPromises();
    expect(wrapper.text()).toContain('完整旧议题');
    expect(wrapper.text()).toContain('完整旧模块');
    let reject!: (error: Error) => void;
    readRecords.mockImplementation(() => new Promise((_resolve, fail) => { reject = fail; }));
    await router.replace('/issues?keyword=新请求'); await flushPromises();
    expect(wrapper.text()).toContain('完整旧议题');
    expect(wrapper.text()).toContain('完整旧模块');
    expect(wrapper.text()).toContain('false');
    reject(new Error('新发布点尚未完成')); await flushPromises();
    expect(wrapper.text()).toContain('完整旧议题');
    expect(wrapper.text()).toContain('完整旧模块');
    wrapper.unmount();
  });
});
