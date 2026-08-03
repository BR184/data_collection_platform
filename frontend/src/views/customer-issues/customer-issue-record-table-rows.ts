import type { CustomerIssueRecordRowResponse } from '../../types/api';
import { buildIssueIidCellValue } from '../../utils/issue-record-links';
import { buildIssueSeverityTag } from '../../utils/issue-severity-display';
import { parseIssueStatusMembers } from '../../utils/issue-status-members';
import { parseCustomerIssuePlannedMergeBranchMembers } from './customer-issue-planned-merge-branch-members';

/** 将客户问题领域响应转换为通用记录表所需的显示行。 */
export function mapCustomerIssueRecordTableRows(
  rows: CustomerIssueRecordRowResponse[],
): Record<string, unknown>[] {
  return rows.map((row) => ({
    __raw: row,
    issueIid: buildIssueIidCellValue(row.issueIid, row.issueLink),
    moduleNames: row.moduleNames || '-',
    functionName: row.functionName || '-',
    title: row.title,
    customerNames: row.customerNames || '-',
    authorName: row.authorName || '-',
    handlerName: row.handlerName || '-',
    assigneeName: row.assigneeName || '-',
    issueState: [
      {
        label: normalizeCustomerIssueState(row.issueState, row.closedAt),
        type: row.closedAt ? ('info' as const) : ('success' as const),
      },
    ],
    bugStatus: parseIssueStatusMembers(row.bugStatus).map((label) => ({
      label,
      type: 'primary' as const,
    })),
    testingPhase: row.testingPhase || '未设定测试阶段',
    severityLevel: row.severityLevel ? [buildIssueSeverityTag(row.severityLevel)] : [],
    priorityLevel: [{ label: row.priorityLevel || '-', type: 'primary' as const }],
    category: [{ label: row.category || '-', type: row.category ? ('primary' as const) : ('info' as const) }],
    milestoneTitle: row.milestoneTitle || '-',
    delayCause: parseIssueStatusMembers(row.delayCause).map((label) => ({
      label,
      type: 'primary' as const,
    })),
    fixUser: row.fixUser || '-',
    plannedResolutionAt:
      row.plannedResolutionText || formatCustomerIssueRecordDateTime(row.plannedResolutionAt),
    plannedMergeVersionBranch: parseCustomerIssuePlannedMergeBranchMembers(
      row.plannedMergeVersionBranch,
    ),
    createdAt: formatCustomerIssueRecordDateTime(row.createdAt),
    retentionHours: row.retentionHours ?? '-',
    updatedAt: formatCustomerIssueRecordDateTime(row.updatedAt),
  }));
}

/** 将后端 ISO 时间格式化为记录页与详情抽屉统一使用的文本。 */
export function formatCustomerIssueRecordDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

/** 将 GitLab 议题状态转换为页面显示状态。 */
export function normalizeCustomerIssueState(value: string, closedAt?: string | null) {
  if (closedAt) {
    return '已关闭';
  }
  const normalized = value.trim().toLowerCase();
  if (normalized === 'closed') {
    return '已关闭';
  }
  if (normalized === 'opened' || normalized === 'open') {
    return '未关闭';
  }
  return value || '-';
}
