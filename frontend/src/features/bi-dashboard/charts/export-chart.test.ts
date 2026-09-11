import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => {
  const authorizeDownload = vi.fn(async () => undefined);
  const exportExcel = vi.fn(async (): Promise<{ blob: Blob; filename?: string }> => ({
    blob: new Blob(['xlsx-bytes'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
    filename: '按指派人统计缺陷数_20260910083015.xlsx',
  }));
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
  return { authorizeDownload, exportExcel, flush, setOption, getDataURL, dispose, init };
});

vi.mock('./bi-echarts-runtime', () => ({ init: mocks.init, registerTheme: vi.fn() }));
vi.mock('../../../api-client/bi-dashboard-api', () => ({
  biDashboardApi: { authorizeDownload: mocks.authorizeDownload, exportExcel: mocks.exportExcel },
}));

import { DeveloperWorkloadChart, SubmissionTrendComboChart } from './types';
import { exportBiChartExcel, exportBiChartPng } from './export-chart';

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

  it('sends the extracted table to the backend and downloads the workbook it returns', async () => {
    const downloads: string[] = [];
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function recordDownload(this: HTMLAnchorElement) {
        downloads.push(this.download);
      });

    await exportBiChartExcel({
      productVersionId: 10,
      productVersionName: 'CC2026R4',
      pageKey: 'system-test',
      sourceVersion: 'source-1',
      title: '按指派人统计缺陷数',
      description: '统计各处理人员被指派的缺陷总数。',
      chart: new DeveloperWorkloadChart(),
      data: [
        { name: '张三', total: 10, fixed: 8, open: 2 },
        { name: '李四', total: 5, fixed: 5, open: 0 },
      ],
    });

    // Excel 端点内部复用同一道下载授权门，前端不再单独调用授权端点。
    expect(mocks.authorizeDownload).not.toHaveBeenCalled();
    // 前端只按图表语义提取表格，序列化交给后端；比率字段忠实呈现图表数值（不再 *100）。
    // 问号词条的业务口径随行下发，由后端写入标题下的说明行。
    expect(mocks.exportExcel).toHaveBeenCalledWith({
      productVersionId: 10,
      pageKey: 'system-test',
      chartTemplateId: 'developer-workload',
      sourceVersion: 'source-1',
      title: '按指派人统计缺陷数',
      productVersionName: 'CC2026R4',
      explanation: '统计各处理人员被指派的缺陷总数。',
      headers: ['指派责任人', '缺陷总数', '已修复缺陷数', '待修复缺陷数', '修复率 (%)'],
      rows: [
        ['张三', 10, 8, 2, 80],
        ['李四', 5, 5, 0, 100],
      ],
    });
    // 优先采用服务端 Content-Disposition 提供的文件名。
    expect(downloads).toEqual(['按指派人统计缺陷数_20260910083015.xlsx']);

    click.mockRestore();
  });

  it('falls back to a sanitized local filename when the backend omits one', async () => {
    mocks.exportExcel.mockResolvedValueOnce({ blob: new Blob(['xlsx-bytes']), filename: undefined });
    const downloads: string[] = [];
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function recordDownload(this: HTMLAnchorElement) {
        downloads.push(this.download);
      });

    await exportBiChartExcel({
      productVersionId: 10,
      productVersionName: 'CC2026R4',
      pageKey: 'coding',
      sourceVersion: 'source-1',
      title: '评审/质量:分析',
      chart: new DeveloperWorkloadChart(),
      data: [{ name: '张三', total: 4, fixed: 2, open: 2 }],
    });

    // 无词条时仍传空串，后端不因此占行，不产生空行漂移。
    expect(mocks.exportExcel).toHaveBeenCalledWith(expect.objectContaining({ explanation: '' }));
    expect(downloads).toHaveLength(1);
    expect(downloads[0]).toMatch(/^评审-质量-分析_\d{14}\.xlsx$/);

    click.mockRestore();
  });
});
