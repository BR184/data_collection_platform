import { describe, expect, it } from 'vitest';
import { isCcProductQuickFilterKey } from './customer-issue-quick-filter-fields';

describe('customer issue quick filter fields', () => {
  it('exposes the CC_PRODUCT fields added to the record page', () => {
    expect([
      'customerName',
      'reasonCategory',
      'authorName',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
      'category',
      'plannedResolutionAtRange',
      'plannedMergeVersionBranch',
      'createdAtRange',
      'retentionHoursRange',
      'updatedAtRange',
    ].every(isCcProductQuickFilterKey)).toBe(true);
  });

  it('keeps the existing quick filter scope explicit', () => {
    expect(isCcProductQuickFilterKey('projectName')).toBe(false);
    expect(isCcProductQuickFilterKey('issueIid')).toBe(false);
    expect(isCcProductQuickFilterKey('title')).toBe(false);
  });
});
