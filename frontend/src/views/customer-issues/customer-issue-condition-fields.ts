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
  severityLevels: Option[];
  priorityLevels: Option[];
  issueStates: Option[];
  bugStatuses: Option[];
  categories: Option[];
  assigneeNames?: Option[];
  milestoneTitles: Option[];
}) {
  return [
    textConditionField('keyword', '关键字', 240),
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '标题', 240),
    selectConditionField('moduleName', '模块', options.moduleNames, 180, true),
    selectConditionField('projectName', '项目', options.projectNames),
    selectConditionField('severityLevel', '严重程度', options.severityLevels),
    selectConditionField('priorityLevel', '优先级', options.priorityLevels, 180, true),
    selectConditionField('issueState', '状态', options.issueStates),
    selectConditionField('bugStatus', '缺陷状态', options.bugStatuses, 180, true),
    selectConditionField('category', '分类', options.categories),
    selectConditionField('assigneeName', '处理人', options.assigneeNames ?? [], 180, true),
    selectConditionField('milestoneTitle', '里程碑', options.milestoneTitles, 180, true),
    datetimeConditionField('createdAt', '创建时间'),
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
