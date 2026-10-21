import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import { readViewsApi } from '../../api-client/read-views-api';
import type { ReadViewMetadata } from '../../types/api/read-view';
import type { IssueIllegalRecordRow, IssueIllegalRecordsPageConfig } from './issue-illegal-records-types';
import IssueIllegalRecordsPage from './IssueIllegalRecordsPage.vue';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); document.body.innerHTML = ''; });

describe('illegal records complete page reads', () => {
  it.each(['system-test-illegal-records', 'customer-issues-illegal-records'])(
    '%s keeps opened details and rows when preparing or failing a new version', async pageKey => {
      vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
      const identities = new Map<string, ReadViewMetadata>();
      vi.spyOn(readViewsApi, 'acquire').mockImplementation(async () => {
        const readContextId = crypto.randomUUID();
        const metadata: ReadViewMetadata = { readContextId, pageKey, scopeKey: 'fixed', sourceVersion: readContextId,
          businessDate: '2026-10-13', dataAsOf: '2026-10-13T00:00:00Z', leaseExpiresAt: '2099-01-01T00:00:00Z',
          pendingUpdates: 0, sourceState: 'CURRENT', escalated: false };
        identities.set(readContextId, metadata); return metadata;
      });
      vi.spyOn(readViewsApi, 'release').mockResolvedValue(undefined);
      const row: IssueIllegalRecordRow = { issueId: 1, issueIid: 101, sourceInstance: 'default', projectId: 9,
        projectName: '测试项目', title: '完整旧非法议题', issueState: 'opened', illegalReason: '缺少必填项',
        severityLevel: '', bugStatus: '', category: '', milestoneTitle: '', authorName: '', assigneeName: '', moduleNames: '', labels: [] };
      const loadRecords = vi.fn<IssueIllegalRecordsPageConfig['loadRecords']>(async params => {
        params.read!.onReadView(identities.get(params.read!.readContextId)!);
        return { records: [row], total: 1, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc' };
      });
      let failDetail!: (error: Error) => void;
      const loadRecord = vi.fn<IssueIllegalRecordsPageConfig['loadRecord']>(() =>
        new Promise((_resolve, reject) => { failDetail = reject; }));
      const options = { projectNames: [], moduleNames: [], illegalReasons: [], severityLevels: [], issueStates: [],
        bugStatuses: [], categories: [], milestoneTitles: [] };
      const props: IssueIllegalRecordsPageConfig = { pageKey, workspaceKey: pageKey, title: '非法议题', description: '',
        detailKicker: '', ruleTitle: '规则', emptyDescription: '无匹配记录', totalTagText: count => `${count} 条`,
        loadRecords, loadRecord, initialFilterOptions: options, buildConditionFields: () => [],
        loadFilterOptions: async (_project, read) => { read!.onReadView(identities.get(read!.readContextId)!); return options; },
        loadRuleExplanation: vi.fn(), loadRealtimeStatus: async () => ({ workspaceKey: pageKey, supported: true,
          status: 'SUCCESS', message: '', refreshing: false, lastSyncedAt: null }),
        columns: [{ key: 'title', label: '标题' }], mapRow: item => ({ __raw: item, title: item.title }),
        resetClearKeys: [], queryClearKeys: [] };
      const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/illegal', component: IssueIllegalRecordsPage }] });
      await router.push('/illegal'); await router.isReady();
      const wrapper = mount(IssueIllegalRecordsPage, { props, attachTo: document.body, global: { plugins: [router, ElementPlus] } });
      await flushPromises();
      const detail = wrapper.findAll('button').filter(button => button.text().includes('查看详情')).at(-1)!;
      await detail.trigger('click'); await flushPromises();
      loadRecords.mockImplementation(async params => {
        params.read!.onReadView(identities.get(params.read!.readContextId)!);
        return { records: [{ ...row, title: '未接受的新议题' }], total: 1, page: 1, size: 20, sortField: 'updatedAt', sortOrder: 'desc' };
      });
      const refresh = wrapper.findAll('button').find(button => button.text() === '刷新')!;
      await refresh.trigger('click'); await flushPromises();
      expect(wrapper.text()).toContain('完整旧非法议题');
      expect(wrapper.text()).not.toContain('未接受的新议题');
      expect(document.body.textContent).toContain('完整旧非法议题');
      expect(loadRecord).toHaveBeenCalledWith('default', 9, 1, expect.objectContaining({ readContextId: expect.any(String) }));
      failDetail(new Error('新版本详情尚未完成')); await flushPromises();
      expect(wrapper.text()).toContain('完整旧非法议题');
      expect(wrapper.text()).not.toContain('未接受的新议题');
      expect(document.body.textContent).toContain('完整旧非法议题');
      wrapper.unmount();
    });
});
