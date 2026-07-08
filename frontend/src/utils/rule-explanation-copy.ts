import type { StatisticBoardRuleExplanationResponse, StatisticRuleFlowStep, StatisticRuleMetricDefinition } from '../types/api';

const technicalCopyReplacements: Array<[RegExp, string]> = [
  [/当前页面已经启用规则说明，但暂时没有可展示的统计过程。/g, '当前规则说明暂无处理流程数据。'],
  [/这次统计包含哪些数据/g, '统计范围'],
  [/哪些会被排除/g, '排除规则'],
  [/数据是怎么一步步(?:变少|变化)的/g, '处理流程'],
  [/最后这些数字怎么算/g, '指标定义'],
  [/当前结果一共基于\s*(\d+)\s*条原始数据逐步筛选，最后保留\s*(\d+)\s*条，最终保留比例为\s*([0-9.]+%)。/g, '原始数据 $1 条，最终保留 $2 条，保留比例 $3。'],
  [/当前结果一共基于\s*(\d+)\s*条合并请求逐步检查，最终筛出\s*(\d+)\s*条需要关注的记录，占原始数据的\s*([0-9.]+%)。/g, '原始合并请求 $1 条，命中非法记录 $2 条，命中比例 $3。'],
  [/最终筛出/g, '命中记录'],
  [/筛出比例/g, '命中比例'],
  [/需要关注的记录/g, '非法记录'],
  [/逐步检查/g, '按规则检查'],
  [/逐步筛选/g, '按规则筛选'],
  [/方便后续筛选和横向比较/g, '用于后续筛选和横向比较'],
  [/方便识别/g, '用于识别'],
  [/优先选择/g, '应选择'],
  [/请先/g, '请'],
  [/请重点确认/g, '请确认'],
  [/不建议/g, '不得'],
  [/不能混用/g, '不得混用'],
  [/不能用/g, '不得使用'],
  [/不再用/g, '未使用'],
  [/不再以/g, '未以'],
  [/不再单独作为/g, '未单独作为'],
  [/避免和/g, '与'],
  [/避免与/g, '与'],
  [/混在一起/g, '区分统计'],
  [/避免异常样本干扰汇总结果/g, '排除异常样本对汇总结果的影响'],
  [/优先取/g, '按顺序取'],
  [/issue_fact\.reason_category/g, '缺陷原因说明'],
  [/issue_fact\.illegal_reasons\s*\/\s*illegal_reason/g, '非法类型'],
  [/issue_fact\.illegal_reasons/g, '非法类型'],
  [/issue_fact\.illegal_reason/g, '非法类型'],
  [/issue_fact\.is_excluded\s*=\s*true/g, '被排除的无效议题'],
  [/issue_fact\.is_illegal\s*=\s*true/g, '已命中非法规则'],
  [/issue_fact\.severity_level\s*=\s*LEVEL1/g, '严重程度为一级缺陷'],
  [/issue_fact\.severity_level\s*=\s*LEVEL2/g, '严重程度为二级缺陷'],
  [/issue_fact\.severity_level\s*=\s*LEVEL3/g, '严重程度为三级缺陷'],
  [/issue_fact\.delay_cause/g, '延期原因'],
  [/issue_fact\.function_name/g, '功能名称'],
  [/issue_fact\.module_names/g, '模块标签'],
  [/issue_fact\.is_legacy/g, '历史遗留标记'],
  [/issue_fact\.raw_payload/g, '议题原始内容'],
  [/issue_fact/g, '议题数据'],
  [/merge_request_fact/g, '合并请求数据'],
  [/spider_issue_data\.cause/g, '老平台缺陷原因说明'],
  [/scope profile/gi, '范围规则'],
  [/illegal_list/g, '非法类型列表'],
  [/illegal_reasons/g, '非法类型'],
  [/illegal_reason/g, '非法类型'],
  [/DelayEnum/g, '延期原因枚举'],
  [/ModuleTableRow/g, '老平台缺陷汇总表'],
  [/testing_phase/g, '测试阶段'],
  [/severity_level/g, '严重程度'],
  [/priority_level/g, '优先级'],
  [/bug_status/g, '处理状态'],
  [/module_names/g, '模块'],
  [/function_name/g, '功能名称'],
  [/reason_category/g, '缺陷原因说明'],
  [/delay_issue/g, '申请延期'],
  [/is_response_delayed/g, '响应延期'],
  [/is_resolve_delayed/g, '解决延期'],
  [/research_template_time/g, '调研模板回复时间'],
  [/fixed_label_time/g, '修复完成时间'],
  [/raw_payload/g, '议题原始内容'],
  [/project_id\s*=\s*325/g, '限定为 CC_Product 客户问题项目'],
  [/project_id/g, '项目'],
  [/setFixQuery/g, '老平台已修复判定规则'],
  [/count\(([^)]*)\)/gi, '统计数量'],
  [/\bLEVEL1\b/g, '一级缺陷'],
  [/\bLEVEL2\b/g, '二级缺陷'],
  [/\bLEVEL3\b/g, '三级缺陷'],
  [/\bSUGGESTION\b/g, '建议类缺陷'],
  [/\bwhere\b/gi, '范围内'],
  [/\bcontains\b/gi, '包含'],
  [/\s{2,}/g, ' '],
];

export function businessRuleCopy(value: string | null | undefined) {
  if (!value) {
    return value;
  }
  return technicalCopyReplacements
    .reduce((text, [pattern, replacement]) => text.replace(pattern, replacement), value)
    .replace(/\s+[,，]/g, '，')
    .trim();
}

export function businessRuleStep(step: StatisticRuleFlowStep): StatisticRuleFlowStep {
  return {
    ...step,
    title: businessRuleCopy(step.title) || step.title,
    description: businessRuleCopy(step.description) || step.description,
  };
}

export function businessRuleMetric(metric: StatisticRuleMetricDefinition): StatisticRuleMetricDefinition {
  return {
    ...metric,
    label: businessRuleCopy(metric.label) || metric.label,
    definition: businessRuleCopy(metric.definition) || metric.definition,
    formula: businessRuleCopy(metric.formula) || metric.formula,
    note: businessRuleCopy(metric.note) ?? metric.note,
  };
}

export function businessRuleExplanation(
  explanation: StatisticBoardRuleExplanationResponse | null,
): StatisticBoardRuleExplanationResponse | null {
  if (!explanation) {
    return null;
  }
  return {
    ...explanation,
    title: businessRuleCopy(explanation.title) || explanation.title,
    scopeDescription: businessRuleCopy(explanation.scopeDescription) ?? explanation.scopeDescription,
    summary: businessRuleCopy(explanation.summary) ?? explanation.summary,
    unsupportedReason: businessRuleCopy(explanation.unsupportedReason) ?? explanation.unsupportedReason,
    flowSteps: explanation.flowSteps.map(businessRuleStep),
    metricDefinitions: explanation.metricDefinitions.map(businessRuleMetric),
  };
}
