import { describe, expect, it, vi } from 'vitest';
import { useReviewDataExport } from './useReviewDataExport';

function setup() {
  return {
    exportReviewRecords: vi.fn<() => Promise<Blob>>(() => Promise.resolve(new Blob(['records']))),
    exportProblemDetails: vi.fn<() => Promise<Blob>>(() => Promise.resolve(new Blob(['problems']))),
    downloadWorkbook: vi.fn<(blob: Blob, filename: string) => void>(),
    now: vi.fn<() => Date>(() => new Date('2026-04-27T08:09:10')),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewDataExport', () => {
  it('downloads review record workbook with a dated filename', async () => {
    const deps = setup();
    const exporter = useReviewDataExport(deps);

    await exporter.exportReviewRecords();

    expect(deps.exportReviewRecords).toHaveBeenCalledOnce();
    expect(deps.downloadWorkbook).toHaveBeenCalledWith(expect.any(Blob), '评审数据管理_20260427080910.xlsx');
    expect(deps.notifySuccess).toHaveBeenCalledWith('已导出评审列表');
    expect(exporter.recordExportLoading.value).toBe(false);
  });

  it('downloads problem detail workbook with a dated filename', async () => {
    const deps = setup();
    const exporter = useReviewDataExport(deps);

    await exporter.exportProblemDetails();

    expect(deps.exportProblemDetails).toHaveBeenCalledOnce();
    expect(deps.downloadWorkbook).toHaveBeenCalledWith(expect.any(Blob), '评审问题详情_20260427080910.xlsx');
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
});
