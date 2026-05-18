import { defineComponent } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createRouter, createWebHashHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import QualityBoardRdView from './QualityBoardRdView.vue';

vi.mock('../components/charts/EChartPanel.vue', () => ({
  default: defineComponent({
    name: 'EChartPanel',
    template: '<div data-testid="echart-panel" />',
  }),
}));

function jsonResponse(data: unknown) {
  return Promise.resolve({
    ok: true,
    text: () => Promise.resolve(JSON.stringify({ success: true, data })),
  } as Response);
}

function createBoard(rows: Array<{ rowKey: string; rowLabel: string; cells: Array<{ columnKey: string; numericValue: number; displayValue: string; drilldown: boolean; detailParams: Record<string, string>; }> }>) {
  return {
    definition: {
      boardKey: 'test-board',
      title: 'test',
      description: '',
      queryTitle: '',
      queryDescription: '',
      rowHeaderLabel: '统计对象',
      filters: [],
      columnGroups: [],
      detailColumns: [],
      defaultPageSize: 10,
      emptyText: '',
    },
    appliedFilters: {},
    appliedFilterGroup: null,
    rows,
    meta: {
      generatedAt: '2026-04-27T10:00:00',
      queryDurationMs: 12,
      rowCount: rows.length,
      columnCount: 0,
      drilldownColumnCount: 0,
    },
  };
}

function cell(columnKey: string, numericValue: number) {
  return {
    columnKey,
    numericValue,
    displayValue: String(numericValue),
    drilldown: false,
    detailParams: {},
  };
}

