import type {
  OptionItemResponse,
  StatisticFilterField,
  SystemTestIllegalRecordFilterOptionsResponse,
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
): StatisticFilterField {
  return {
    key,
    label,
    type: 'select',
    width,
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options,
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
