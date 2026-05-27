import { describe, expect, it } from 'vitest';
import { buildGitlabResourceLinkCell, buildIssueIidCellValue } from './issue-record-links';

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

  it('builds a shared GitLab resource link cell for merge requests and prefixed issue references', () => {
    expect(buildGitlabResourceLinkCell(18, 'http://gitlab.example.com/-/merge_requests/18')).toEqual({
      label: '18',
      href: 'http://gitlab.example.com/-/merge_requests/18',
    });
    expect(buildGitlabResourceLinkCell(101, 'http://gitlab.example.com/-/issues/101', { prefix: '#' })).toEqual({
      label: '#101',
      href: 'http://gitlab.example.com/-/issues/101',
    });
  });
});
