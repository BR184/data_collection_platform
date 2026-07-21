import { describe, expect, it, vi } from 'vitest';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import { useReviewProblemItems } from './useReviewProblemItems';

function problemItem(id: number, reviewRecordId = 1): ReviewDataProblemItemResponse {
  return {
    id,
    reviewRecordId,
    reviewerName: `reviewer-${id}`,
    workloadHours: 1,
    reviewCategory: 'meeting',
    documentPosition: '1.1',
    problemCategory: 'spec',
    problemDescription: `problem-${id}`,
    suggestedSolution: 'fix it',
    ownerName: 'owner',
    rejectionReason: '',
    problemStatus: 'new',
    updatedAt: '2026-04-27T09:30:00',
  };
}

function record(id: number): ReviewDataRecordRowResponse {
  return {
    id,
    projectName: 'project',
    title: `record-${id}`,
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
    updatedAt: '2026-04-27T09:30:00',
    deleted: false,
  };
}

describe('useReviewProblemItems', () => {
  it('opens the problem dialog and reuses cached rows when reopened', async () => {
    const loader = vi.fn(async (recordId: number) => [problemItem(recordId * 10, recordId)]);
    const state = useReviewProblemItems(loader);

    await state.openProblemList(record(3));

    expect(state.problemDialogVisible.value).toBe(true);
    expect(state.activeProblemRecord.value?.id).toBe(3);
    expect(loader).toHaveBeenCalledTimes(1);
    expect(loader).toHaveBeenCalledWith(3);
    expect(state.problemItemsFor(3)[0].problemDescription).toBe('problem-30');
    expect(state.problemLoadingMap.value[3]).toBe(false);

    state.closeProblemList();
    expect(state.problemDialogVisible.value).toBe(false);
    expect(state.activeProblemRecord.value).toBeNull();

    await state.openProblemList(record(3));
    expect(loader).toHaveBeenCalledTimes(1);
  });

  it('can force reload cached problem items after item mutations', async () => {
    const loader = vi
      .fn<(recordId: number) => Promise<ReviewDataProblemItemResponse[]>>()
      .mockResolvedValueOnce([problemItem(1)])
      .mockResolvedValueOnce([problemItem(2)]);
    const state = useReviewProblemItems(loader);

    await state.loadProblemItems(1);
    await state.loadProblemItems(1);

    expect(loader).toHaveBeenCalledTimes(2);
    expect(state.problemItemsFor(1)[0].id).toBe(2);
  });

  it('keeps the selected record stable while the dialog is open', async () => {
    const loader = vi.fn(async (recordId: number) => [problemItem(recordId, recordId)]);
    const state = useReviewProblemItems(loader);

    await state.openProblemList(record(2));

    expect(state.activeProblemRecord.value?.title).toBe('record-2');
    expect(state.problemDialogVisible.value).toBe(true);
  });
});
