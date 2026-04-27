import { describe, expect, it, vi } from 'vitest';
import type { ReviewDataRecordDetailResponse, ReviewDataRecordRowResponse } from '../../types/api';
import { useReviewDataDetail } from './useReviewDataDetail';

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

function detail(recordId: number): ReviewDataRecordDetailResponse {
  return {
    record: record(recordId),
    reviewExperts: ['Ada'],
    problemItems: [],
  };
}

function setup() {
  return {
    loadRecordDetail: vi.fn<(recordId: number) => Promise<ReviewDataRecordDetailResponse>>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewDataDetail', () => {
  it('loads detail before opening the drawer', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(5));
    const detailState = useReviewDataDetail(deps);

    await detailState.openDetail(5);

    expect(deps.loadRecordDetail).toHaveBeenCalledWith(5);
    expect(detailState.detailVisible.value).toBe(true);
    expect(detailState.detailData.value?.record.id).toBe(5);
  });

  it('reports detail loading failures without opening the drawer', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockRejectedValue(new Error('detail failed'));
    const detailState = useReviewDataDetail(deps);

    await detailState.openDetail(5);

    expect(detailState.detailVisible.value).toBe(false);
    expect(detailState.detailData.value).toBeNull();
    expect(deps.notifyError).toHaveBeenCalledWith('detail failed');
  });

  it('refreshes the drawer detail only when the same record is open', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValueOnce(detail(5)).mockResolvedValueOnce(detail(5));
    const detailState = useReviewDataDetail(deps);

    await detailState.openDetail(5);
    await detailState.refreshDetailIfOpen(6);
    await detailState.refreshDetailIfOpen(5);

    expect(deps.loadRecordDetail).toHaveBeenCalledTimes(2);
  });
});
