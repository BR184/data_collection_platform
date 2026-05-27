import type { RecordTableLinkValue } from '../types/record-table';

export function buildGitlabResourceLinkCell(
  resourceLabel: number | string | null | undefined,
  resourceLink?: string | null,
  options: { prefix?: string; emptyLabel?: string } = {},
): RecordTableLinkValue | string {
  const normalizedLabel = resourceLabel == null || resourceLabel === '' ? options.emptyLabel ?? '-' : String(resourceLabel);
  const label = options.prefix && normalizedLabel !== '-' ? `${options.prefix}${normalizedLabel}` : normalizedLabel;
  if (!resourceLink) {
    return label;
  }
  return {
    label,
    href: resourceLink,
  };
}

export function buildIssueIidCellValue(
  issueIid: number | string | null | undefined,
  issueLink?: string | null,
): RecordTableLinkValue | string {
  return buildGitlabResourceLinkCell(issueIid, issueLink);
}
