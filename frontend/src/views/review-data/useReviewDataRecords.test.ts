import { describe, expect, it } from 'vitest';
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
    reviewVersions: [],
    problemStatuses: [],
    reviewCategories: [],
    problemCategories: [],
    formProjectNames: [],
    formModuleNames: [],
  };
}

describe('useReviewDataRecords', () => {
  it('accepts complete record data and derives the table and summary', () => {
    const state = useReviewDataRecords();
    const options = filterOptions();
    state.commitFilterOptions(options);
    state.commitRows(listResponse([record(1)]));
    expect(state.filterOptions.value).toStrictEqual(options);
    expect(state.rows.value).toEqual([record(1)]);
    expect(state.total.value).toBe(1);
    expect(state.tableRows.value[0].title).toBe('review-1');
    expect(state.summaryCards.value[0].value).toBe('1');
  });

  it('clears private rows and summary together', () => {
    const state = useReviewDataRecords();
    state.commitRows(listResponse([record(1), record(2)]));
    expect(state.total.value).toBe(2);
    state.clearRows();
    expect(state.total.value).toBe(0);
    expect(state.rows.value).toEqual([]);
    expect(state.summary.value).toBeNull();
  });
});
