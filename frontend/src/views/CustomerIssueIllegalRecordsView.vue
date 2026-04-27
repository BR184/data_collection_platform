<script setup lang="ts">
import IssueIllegalRecordsPage from './issue-illegal-records/IssueIllegalRecordsPage.vue';
import { api } from '../api';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordRowResponse,
  StatisticFilterField,
} from '../types/api';
import type { RecordTableColumn } from '../types/record-table';
import { buildCustomerIssueIllegalConditionFields } from './customer-issues/customer-issue-condition-fields';
import type {
  IssueIllegalRecordFilterOptions,
  IssueIllegalRecordQueryParams,
} from './issue-illegal-records/issue-illegal-records-types';
import { CUSTOMER_MILESTONE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';

const initialFilterOptions: CustomerIssueIllegalRecordFilterOptionsResponse = {
  projectNames: [],
  moduleNames: [],
  illegalReasons: [],
  severityLevels: [],
  priorityLevels: [],
  issueStates: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
};

const columns: RecordTableColumn[] = [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'illegalReason', label: '非法原因', type: 'tag', sortable: true, minWidth: 150 },
  { key: 'projectName', label: '所属项目', sortable: true, minWidth: 150 },
  { key: 'moduleNames', label: '模块', sortable: true, minWidth: 160 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'priorityLevel', label: '优先级', type: 'tag', sortable: true, width: 100 },
  { key: 'issueState', label: '状态', type: 'tag', sortable: true, width: 100 },
  { key: 'milestoneTitle', label: '里程碑', sortable: true, minWidth: 160 },
  { key: 'authorName', label: '创建人', sortable: true, minWidth: 120 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
];

function normalizeIssueState(value: string) {
  return value === 'closed' ? '已关闭' : value === 'opened' ? '未关闭' : value || '-';
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function mapRow(row: CustomerIssueIllegalRecordRowResponse): Record<string, unknown> {
  return {
    __raw: row,
    issueIid: buildIssueIidCellValue(row.issueIid, row.issueLink),
    title: row.title,
    illegalReason: [{ label: row.illegalReason || '未说明', type: 'warning' as const }],
    projectName: row.projectName || '-',
    moduleNames: row.moduleNames || '-',
    severityLevel: [{ label: row.severityLevel || '-', type: 'danger' as const }],
    priorityLevel: [{ label: row.priorityLevel || '-', type: 'primary' as const }],
    issueState: [{ label: normalizeIssueState(row.issueState), type: row.closedAt ? 'info' as const : 'success' as const }],
    milestoneTitle: row.milestoneTitle || '-',
    authorName: row.authorName || '-',
    updatedAt: formatDateTime(row.updatedAt),
  };
}

function loadRecords(params: IssueIllegalRecordQueryParams) {
  return api.getCustomerIssueIllegalRecords({
    projectId: params.projectId,
    keyword: params.keyword,
    issueIid: params.issueIid,
    title: params.title,
    projectName: params.projectName,
    moduleName: params.moduleName,
    illegalReason: params.illegalReason,
    severityLevel: params.severityLevel,
    priorityLevel: params.priorityLevel,
    issueState: params.issueState,
    bugStatus: params.bugStatus,
    category: params.category,
    milestoneTitle: params.milestoneTitle,
    createdAtStart: params.createdAtStart,
    createdAtEnd: params.createdAtEnd,
    updatedAtStart: params.updatedAtStart,
    updatedAtEnd: params.updatedAtEnd,
    filterGroup: params.filterGroup,
    page: params.page,
    size: params.size,
    sortBy: params.sortBy,
    sortOrder: params.sortOrder,
  });
}

function buildConditionFields(options: IssueIllegalRecordFilterOptions): StatisticFilterField[] {
  return buildCustomerIssueIllegalConditionFields(options as CustomerIssueIllegalRecordFilterOptionsResponse);
}
</script>

<template>
  <IssueIllegalRecordsPage
    workspace-key="customer-issue-illegal-records"
    title="客户问题非法数据"
    description="客户问题范围内的非法缺陷记录筛选、规则说明与详情查看"
    detail-kicker="客户问题非法数据"
    rule-title="客户问题缺陷非法数据规则说明"
    empty-description="当前筛选条件下没有客户问题非法数据。"
    :total-tag-text="(total) => `当前 ${total} 条`"
    :load-records="loadRecords"
    :load-filter-options="api.getCustomerIssueIllegalRecordFilterOptions"
    :load-rule-explanation="api.getCustomerIssueIllegalRecordRuleExplanation"
    :initial-filter-options="initialFilterOptions"
    :build-condition-fields="buildConditionFields"
    :columns="columns"
    :map-row="mapRow"
    :scope-provider="CUSTOMER_MILESTONE_SCOPE_PROVIDER"
    :build-scope-options="(options) => buildScopeOptions(options.milestoneTitles ?? [], '全部里程碑')"
    :reset-clear-keys="[
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'illegalReason',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
      'category',
      'milestoneTitle',
      'createdAtStart',
      'createdAtEnd',
      'updatedAtStart',
      'updatedAtEnd',
    ]"
    :query-clear-keys="[
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'illegalReason',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
      'category',
      'milestoneTitle',
      'createdAtStart',
      'createdAtEnd',
      'updatedAtStart',
      'updatedAtEnd',
    ]"
    default-sort-by="updatedAt"
    default-sort-order="desc"
  />
</template>
