import { describe, expect, it, vi } from 'vitest';
import type { ReviewDataRecordListResponse, ReviewDataRecordRowResponse } from '../../types/api';
import { useReviewDataExport } from './useReviewDataExport';

function record(id: number): ReviewDataRecordRowResponse {
  return {
    id,
    projectName: 'project',
    title: `review-${id}`,
    moduleName: 'module',
    reviewType: 'design',
    reviewDate: '2026-04-27',
    reviewOwner: 'owner',
    reviewExpertsSummary: 'expert',
    reviewScalePages: 10,
    reviewProduct: 'doc',
    authorName: 'author',
    reviewVersion: 'v1',
    problemCount: 1,
    problemDensity: 0.1,
    updatedAt: '2026-04-27T10:00:00',
    deleted: false,
  };
}

function response(records: ReviewDataRecordRowResponse[], total: number): ReviewDataRecordListResponse {
  return {
    records,
    total,
    page: 1,
    size: 100,
    sortField: 'updatedAt',
    sortOrder: 'desc',
    summary: {
      totalRecords: total,
      totalProblemItems: 0,
      averageReviewScalePages: 0,
      averageProblemCount: 0,
    },
  };
}

function setup() {
  return {
    fetchRecords: vi.fn<(page: number, size: number) => Promise<ReviewDataRecordListResponse>>(),
    buildCsv: vi.fn<(rows: ReviewDataRecordRowResponse[]) => string>((rows) => rows.map((row) => row.title).join(',')),
    downloadCsv: vi.fn<(csv: string, filename: string) => void>(),
    getExpectedTotal: vi.fn<() => number>(() => 0),
    now: vi.fn<() => Date>(() => new Date('2026-04-27T08:09:10')),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewDataExport', () => {
  it('exports all pages into a dated csv file', async () => {
    const deps = setup();
    deps.getExpectedTotal.mockReturnValue(3);
    deps.fetchRecords
      .mockResolvedValueOnce(response([record(1), record(2)], 3))
      .mockResolvedValueOnce(response([record(3)], 3));
    const exporter = useReviewDataExport(deps);

    await exporter.exportExcel();

    expect(deps.fetchRecords).toHaveBeenNthCalledWith(1, 1, 100);
    expect(deps.fetchRecords).toHaveBeenNthCalledWith(2, 2, 100);
    expect(deps.buildCsv).toHaveBeenCalledWith([record(1), record(2), record(3)]);
    expect(deps.downloadCsv).toHaveBeenCalledWith('review-1,review-2,review-3', '评审数据管理_20260427080910.csv');
    expect(deps.notifySuccess).toHaveBeenCalledWith('已导出 3 条评审记录');
    expect(exporter.exportLoading.value).toBe(false);
  });

  it('uses the first page total when the current list total is stale', async () => {
    const deps = setup();
    deps.getExpectedTotal.mockReturnValue(1);
    deps.fetchRecords
      .mockResolvedValueOnce(response([record(1), record(2)], 3))
      .mockResolvedValueOnce(response([record(3)], 3));
    const exporter = useReviewDataExport(deps);

    await exporter.exportExcel();

    expect(deps.fetchRecords).toHaveBeenCalledTimes(2);
    expect(deps.notifySuccess).toHaveBeenCalledWith('已导出 3 条评审记录');
  });

  it('reports export failure and clears loading state', async () => {
    const deps = setup();
    deps.fetchRecords.mockRejectedValue(new Error('network failed'));
    const exporter = useReviewDataExport(deps);

    await exporter.exportExcel();

    expect(deps.downloadCsv).not.toHaveBeenCalled();
    expect(deps.notifyError).toHaveBeenCalledWith('network failed');
    expect(exporter.exportLoading.value).toBe(false);
  });
});
