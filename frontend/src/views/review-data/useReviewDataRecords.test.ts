import { describe, expect, it, vi } from 'vitest';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataRecordListResponse,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import { useReviewDataRecords } from './useReviewDataRecords';

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
    problemCount: 2,
    problemDensity: 0.2,
    updatedAt: '2026-04-27T10:00:00',
    deleted: false,
  };
}

function listResponse(records: ReviewDataRecordRowResponse[]): ReviewDataRecordListResponse {
  return {
    records,
    total: records.length,
    page: 1,
    size: 20,
    sortField: 'updatedAt',
    sortOrder: 'desc',
    summary: {
      totalRecords: records.length,
      totalProblemItems: 4,
      averageReviewScalePages: 10,
      averageProblemCount: 2,
    },
  };
}

function filterOptions(): ReviewDataFilterOptionsResponse {
  return {
    projectNames: [{ label: 'Project A', value: 'Project A' }],
    moduleNames: [],
    reviewOwners: [],
    reviewTypes: [],
    reviewExperts: [],
    problemStatuses: [],
    reviewCategories: [],
    problemCategories: [],
  };
}

describe('useReviewDataRecords', () => {
  it('loads filter options and records into derived table state', async () => {
    const options = filterOptions();
    const fetchFilterOptions = vi.fn(async () => options);
    const fetchRecords = vi.fn(async () => listResponse([record(1)]));
    const state = useReviewDataRecords({ fetchFilterOptions, fetchRecords });

    await state.loadFilterOptions();
    await state.loadRows({ keyword: 'review', page: 1, size: 20, sortBy: 'updatedAt', sortOrder: 'desc' });

    expect(state.filterOptions.value).toStrictEqual(options);
    expect(fetchRecords).toHaveBeenCalledWith({
      keyword: 'review',
      page: 1,
      size: 20,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
    });
    expect(state.rows.value).toEqual([record(1)]);
    expect(state.total.value).toBe(1);
    expect(state.tableRows.value[0].title).toBe('review-1');
    expect(state.summaryCards.value[0].value).toBe('1');
  });

  it('refreshes filter options and rows together', async () => {
    const fetchFilterOptions = vi.fn(async () => filterOptions());
    const fetchRecords = vi.fn(async () => listResponse([record(1), record(2)]));
    const state = useReviewDataRecords({ fetchFilterOptions, fetchRecords });
    const params = { keyword: '', page: 2, size: 10, sortBy: 'title', sortOrder: 'asc' as const };

    await state.refresh(params);

    expect(fetchFilterOptions).toHaveBeenCalledOnce();
    expect(fetchRecords).toHaveBeenCalledWith(params);
    expect(state.total.value).toBe(2);
  });
});
