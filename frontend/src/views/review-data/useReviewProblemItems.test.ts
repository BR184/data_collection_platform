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
  it('loads problem items when a row is expanded and reuses the cached rows when reopened', async () => {
    const loader = vi.fn(async (recordId: number) => [problemItem(recordId * 10, recordId)]);
    const state = useReviewProblemItems(loader);

    await state.toggleProblemPanel(3);

    expect(state.expandedRowKeys.value).toEqual([3]);
    expect(loader).toHaveBeenCalledTimes(1);
    expect(loader).toHaveBeenCalledWith(3);
    expect(state.problemItemsFor(3)[0].problemDescription).toBe('problem-30');
    expect(state.problemLoadingMap.value[3]).toBe(false);

    await state.toggleProblemPanel(3);
    expect(state.expandedRowKeys.value).toEqual([]);

    await state.toggleProblemPanel(3);
    expect(loader).toHaveBeenCalledTimes(1);
    expect(state.expandedRowKeys.value).toEqual([3]);
  });

  it('can force reload the cached problem items after item mutations', async () => {
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

  it('syncs Element Plus expand-change rows to a single expanded record', async () => {
    const loader = vi.fn(async (recordId: number) => [problemItem(recordId, recordId)]);
    const state = useReviewProblemItems(loader);
    const first = { __raw: record(1) };
    const second = { __raw: record(2) };

    await state.handleExpandChange(first, [first]);
    await state.handleExpandChange(second, [first, second]);
    await state.handleExpandChange(second, [first]);

    expect(state.expandedRowKeys.value).toEqual([]);
    expect(loader).toHaveBeenCalledTimes(2);
    expect(state.isProblemExpanded(2)).toBe(false);
  });
});
