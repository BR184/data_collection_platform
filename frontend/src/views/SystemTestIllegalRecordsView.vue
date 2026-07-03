<script setup lang="ts">
import IssueIllegalRecordsPage from './issue-illegal-records/IssueIllegalRecordsPage.vue';
// 系统测试非法数据页复用非法记录页底座，保证客户问题和系统测试的处置链路一致。
// 本页只绑定系统测试范围、规则说明和接口适配，避免复制整套记录表逻辑。
import { api } from '../api';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import { buildIssueSeverityTag } from '../utils/issue-severity-display';
import type {
  StatisticFilterField,
  SystemTestIllegalRecordFilterOptionsResponse,
  SystemTestIllegalRecordRowResponse,
} from '../types/api';
import type { RecordTableColumn, RecordTableFilterField, RecordTableTagValue } from '../types/record-table';
import { buildSystemTestIllegalConditionFields } from './system-test/system-test-condition-fields';
import type {
  IssueIllegalRecordFilterOptions,
  IssueIllegalRecordQueryParams,
} from './issue-illegal-records/issue-illegal-records-types';

const initialFilterOptions: SystemTestIllegalRecordFilterOptionsResponse = {
  projectNames: [],
  moduleNames: [],
  testingPhases: [],
  illegalReasons: [],
  authorNames: [],
  assigneeNames: [],
  issueStates: [],
  severityLevels: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
};

const columns: RecordTableColumn[] = [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'moduleNames', label: '模块名', type: 'tags', sortable: true, minWidth: 180 },
  { key: 'title', label: '议题标题', sortable: true, minWidth: 320 },
  { key: 'issueState', label: '议题状态', type: 'tag', sortable: true, width: 110 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'assigneeName', label: '议题处理人', sortable: true, minWidth: 120 },
  { key: 'illegalReason', label: '非法类型', type: 'tag', sortable: true, minWidth: 150 },
];

function buildStateTag(value: string): RecordTableTagValue {
  return value.toLowerCase() === 'closed'
    ? { label: '已关闭', type: 'success' }
    : { label: '未关闭', type: 'warning' };
}

function mapRow(row: SystemTestIllegalRecordRowResponse): Record<string, unknown> {
  return {
    __raw: row,
    issueId: row.issueId,
    issueIid: buildIssueIidCellValue(row.issueIid, row.issueLink),
    title: row.title || '-',
    moduleNames: row.moduleNames ? [{ label: row.moduleNames, type: 'info' as const }] : [],
    severityLevel: row.severityLevel ? [buildIssueSeverityTag(row.severityLevel)] : [],
    issueState: row.issueState ? [buildStateTag(row.issueState)] : [],
    assigneeName: row.assigneeName || '-',
    illegalReason: [{ label: row.illegalReason || '未说明', type: 'warning' as const }],
  };
}

function loadRecords(params: IssueIllegalRecordQueryParams) {
  return api.getSystemTestIllegalRecords({
    projectId: undefined,
    keyword: params.keyword,
    issueIid: params.issueIid,
    title: params.title,
    projectName: params.projectName,
    moduleName: params.moduleName,
    testingPhase: params.testingPhase,
    illegalReason: params.illegalReason,
    authorName: params.authorName,
    assigneeName: params.assigneeName,
    issueState: params.issueState,
    severityLevel: params.severityLevel,
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
  return buildSystemTestIllegalConditionFields(options as SystemTestIllegalRecordFilterOptionsResponse);
}

function buildPrimaryFilters(options: IssueIllegalRecordFilterOptions): RecordTableFilterField[] {
  return [
    {
      key: 'testingPhase',
      label: '测试阶段',
      type: 'select',
      defaultStrategy: 'first-available',
      clearable: false,
      width: 240,
      options: options.testingPhases ?? [],
    },
  ];
}
</script>

<template>
  <IssueIllegalRecordsPage
    workspace-key="system-test-illegal-records"
    title="系统测试非法数据"
    description="系统测试范围内的非法议题记录筛选、规则说明与详情查看"
    detail-kicker="系统测试非法数据"
    rule-title="系统测试非法数据规则说明"
    empty-description="当前筛选条件下没有系统测试非法数据。"
    :total-tag-text="(total) => `当前 ${total} 条`"
    :load-records="loadRecords"
    :export-records="api.exportSystemTestIllegalRecords"
    export-filename-prefix="多元议题查询结果"
    :load-filter-options="api.getSystemTestIllegalRecordFilterOptions"
    :load-rule-explanation="api.getSystemTestIllegalRecordRuleExplanation"
    :load-realtime-status="api.getSystemTestIllegalRecordRealtimeStatus"
    :request-realtime-refresh="api.refreshSystemTestIllegalRecordRealtime"
    :initial-filter-options="initialFilterOptions"
    :build-condition-fields="buildConditionFields"
    :build-primary-filters="buildPrimaryFilters"
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
      'illegalReason',
      'authorName',
      'assigneeName',
      'severityLevel',
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
      'authorName',
      'assigneeName',
      'severityLevel',
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
