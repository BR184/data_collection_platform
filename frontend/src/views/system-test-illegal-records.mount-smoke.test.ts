import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import SystemTestIllegalRecordsView from './SystemTestIllegalRecordsView.vue';

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

describe('SystemTestIllegalRecordsView mount smoke', () => {
  it('mounts without route errors and opens the detail drawer', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (url.includes('/api/question-metrics/illegal-records/filter-options')) {
          return jsonResponse({
            projectNames: [{ label: 'Rocksdb', value: 'Rocksdb' }],
            moduleNames: [{ label: 'Sketch', value: 'Sketch' }],
            testingPhases: [{ label: 'CC2026R1第一轮', value: 'CC2026R1第一轮' }],
            illegalReasons: [{ label: '未设定模块', value: '未设定模块' }],
            authorNames: [{ label: 'Alice', value: 'Alice' }],
            assigneeNames: [{ label: 'Bob', value: 'Bob' }],
            issueStates: [{ label: 'opened', value: 'opened' }],
            severityLevels: [{ label: 'LEVEL2', value: 'LEVEL2' }],
            bugStatuses: [{ label: '处理中', value: '处理中' }],
            categories: [{ label: '功能缺陷', value: '功能缺陷' }],
            milestoneTitles: [{ label: 'CC2026R1', value: 'CC2026R1' }],
          });
        }
        if (url.includes('/api/question-metrics/illegal-records?')) {
          return jsonResponse({
            records: [
              {
                issueId: 9301,
                issueIid: 301,
                issueLink: 'http://gitlab.example.com/-/issues/301',
                projectId: 1001,
                projectName: 'Rocksdb',
                title: 'System illegal sample',
                issueState: 'opened',
                testingPhase: 'CC2026R1第一轮系统测试',
                illegalReason: '未设定模块',
                severityLevel: 'LEVEL2',
                bugStatus: '处理中',
                category: '功能缺陷',
                milestoneTitle: 'CC2026R1',
                authorName: 'Alice',
                assigneeName: 'Bob',
                moduleNames: 'Sketch',
                createdAt: '2026-04-24T08:00:00',
                updatedAt: '2026-04-24T09:00:00',
                closedAt: null,
                labels: ['系统测试', 'CC2026R1第一轮系统测试'],
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
          path: '/question-metrics/illegal-records',
          component: SystemTestIllegalRecordsView,
          meta: { pageKey: 'question-metrics-illegal-records' },
        },
      ],
    });

    await router.push('/question-metrics/illegal-records?projectId=1001');
    await router.isReady();

    const wrapper = mount(SystemTestIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();
    expect(wrapper.exists()).toBe(true);
    expect(wrapper.text()).toContain('System illegal sample');
    expect(wrapper.text()).toContain('未设定模块');
    expect(wrapper.text()).toContain('测试阶段');

    await wrapper.get('.issue-illegal-detail-trigger').trigger('click');
    await flushPromises();

    expect(document.body.textContent).toContain('系统测试非法数据');
    expect(document.body.textContent).toContain('System illegal sample');
    expect(document.body.textContent).toContain('CC2026R1第一轮系统测试');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('exports current filtered illegal records as csv', async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url.includes('/api/question-metrics/illegal-records/export')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('issue_iid,illegal_reason\n301,missing module\n') } as Response);
      }
      if (url.includes('/api/question-metrics/illegal-records/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          testingPhases: [],
          illegalReasons: [],
          authorNames: [],
          assigneeNames: [],
          issueStates: [],
          severityLevels: [],
          bugStatuses: [],
          categories: [],
          milestoneTitles: [],
        });
      }
      if (url.includes('/api/question-metrics/illegal-records?')) {
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
      routes: [
        {
          path: '/question-metrics/illegal-records',
          component: SystemTestIllegalRecordsView,
          meta: { pageKey: 'question-metrics-illegal-records' },
        },
      ],
    });

    await router.push('/question-metrics/illegal-records?projectId=1001&keyword=sample&illegalReason=missing%20module&sortBy=updatedAt&sortOrder=desc');
    await router.isReady();

    const wrapper = mount(SystemTestIllegalRecordsView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });
    await flushPromises();

    await wrapper.findAll('button').find((button) => button.text().includes('导出'))?.trigger('click');
    await flushPromises();

    const exportCall = fetchMock.mock.calls.find(([url]) => String(url).includes('/api/question-metrics/illegal-records/export'));
    expect(String(exportCall?.[0])).toContain('projectId=1001');
    expect(String(exportCall?.[0])).toContain('keyword=sample');
    expect(String(exportCall?.[0])).toContain('illegalReason=missing+module');
    expect(String(exportCall?.[0])).not.toContain('page=');

    wrapper.unmount();
    clickSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
