import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => {
  const authorizeDownload = vi.fn(async () => undefined);
  const flush = vi.fn();
  const setOption = vi.fn();
  const getDataURL = vi.fn(() => 'data:image/png;base64,AAAA');
  const dispose = vi.fn();
  const init = vi.fn(() => ({
    setOption,
    getZr: () => ({ flush }),
    getDataURL,
    dispose,
  }));
  return { authorizeDownload, flush, setOption, getDataURL, dispose, init };
});

vi.mock('./bi-echarts-runtime', () => ({ init: mocks.init, registerTheme: vi.fn() }));
vi.mock('../../../api-client/bi-dashboard-api', () => ({
  biDashboardApi: { authorizeDownload: mocks.authorizeDownload },
}));

import { DeveloperWorkloadChart, SubmissionTrendComboChart } from './types';
import { buildChartExcelData, exportBiChartExcel, exportBiChartPng } from './export-chart';

describe('BI chart export', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders complete data without animation before reading the canvas for PNG', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const initialChildren = document.body.childElementCount;

    await exportBiChartPng({
      productVersionId: 10,
      pageKey: 'coding',
      sourceVersion: 'source-1',
      title: '提交趋势',
      chart: new SubmissionTrendComboChart(),
      data: {
        periods: Array.from({ length: 16 }, (_, index) => `07-${String(index + 1).padStart(2, '0')}`),
        commits: Array.from({ length: 16 }, (_, index) => index + 1),
        mergeRequests: Array.from({ length: 16 }, (_, index) => index),
      },
    });

    expect(mocks.authorizeDownload).toHaveBeenCalledWith(10, 'coding', 'submission-trend-combo', 'source-1');
    const option = mocks.setOption.mock.calls[0]?.[0] as { animation?: boolean; dataZoom?: unknown[] };
    expect(option.animation).toBe(false);
    expect(option.dataZoom).toEqual([]);
    expect(mocks.flush).toHaveBeenCalledOnce();
    expect(mocks.flush.mock.invocationCallOrder[0]).toBeLessThan(mocks.getDataURL.mock.invocationCallOrder[0]!);
    expect(click).toHaveBeenCalledOnce();
    expect(mocks.dispose).toHaveBeenCalledOnce();
    expect(document.body.childElementCount).toBe(initialChildren);

    click.mockRestore();
  });

  it('extracts structured table data and triggers Excel XML download', async () => {
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    await exportBiChartExcel({
      productVersionId: 10,
      productVersionName: 'CC2026R3',
      pageKey: 'system-test',
      sourceVersion: 'source-1',
      title: '按指派人统计缺陷数',
      chart: new DeveloperWorkloadChart(),
      data: [
        { name: '张三', total: 10, fixed: 8, open: 2 },
        { name: '李四', total: 5, fixed: 5, open: 0 },
      ],
    });

    expect(mocks.authorizeDownload).toHaveBeenCalledWith(10, 'system-test', 'developer-workload', 'source-1');
    expect(click).toHaveBeenCalledOnce();

    const table = buildChartExcelData('developer-workload', [
      { name: '张三', total: 10, fixed: 8, open: 2 },
    ]);
    expect(table.headers).toEqual(['指派责任人', '缺陷总数', '已修复缺陷数', '待修复缺陷数', '修复率 (%)']);
    expect(table.rows[0]).toEqual(['张三', 10, 8, 2, 80]);

    click.mockRestore();
  });
});
