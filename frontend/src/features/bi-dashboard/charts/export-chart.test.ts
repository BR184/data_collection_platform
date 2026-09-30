import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { BiChartDownloadContext } from '../data/types';

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
      downloadContext: {
        pageKey: 'coding',
        chartInstanceId: 'coding-submission-trend',
        sourceVersion: 'source-1',
        scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
      },
      title: '提交趋势',
      chart: new SubmissionTrendComboChart(),
      data: {
        periods: Array.from({ length: 16 }, (_, index) => `07-${String(index + 1).padStart(2, '0')}`),
        commits: Array.from({ length: 16 }, (_, index) => index + 1),
        mergeRequests: Array.from({ length: 16 }, (_, index) => index),
      },
    });

    expect(mocks.authorizeDownload).toHaveBeenCalledWith({
      pageKey: 'coding',
      chartInstanceId: 'coding-submission-trend',
      sourceVersion: 'source-1',
      scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
      chartTemplateId: 'submission-trend-combo',
    });
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
      downloadContext: {
        pageKey: 'system-test',
        chartInstanceId: 'system-test-assignee-workload',
        sourceVersion: 'source-1',
        scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
      },
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
      scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
      pageKey: 'system-test',
      chartInstanceId: 'system-test-assignee-workload',
      chartTemplateId: 'developer-workload',
      sourceVersion: 'source-1',
      title: '按指派人统计缺陷数',
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
      downloadContext: {
        pageKey: 'coding',
        chartInstanceId: 'coding-review-scatter',
        sourceVersion: 'source-1',
        scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
      },
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

  it('freezes the clicked PNG data and scope while authorization is pending', async () => {
    let authorize!: () => void;
    mocks.authorizeDownload.mockImplementationOnce(
      () => new Promise<undefined>((resolve) => { authorize = () => resolve(undefined); }),
    );
    const data = {
      periods: ['2026-09-01', '2026-09-02'],
      commits: [2, 3],
      mergeRequests: [1, 2],
    };
    const downloadContext: BiChartDownloadContext = {
      pageKey: 'coding' as const,
      chartInstanceId: 'coding-submission-trend',
      sourceVersion: 'source-before',
      scope: { rangeType: 'PRODUCT_VERSION' as const, productVersionId: 10 },
    };

    const pending = exportBiChartPng({
      downloadContext,
      title: '提交趋势',
      chart: new SubmissionTrendComboChart(),
      data,
    });
    data.periods[0] = '2026-10-01';
    downloadContext.scope = { rangeType: 'PRODUCT_VERSION', productVersionId: 99 };
    downloadContext.sourceVersion = 'source-after';
    authorize();
    await pending;

    expect(mocks.authorizeDownload).toHaveBeenCalledWith(expect.objectContaining({
      chartInstanceId: 'coding-submission-trend',
      sourceVersion: 'source-before',
      scope: { rangeType: 'PRODUCT_VERSION', productVersionId: 10 },
    }));
    const option = mocks.setOption.mock.calls[0]?.[0] as { xAxis: { data: string[] } };
    expect(option.xAxis.data[0]).toBe('2026-09-01');
  });

  it('freezes customer filters, source version and Excel rows at click time', async () => {
    let finishExport!: () => void;
    mocks.exportExcel.mockImplementationOnce(() => new Promise((resolve) => {
      finishExport = () => resolve({ blob: new Blob(['xlsx-bytes']), filename: 'customer.xlsx' });
    }));
    const rows = [{ name: '张三', total: 4, fixed: 2, open: 2 }];
    const downloadContext: BiChartDownloadContext = {
      pageKey: 'customer-issues' as const,
      chartInstanceId: 'customer-issue-assignee-workload',
      sourceVersion: 'customer-v1',
      scope: {
        rangeType: 'CUSTOMER_ISSUE' as const,
        milestoneBusinessKey: 'mile-1',
        businessDate: '2026-09-24',
        customerKind: 'VALUE' as const,
        customer: '客户甲',
        moduleKind: 'ALL' as const,
        functionKind: 'MISSING' as const,
      },
    };
    const pending = exportBiChartExcel({
      downloadContext,
      title: '客户缺陷负荷',
      chart: new DeveloperWorkloadChart(),
      data: rows,
    });
    rows[0]!.name = '客户乙';
    downloadContext.scope = {
      rangeType: 'CUSTOMER_ISSUE', milestoneBusinessKey: 'mile-2', businessDate: '2026-09-25',
      customerKind: 'ALL', moduleKind: 'ALL', functionKind: 'ALL',
    };
    downloadContext.sourceVersion = 'customer-v2';
    finishExport();
    await pending;

    expect(mocks.exportExcel).toHaveBeenCalledWith(expect.objectContaining({
      chartInstanceId: 'customer-issue-assignee-workload',
      sourceVersion: 'customer-v1',
      scope: expect.objectContaining({
        rangeType: 'CUSTOMER_ISSUE', milestoneBusinessKey: 'mile-1', businessDate: '2026-09-24',
        customerKind: 'VALUE', customer: '客户甲', moduleKind: 'ALL', functionKind: 'MISSING',
      }),
      rows: [['张三', 4, 2, 2, 50]],
    }));
  });
});
