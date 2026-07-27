import type { RecordTableColumn } from '../../types/record-table';

/** CC_PRODUCT 议题页面与 Excel 共用的可见列顺序。 */
export const CC_PRODUCT_RECORD_COLUMNS: RecordTableColumn[] = [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'moduleNames', label: '模块名', sortable: true, minWidth: 140 },
  { key: 'functionName', label: '功能名称', sortable: true, minWidth: 140 },
  { key: 'title', label: '议题标题', sortable: true, minWidth: 260 },
  { key: 'customerNames', label: '客户', sortable: true, minWidth: 150 },
  { key: 'authorName', label: '议题提交人', sortable: true, minWidth: 120 },
  { key: 'handlerName', label: '议题处理人', sortable: true, minWidth: 120 },
  { key: 'assigneeName', label: '议题指派人', sortable: true, minWidth: 120 },
  { key: 'issueState', label: '议题状态', type: 'tag', sortable: true, width: 110 },
  { key: 'bugStatus', label: '测试状态', type: 'tags', sortable: true, minWidth: 160 },
  { key: 'testingPhase', label: '测试阶段', sortable: true, minWidth: 180 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'priorityLevel', label: '缺陷优先级', type: 'tag', sortable: true, width: 120 },
  { key: 'category', label: '议题类别', type: 'tag', sortable: true, minWidth: 120 },
  { key: 'milestoneTitle', label: '里程碑', sortable: true, minWidth: 160 },
  { key: 'delayCause', label: '延期原因', sortable: true, minWidth: 160 },
  { key: 'fixUser', label: '缺陷修复人', sortable: true, minWidth: 120 },
  { key: 'plannedResolutionAt', label: '计划解决时间', type: 'datetime', sortable: true, minWidth: 170 },
  { key: 'plannedMergeVersionBranch', label: '计划合并版本分支', type: 'tags', sortable: true, minWidth: 180 },
  { key: 'createdAt', label: '提交时间', sortable: true, minWidth: 170 },
  { key: 'retentionHours', label: '缺陷滞留时长（小时）', type: 'number', minWidth: 180 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
];

/** 延期问题沿用既有列，避免 CC_PRODUCT 的展示契约改变其页面。 */
export const DELAY_RECORD_COLUMNS: RecordTableColumn[] = [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'moduleNames', label: '模块名', sortable: true, minWidth: 140 },
  { key: 'title', label: '议题标题', sortable: true, minWidth: 260 },
  { key: 'authorName', label: '议题提交人', sortable: true, minWidth: 120 },
  { key: 'assigneeName', label: '议题处理人', sortable: true, minWidth: 120 },
  { key: 'issueState', label: '议题状态', type: 'tag', sortable: true, width: 110 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'priorityLevel', label: '缺陷优先级', type: 'tag', sortable: true, width: 120 },
  { key: 'bugStatus', label: '测试状态', type: 'tags', sortable: true, minWidth: 160 },
  { key: 'category', label: '议题类别', type: 'tag', sortable: true, minWidth: 120 },
  { key: 'milestoneTitle', label: '里程碑', sortable: true, minWidth: 160 },
  { key: 'createdAt', label: '提交时间', sortable: true, minWidth: 170 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
];
