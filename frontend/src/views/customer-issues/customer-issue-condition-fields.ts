import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueRecordFilterOptionsResponse,
  StatisticFilterField,
} from '../../types/api';

type Option = { label: string; value: string };

function textConditionField(key: string, label: string, width = 220): StatisticFilterField {
  return {
    key,
    label,
    type: 'text',
    width,
    operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: [],
  };
}

function selectConditionField(
  key: string,
  label: string,
  options: Option[],
  width = 180,
  labelGroupEnabled = false,
): StatisticFilterField {
  return {
    key,
    label,
    type: 'select',
    width,
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options,
    ...(labelGroupEnabled ? { labelGroupEnabled: true, labelGroupValueType: 'STRING' } : {}),
  };
}

function datetimeConditionField(key: string, label: string, width = 220): StatisticFilterField {
  return {
    key,
    label,
    type: 'datetime',
    width,
    operators: ['year', 'month', 'day', 'before', 'after', 'between', 'isEmpty', 'isNotEmpty'],
    options: [],
  };
}

function commonIssueConditionFields(options: {
  projectNames: Option[];
  moduleNames: Option[];
  functionNames?: Option[];
  severityLevels: Option[];
  priorityLevels: Option[];
  issueStates: Option[];
  bugStatuses: Option[];
  categories: Option[];
  authorNames?: Option[];
  assigneeNames?: Option[];
  milestoneTitles: Option[];
}) {
  return [
    textConditionField('keyword', '关键字', 240),
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '议题标题', 240),
    selectConditionField('moduleName', '模块名', options.moduleNames, 180, true),
    selectConditionField('functionName', '功能名', options.functionNames ?? [], 180, true),
    selectConditionField('projectName', '项目', options.projectNames),
    selectConditionField('severityLevel', '严重程度', options.severityLevels),
    selectConditionField('priorityLevel', '缺陷优先级', options.priorityLevels, 180, true),
    selectConditionField('issueState', '议题状态', options.issueStates),
    selectConditionField('bugStatus', '测试状态', options.bugStatuses, 180, true),
    selectConditionField('category', '议题类别', options.categories),
    selectConditionField('authorName', '议题提交人', options.authorNames ?? [], 180, true),
    selectConditionField('assigneeName', '议题处理人', options.assigneeNames ?? [], 180, true),
    selectConditionField('milestoneTitle', '里程碑', options.milestoneTitles, 180, true),
    datetimeConditionField('createdAt', '提交时间'),
    datetimeConditionField('updatedAt', '更新时间'),
  ];
}

export function buildCustomerIssueRecordConditionFields(
  options: CustomerIssueRecordFilterOptionsResponse,
): StatisticFilterField[] {
  const fields = commonIssueConditionFields(options);
  fields.splice(5, 0, selectConditionField('reasonCategory', '缺陷原因', options.reasonCategories));
  return fields;
}

export function buildCustomerIssueIllegalConditionFields(
  options: CustomerIssueIllegalRecordFilterOptionsResponse,
): StatisticFilterField[] {
  const fields = commonIssueConditionFields(options);
  fields.splice(5, 0, selectConditionField('illegalReason', '非法类型', options.illegalReasons));
  return fields;
}
