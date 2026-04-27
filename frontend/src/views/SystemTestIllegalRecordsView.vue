<script setup lang="ts">
import IssueIllegalRecordsPage from './issue-illegal-records/IssueIllegalRecordsPage.vue';
import { api } from '../api';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import type {
  StatisticFilterField,
  SystemTestIllegalRecordFilterOptionsResponse,
  SystemTestIllegalRecordRowResponse,
} from '../types/api';
import type { RecordTableColumn, RecordTableTagValue } from '../types/record-table';
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
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'illegalReason', label: '非法类型', type: 'tag', sortable: true, minWidth: 150 },
  { key: 'projectName', label: '项目名称', sortable: true, minWidth: 140 },
  { key: 'moduleNames', label: '模块', type: 'tags', sortable: true, minWidth: 180 },
  { key: 'testingPhase', label: '测试阶段', sortable: true, minWidth: 180 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'bugStatus', label: '缺陷状态', sortable: true, minWidth: 140 },
  { key: 'issueState', label: '状态', type: 'tag', sortable: true, width: 110 },
  { key: 'assigneeName', label: '处理人', sortable: true, minWidth: 120 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
];

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function splitDisplayList(value: string) {
  return value
    .split('、')
    .map((item) => item.trim())
    .filter(Boolean);
}

function buildSeverityTag(value: string): RecordTableTagValue {
  const normalized = value.toUpperCase();
  if (normalized === 'LEVEL1') {
    return { label: value, type: 'danger' };
  }
  if (normalized === 'LEVEL2') {
    return { label: value, type: 'warning' };
  }
  if (normalized === 'LEVEL3') {
    return { label: value, type: 'primary' };
  }
  return { label: value || '-', type: 'info' };
}

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
    illegalReason: [{ label: row.illegalReason || '未说明', type: 'warning' as const }],
    projectName: row.projectName || '-',
    moduleNames: splitDisplayList(row.moduleNames || '-').map((label) => ({ label, type: 'info' as const })),
    testingPhase: row.testingPhase || '-',
    severityLevel: row.severityLevel ? [buildSeverityTag(row.severityLevel)] : [],
    bugStatus: row.bugStatus || '-',
    issueState: row.issueState ? [buildStateTag(row.issueState)] : [],
    assigneeName: row.assigneeName || '-',
    updatedAt: formatDateTime(row.updatedAt),
  };
}

function loadRecords(params: IssueIllegalRecordQueryParams) {
  return api.getSystemTestIllegalRecords({
    projectId: params.projectId,
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
    :load-filter-options="api.getSystemTestIllegalRecordFilterOptions"
    :load-rule-explanation="api.getSystemTestIllegalRecordRuleExplanation"
    :initial-filter-options="initialFilterOptions"
    :build-condition-fields="buildConditionFields"
    :columns="columns"
    :map-row="mapRow"
    :reset-clear-keys="[
      'keyword',
      'issueIid',
      'title',
      'projectName',
      'moduleName',
      'testingPhase',
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
      'testingPhase',
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
