import { describe, expect, it } from 'vitest';
import { parseIssueStatusMembers } from './issue-status-members';

describe('parseIssueStatusMembers', () => {
  it('test_combinedStatus_parse_returnsDistinctMembers', () => {
    expect(parseIssueStatusMembers('历史遗留、申请延期，历史遗留,待合并&申请延期')).toEqual([
      '历史遗留',
      '申请延期',
      '待合并',
    ]);
  });

  it('test_resolvedStatus_parse_keepsSlashAsSingleMember', () => {
    expect(parseIssueStatusMembers('已修复/完成')).toEqual(['已修复/完成']);
  });
});
