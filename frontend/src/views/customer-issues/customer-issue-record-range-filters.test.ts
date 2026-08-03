import { describe, expect, it } from 'vitest';
import type { LocationQuery } from 'vue-router';
import {
  buildCustomerIssueRecordRangeQueryPatch,
  buildCustomerIssueRecordRangeRequestParams,
  readCustomerIssueRecordRangeValues,
} from './customer-issue-record-range-filters';

describe('customer issue record range filters', () => {
  it('restores every CC_PRODUCT range from the route with numeric values', () => {
    const values = readCustomerIssueRecordRangeValues({
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      retentionHoursMin: '24',
      retentionHoursMax: '72',
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    } as LocationQuery, false);

    expect(values).toEqual({
      createdAtRange: ['2026-01-01', '2026-08-31'],
      updatedAtRange: ['2026-07-01', '2026-08-31'],
      plannedResolutionAtRange: ['2026-08-01', '2026-08-31'],
      retentionHoursRange: [24, 72],
    });
  });

  it('keeps delay requests isolated from CC_PRODUCT-only ranges', () => {
    const params = buildCustomerIssueRecordRangeRequestParams({
      plannedResolutionAtStart: '2026-08-01',
      plannedResolutionAtEnd: '2026-08-31',
      retentionHoursMin: '24',
      retentionHoursMax: '72',
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    } as LocationQuery, true);

    expect(params).toEqual({
      createdAtStart: '2026-01-01',
      createdAtEnd: '2026-08-31',
      updatedAtStart: '2026-07-01',
      updatedAtEnd: '2026-08-31',
    });
  });

  it('supports one-sided numeric ranges and ignores invalid route numbers', () => {
    expect(buildCustomerIssueRecordRangeRequestParams({
      retentionHoursMin: 'invalid',
      retentionHoursMax: '72',
    } as LocationQuery, false)).toEqual({ retentionHoursMax: 72 });

    expect(buildCustomerIssueRecordRangeQueryPatch('retentionHoursRange', [24, null])).toEqual({
      page: 1,
      retentionHoursMin: '24',
      retentionHoursMax: null,
    });
  });
});
