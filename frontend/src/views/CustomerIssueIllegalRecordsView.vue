<script setup lang="ts">
import { ref } from 'vue';
import IssueIllegalRecordsPage from './issue-illegal-records/IssueIllegalRecordsPage.vue';
// 客户问题非法数据页沿用同一个非法记录页面骨架，差异只体现在规则说明和接口域。
// 这种薄封装让系统测试与客户问题两类页面保持一致的筛选、分页和导出体验。
import { api } from '../api';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import { buildIssueSeverityTag } from '../utils/issue-severity-display';
import type { DataScopeOption } from '../types/data-scope';
import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordRowResponse,
  StatisticFilterField,
} from '../types/api';
import type { RecordTableColumn, RecordTableFilterField } from '../types/record-table';
import { buildCustomerIssueIllegalConditionFields } from './customer-issues/customer-issue-condition-fields';
import type {
  IssueIllegalRecordFilterOptions,
  IssueIllegalRecordQueryParams,
} from './issue-illegal-records/issue-illegal-records-types';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;
const phaseScopeOptions = ref<DataScopeOption[]>([]);

const initialFilterOptions: CustomerIssueIllegalRecordFilterOptionsResponse = {
  projectNames: [],
  moduleNames: [],
  functionNames: [],
  illegalReasons: [],
  severityLevels: [],
  priorityLevels: [],
  issueStates: [],
  bugStatuses: [],
  categories: [],
  authorNames: [],
  assigneeNames: [],
  milestoneTitles: [],
};

const columns: RecordTableColumn[] = [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'moduleNames', label: '模块名', sortable: true, minWidth: 160 },
  { key: 'title', label: '议题标题', sortable: true, minWidth: 260 },
  { key: 'issueState', label: '议题状态', type: 'tag', sortable: true, width: 100 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'assigneeName', label: '议题处理人', sortable: true, minWidth: 120 },
  { key: 'illegalReason', label: '非法类型', type: 'tag', sortable: true, minWidth: 150 },
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
    moduleNames: row.moduleNames || '-',
    title: row.title,
    severityLevel: row.severityLevel ? [buildIssueSeverityTag(row.severityLevel)] : [],
    assigneeName: row.assigneeName || '-',
    issueState: [{ label: normalizeIssueState(row.issueState), type: row.closedAt ? 'info' as const : 'success' as const }],
    illegalReason: [{ label: row.illegalReason || '未说明', type: 'warning' as const }],
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
    testingPhase: params.testingPhase,
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

async function loadFilterOptions(projectId?: string | number | null) {
  const [options, phaseGroups] = await Promise.all([
    api.getCustomerIssueIllegalRecordFilterOptions(projectId),
    api.getTestingPhaseGroups({
      projectId: LEGACY_CROWN_CAD_PROJECT_ID,
      enabled: true,
    }),
  ]);
  phaseScopeOptions.value = phaseGroups
    .map((group) => String(group.name ?? '').trim())
    .filter(Boolean)
    .map((name) => ({ label: name, value: name }));
  return options;
}

function buildConditionFields(options: IssueIllegalRecordFilterOptions): StatisticFilterField[] {
  return buildCustomerIssueIllegalConditionFields(options as CustomerIssueIllegalRecordFilterOptionsResponse);
}

function buildPrimaryFilters(): RecordTableFilterField[] {
  return [
    {
      key: 'testingPhase',
      label: '测试阶段',
      type: 'select',
      defaultStrategy: 'first-available',
      clearable: false,
      width: 240,
      options: phaseScopeOptions.value,
    },
  ];
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
    :export-records="api.exportCustomerIssueIllegalRecords"
    export-filename-prefix="客户问题非法数据"
    :load-filter-options="loadFilterOptions"
    :load-rule-explanation="api.getCustomerIssueIllegalRecordRuleExplanation"
    :load-realtime-status="api.getCustomerIssueIllegalRecordRealtimeStatus"
    :request-realtime-refresh="api.refreshCustomerIssueIllegalRecordRealtime"
    :initial-filter-options="initialFilterOptions"
    :build-condition-fields="buildConditionFields"
    :build-primary-filters="buildPrimaryFilters"
    :native-primary-select-keys="['testingPhase']"
    :columns="columns"
    :map-row="mapRow"
    created-at-detail-label="议题提交时间"
    updated-at-detail-label="议题更新时间"
    issue-state-detail-label="议题状态"
    module-detail-label="模块名"
    author-detail-label="议题提交人"
    assignee-detail-label="议题处理人"
    severity-detail-label="议题严重程度"
    bug-status-detail-label="测试状态"
    :reset-clear-keys="[
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'testingPhase',
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
      'testingPhase',
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
