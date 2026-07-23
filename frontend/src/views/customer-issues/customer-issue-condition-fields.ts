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
  handlerNames?: Option[];
  assigneeNames?: Option[];
  functionNameLabel?: string;
  handlerNameLabel?: string;
  assigneeNameLabel?: string;
  includeHandlerName?: boolean;
  milestoneTitles: Option[];
}) {
  return [
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '议题标题', 240),
    selectConditionField('moduleName', '模块名', options.moduleNames, 180, true),
    selectConditionField('functionName', options.functionNameLabel ?? '功能名', options.functionNames ?? [], 180, true),
    selectConditionField('projectName', '项目', options.projectNames),
    selectConditionField('severityLevel', '严重程度', options.severityLevels),
    selectConditionField('priorityLevel', '缺陷优先级', options.priorityLevels, 180, true),
    selectConditionField('issueState', '议题状态', options.issueStates),
    selectConditionField('bugStatus', '测试状态', options.bugStatuses, 180, true),
    selectConditionField('category', '议题类别', options.categories),
    selectConditionField('authorName', '议题提交人', options.authorNames ?? [], 180, true),
    ...(options.includeHandlerName
      ? [
        selectConditionField(
          'handlerName',
          options.handlerNameLabel ?? '议题处理人',
          options.handlerNames ?? [],
          180,
          true,
        ),
      ]
      : []),
    selectConditionField(
      'assigneeName',
      options.assigneeNameLabel ?? '议题处理人',
      options.assigneeNames ?? [],
      180,
      true,
    ),
    selectConditionField('milestoneTitle', '里程碑', options.milestoneTitles, 180, true),
    datetimeConditionField('createdAt', '提交时间'),
    datetimeConditionField('updatedAt', '更新时间'),
  ];
}

export function buildCustomerIssueRecordConditionFields(
  options: CustomerIssueRecordFilterOptionsResponse,
  includeCcProductFields = true,
): StatisticFilterField[] {
  const fields = commonIssueConditionFields({
    ...options,
    functionNameLabel: '功能名称',
    includeHandlerName: includeCcProductFields,
    handlerNameLabel: '议题处理人',
    assigneeNameLabel: '议题指派人',
  });
  fields.splice(5, 0, selectConditionField('reasonCategory', '缺陷原因', options.reasonCategories));
  if (!includeCcProductFields) {
    return fields;
  }
  const functionNameIndex = fields.findIndex((field) => field.key === 'functionName');
  fields.splice(
    functionNameIndex + 1,
    0,
    selectConditionField('customerName', '客户', options.customerNames, 180),
    selectConditionField('testingPhase', '测试阶段', options.testingPhases, 180),
  );
  const milestoneIndex = fields.findIndex((field) => field.key === 'milestoneTitle');
  fields.splice(
    milestoneIndex + 1,
    0,
    selectConditionField('delayCause', '延期原因', options.delayCauses, 180),
    selectConditionField('fixUser', '缺陷修复人', options.fixUsers, 180),
  );
  return fields;
}

export function buildCustomerIssueIllegalConditionFields(
  options: CustomerIssueIllegalRecordFilterOptionsResponse,
): StatisticFilterField[] {
  const fields = commonIssueConditionFields(options);
  fields.splice(5, 0, selectConditionField('illegalReason', '非法类型', options.illegalReasons));
  return fields;
}
