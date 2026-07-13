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

const METRIC_SEVERITY_OPTIONS: OptionItemResponse[] = [
  { label: '一级缺陷', value: 'LEVEL1' },
  { label: '二级缺陷', value: 'LEVEL2' },
  { label: '三级缺陷', value: 'LEVEL3' },
  { label: '建议类缺陷', value: 'SUGGESTION' },
];

const BOOLEAN_OPTIONS: OptionItemResponse[] = [
  { label: '是', value: 'true' },
  { label: '否', value: 'false' },
];

const MAJOR_CAUSE_OPTIONS: OptionItemResponse[] = [
  '需求阶段',
  '设计阶段',
  '编码问题',
  '打包问题',
  '依赖问题',
  '精度问题',
].map((value) => ({ label: value, value }));

const DELAY_CAUSE_OPTIONS: OptionItemResponse[] = [
  '技术卡点',
  '方案卡点',
  '资源卡点',
  '数据异常',
  '算法问题',
  '机制问题',
  '计算效率',
].map((value) => ({ label: value, value }));

const CAUSE_METRIC_OPTIONS: OptionItemResponse[] = [
  ['新增理解偏差', 'demand_misunderstand'],
  ['需求遗漏', 'missing_requirement'],
  ['新增需求', 'add_demand_2'],
  ['需求变更未同步', 'demand_change_not_sync'],
  ['功能设计遗漏', 'design_forget'],
  ['设计方案不合理', 'design_scheme'],
  ['场景考虑不全', 'incomplete'],
  ['术语、提示信息不合适', 'prompt_message'],
  ['编码规范错误', 'standard_error'],
  ['功能编码遗漏', 'function_forget'],
  ['编码逻辑：计算与算法错误', 'logic_calculation_algorithm_error'],
  ['编码逻辑：流程控制错误', 'logic_flow_control_error'],
  ['编码逻辑：数据与状态处理错误', 'logic_data_state_process_error'],
  ['编码逻辑：业务逻辑错误', 'logic_business_logic_error'],
  ['编码逻辑：集成与接口错误', 'logic_integration_interface_error'],
  ['环境配置问题', 'environment_config_issue'],
  ['编译/打包/部署问题', 'compilation_package_deployment_issue'],
  ['第三方库问题', 'other_thirdParty'],
  ['算法不支持', 'algorithm_not_support'],
  ['机制不支持', 'mechanism_not_support'],
  ['前置数据异常', 'precondition_data_exception'],
  ['未识别的前后置任务', 'other_unIdentifyTask'],
  ['精度导致约束求解异常', 'precision_constraint_exception'],
  ['精度导致算法执行异常', 'precision_algorithm_exception'],
].map(([label, value]) => ({ label, value }));

export function buildSystemTestIllegalConditionFields(
  options: SystemTestIllegalRecordFilterOptionsResponse,
): StatisticFilterField[] {
  return [
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '标题', 240),
    selectConditionField('moduleName', '模块', options.moduleNames, 180, true),
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
    textConditionField('issueIid', '议题编号', 180),
    textConditionField('title', '标题', 240),
    selectConditionField('moduleName', '模块', options.moduleNames, 180, true),
    selectConditionField('functionName', '功能名', options.functionNames, 180, true),
    selectConditionField('projectName', '项目', options.projectNames, 180, true),
    selectConditionField('testingPhase', '测试阶段', options.testingPhases, 180, true),
    selectConditionField('metricSeverity', '看板严重程度', METRIC_SEVERITY_OPTIONS),
    selectConditionField('regularMetric', '常规指标数据', BOOLEAN_OPTIONS),
    selectConditionField('majorCause', '缺陷主原因', MAJOR_CAUSE_OPTIONS),
    selectConditionField('causeMetric', '缺陷原因明细', CAUSE_METRIC_OPTIONS, 220),
    textConditionField('reasonCategory', '原因分类', 200),
    textConditionField('fixUser', '修复人', 180),
    selectConditionField('delayCause', '延期原因', DELAY_CAUSE_OPTIONS),
    selectConditionField('delayIssue', '申请延期', BOOLEAN_OPTIONS),
    selectConditionField('rollback', '回退类缺陷', BOOLEAN_OPTIONS),
    selectConditionField('openIssue', '未关闭缺陷', BOOLEAN_OPTIONS),
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