describe('QualityBoardRdView mount smoke', () => {
  it('loads summary sources and renders the quality overview', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/review-data/records/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          reviewOwners: [],
          reviewTypes: [
            { label: '需求评审', value: '需求评审' },
            { label: '设计评审', value: '设计评审' },
          ],
          reviewExperts: [],
          problemStatuses: [],
          reviewCategories: [],
          problemCategories: [],
        });
      }
      if (url.includes('/api/review-data/records?')) {
        if (url.includes('reviewType=%E9%9C%80%E6%B1%82')) {
          return jsonResponse({
            records: [],
            total: 1,
            page: 1,
            size: 1,
            sortField: 'updatedAt',
            sortOrder: 'desc',
            summary: {
              totalRecords: 4,
              totalProblemItems: 8,
              averageReviewScalePages: 10,
              averageProblemCount: 2,
            },
          });
        }
        return jsonResponse({
          records: [],
          total: 1,
          page: 1,
          size: 1,
          sortField: 'updatedAt',
          sortOrder: 'desc',
          summary: {
            totalRecords: 2,
            totalProblemItems: 4,
            averageReviewScalePages: 8,
            averageProblemCount: 2,
          },
        });
      }
      if (url.includes('/api/code-review/multi-board/source-options')) {
        return jsonResponse([
          { label: 'CC', value: 'cc' },
          { label: 'DGM', value: 'dgm' },
        ]);
      }
      if (url.includes('/api/code-review/multi-board/overview?source=cc')) {
        return jsonResponse({
          source: 'cc',
          sourceLabel: 'CC',
          mergeRequestCount: 8,
          completedCount: 6,
          pendingCount: 2,
          averageCommentRate: 18,
          totalDefectCount: 7,
          totalAddedLines: 320,
          defectDensityPerKloc: 21.88,
          averageReviewDurationMinutes: 16,
          averageAddedLines: 40,
          moduleRows: [],
          ownerRows: [],
        });
      }
      if (url.includes('/api/code-review/multi-board/overview?source=dgm')) {
        return jsonResponse({
          source: 'dgm',
          sourceLabel: 'DGM',
          mergeRequestCount: 5,
          completedCount: 4,
          pendingCount: 1,
          averageCommentRate: 15,
          totalDefectCount: 3,
          totalAddedLines: 210,
          defectDensityPerKloc: 14.29,
          averageReviewDurationMinutes: 14,
          averageAddedLines: 42,
          moduleRows: [],
          ownerRows: [],
        });
      }
      if (url.includes('/api/integration-tests/project-options')) {
        return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT' }]);
      }
      if (url.includes('/api/integration-tests/phase-options')) {
        return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT', testingPhase: 'R1集成测试', recordCount: 3 }]);
      }
      if (url.includes('/api/integration-tests/summary')) {
        return jsonResponse({
          projectId: 325,
          testingPhase: 'R1集成测试',
          moduleCount: 2,
          totalIssueCount: 3,
          factRefreshedAt: '2026-04-27T10:00:00',
          rows: [
            { moduleName: '支付中心', issueCount: 1, executeCase: 10, passCase: 8, notPassCase: 2, notPassCaseNow: 2, problemCase: 1, exceptionCount: 0, passRate: 80, illegalCount: 0 },
          ],
        });
      }
      if (url.includes('/api/statistic-boards/system-test-defect-summary')) {
        return jsonResponse(
          createBoard([
            { rowKey: 'module-a', rowLabel: '支付中心', cells: [cell('module_total', 10), cell('fix_rate', 80)] },
            {
              rowKey: '__total__',
              rowLabel: '总计',
              cells: [cell('module_total', 10), cell('open_count', 1), cell('solved_count', 8), cell('extension_count', 1)],
            },
          ]),
        );
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/quality-board/rd-quality-board', component: QualityBoardRdView, meta: { pageKey: 'quality-board-rd-quality-board' } }],
    });

    await router.push('/quality-board/rd-quality-board');
    await router.isReady();

    const wrapper = mount(QualityBoardRdView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('研发质量一屏概览');
    expect(wrapper.text()).toContain('评审密度对比');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(4);

    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it('keeps the page usable when one summary section fails', async () => {
    const fetchSpy = vi.fn((url: string) => {
      if (url.includes('/api/statistic-boards/system-test-defect-summary')) {
        return Promise.resolve({
          ok: false,
          text: () => Promise.resolve(JSON.stringify({ success: false, message: '系统测试看板超时' })),
        } as Response);
      }
      if (url.includes('/api/review-data/records/filter-options')) {
        return jsonResponse({
          projectNames: [],
          moduleNames: [],
          reviewOwners: [],
          reviewTypes: [
            { label: '需求评审', value: '需求评审' },
            { label: '设计评审', value: '设计评审' },
          ],
          reviewExperts: [],
          problemStatuses: [],
          reviewCategories: [],
          problemCategories: [],
        });
      }
      if (url.includes('/api/review-data/records?')) {
        return jsonResponse({
          records: [],
          total: 1,
          page: 1,
          size: 1,
          sortField: 'updatedAt',
          sortOrder: 'desc',
          summary: {
            totalRecords: 2,
            totalProblemItems: 4,
            averageReviewScalePages: 8,
            averageProblemCount: 2,
          },
        });
      }
      if (url.includes('/api/code-review/multi-board/source-options')) {
        return jsonResponse([{ label: 'CC', value: 'cc' }]);
      }
      if (url.includes('/api/code-review/multi-board/overview?source=cc')) {
        return jsonResponse({
          source: 'cc',
          sourceLabel: 'CC',
          mergeRequestCount: 8,
          completedCount: 6,
          pendingCount: 2,
          averageCommentRate: 18,
          totalDefectCount: 7,
          totalAddedLines: 320,
          defectDensityPerKloc: 21.88,
          averageReviewDurationMinutes: 16,
          averageAddedLines: 40,
          moduleRows: [],
          ownerRows: [],
        });
      }
      if (url.includes('/api/integration-tests/project-options')) {
        return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT' }]);
      }
      if (url.includes('/api/integration-tests/phase-options')) {
        return jsonResponse([{ projectId: 325, projectName: 'CC_PRODUCT', testingPhase: 'R1集成测试', recordCount: 3 }]);
      }
      if (url.includes('/api/integration-tests/summary')) {
        return jsonResponse({
          projectId: 325,
          testingPhase: 'R1集成测试',
          moduleCount: 2,
          totalIssueCount: 3,
          factRefreshedAt: '2026-04-27T10:00:00',
          rows: [],
        });
      }
      return jsonResponse({});
    });
    vi.stubGlobal('fetch', fetchSpy);
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [{ path: '/quality-board/rd-quality-board', component: QualityBoardRdView, meta: { pageKey: 'quality-board-rd-quality-board' } }],
    });

    await router.push('/quality-board/rd-quality-board');
    await router.isReady();

    const wrapper = mount(QualityBoardRdView, {
      attachTo: document.body,
      global: { plugins: [router, ElementPlus] },
    });

    await flushPromises();

    expect(wrapper.text()).toContain('研发质量一屏概览');
    expect(wrapper.findAll('[data-testid="echart-panel"]')).toHaveLength(4);
    expect(warnSpy).toHaveBeenCalledWith('系统测试摘要 加载失败', expect.any(Error));

    wrapper.unmount();
    warnSpy.mockRestore();
    vi.unstubAllGlobals();
  });
});
