import type {
  OptionItemResponse,
  StatisticFilterField,
  SystemTestIllegalRecordFilterOptionsResponse,
  SystemTestIssueSearchFilterOptionsResponse,
} from '../../types/api';

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
  options: OptionItemResponse[] = [],
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

export function buildSystemTestIllegalConditionFields(
  options: SystemTestIllegalRecordFilterOptionsResponse,
): StatisticFilterField[] {
  return [
    textConditionField('keyword', '关键字', 240),
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '标题', 240),
    selectConditionField('moduleName', '模块', options.moduleNames),
    textConditionField('functionName', '功能名', 180),
    selectConditionField('projectName', '项目', options.projectNames),
    selectConditionField('testingPhase', '测试阶段', options.testingPhases),
    selectConditionField('illegalReason', '非法类型', options.illegalReasons),
    selectConditionField('severityLevel', '严重程度', options.severityLevels),
    selectConditionField('issueState', '状态', options.issueStates),
    selectConditionField('bugStatus', '缺陷状态', options.bugStatuses),
    selectConditionField('category', '分类', options.categories),
    selectConditionField('milestoneTitle', '里程碑', options.milestoneTitles),
    selectConditionField('authorName', '创建人', options.authorNames),
    selectConditionField('assigneeName', '处理人', options.assigneeNames),
    datetimeConditionField('createdAt', '创建时间'),
    datetimeConditionField('updatedAt', '更新时间'),
  ];
}

export function buildSystemTestIssueSearchConditionFields(
  options: SystemTestIssueSearchFilterOptionsResponse,
): StatisticFilterField[] {
  return [
    textConditionField('keyword', '关键字', 240),
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '标题', 240),
    selectConditionField('moduleName', '模块', options.moduleNames, 180, true),
    selectConditionField('projectName', '项目', options.projectNames, 180, true),
    selectConditionField('testingPhase', '测试阶段', options.testingPhases, 180, true),
    selectConditionField('severityLevel', '严重程度', options.severityLevels, 180, true),
    selectConditionField('issueState', '状态', options.issueStates, 180, true),
    selectConditionField('bugStatus', '缺陷状态', options.bugStatuses, 180, true),
    selectConditionField('category', '分类', options.categories, 180, true),
    selectConditionField('milestoneTitle', '里程碑', options.milestoneTitles, 180, true),
    selectConditionField('authorName', '创建人', options.authorNames, 180, true),
    selectConditionField('assigneeName', '处理人', options.assigneeNames, 180, true),
    datetimeConditionField('createdAt', '创建时间'),
    datetimeConditionField('updatedAt', '更新时间'),
  ];
}
