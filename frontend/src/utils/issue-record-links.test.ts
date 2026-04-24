import { describe, expect, it } from 'vitest';
import { buildIssueIidCellValue } from './issue-record-links';

describe('issue-record-links', () => {
  it('builds a record-table link value when issue link exists', () => {
    expect(buildIssueIidCellValue(301, 'http://gitlab.example.com/-/issues/301')).toEqual({
      label: '301',
      href: 'http://gitlab.example.com/-/issues/301',
    });
  });

  it('falls back to plain text when issue link is missing', () => {
    expect(buildIssueIidCellValue(301, null)).toBe('301');
  });
});
