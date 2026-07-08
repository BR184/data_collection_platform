import { describe, expect, it, vi } from 'vitest';
import { useReviewDataExport } from './useReviewDataExport';

function setup() {
  return {
    exportReviewRecords: vi.fn<() => Promise<Blob>>(() => Promise.resolve(new Blob(['records']))),
    exportProblemDetails: vi.fn<() => Promise<Blob>>(() => Promise.resolve(new Blob(['problems']))),
    downloadTemplate: vi.fn<() => Promise<Blob>>(() => Promise.resolve(new Blob(['template']))),
    downloadWorkbook: vi.fn<(blob: Blob, filename: string) => void>(),
    now: vi.fn<() => Date>(() => new Date('2026-04-27T08:09:10')),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewDataExport', () => {
  it('downloads review record workbook with the legacy filename', async () => {
    const deps = setup();
    const exporter = useReviewDataExport(deps);

    await exporter.exportReviewRecords();

    expect(deps.exportReviewRecords).toHaveBeenCalledOnce();
    expect(deps.downloadWorkbook).toHaveBeenCalledWith(expect.any(Blob), expect.stringMatching(/^评审数据.*\.xlsx$/));
    expect(deps.notifySuccess).toHaveBeenCalledWith('已导出评审列表');
    expect(exporter.recordExportLoading.value).toBe(false);
  });

  it('downloads problem detail workbook with the legacy filename', async () => {
    const deps = setup();
    const exporter = useReviewDataExport(deps);

    await exporter.exportProblemDetails();

    expect(deps.exportProblemDetails).toHaveBeenCalledOnce();
    expect(deps.downloadWorkbook).toHaveBeenCalledWith(expect.any(Blob), '评审问题详情.xlsx');
    expect(deps.notifySuccess).toHaveBeenCalledWith('已导出问题列表');
    expect(exporter.problemExportLoading.value).toBe(false);
  });

  it('reports export failure and clears loading state', async () => {
    const deps = setup();
    deps.exportProblemDetails.mockRejectedValue(new Error('network failed'));
    const exporter = useReviewDataExport(deps);

    await exporter.exportProblemDetails();

    expect(deps.downloadWorkbook).not.toHaveBeenCalled();
    expect(deps.notifyError).toHaveBeenCalledWith('network failed');
    expect(exporter.problemExportLoading.value).toBe(false);
  });

  it('downloads review template workbook with legacy filename', async () => {
    const deps = setup();
    const exporter = useReviewDataExport(deps);

    await exporter.downloadTemplate();

    expect(deps.downloadTemplate).toHaveBeenCalledOnce();
    expect(deps.downloadWorkbook).toHaveBeenCalledWith(expect.any(Blob), '模板文件.xls');
    expect(deps.notifySuccess).toHaveBeenCalledWith('已下载评审模板');
    expect(exporter.templateDownloadLoading.value).toBe(false);
  });
});
