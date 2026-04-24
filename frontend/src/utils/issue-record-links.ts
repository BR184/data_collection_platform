import type { RecordTableLinkValue } from '../types/record-table';

export function buildIssueIidCellValue(
  issueIid: number | string | null | undefined,
  issueLink?: string | null,
): RecordTableLinkValue | string {
  const label = issueIid == null ? '-' : String(issueIid);
  if (!issueLink) {
    return label;
  }
  return {
    label,
    href: issueLink,
  };
}
