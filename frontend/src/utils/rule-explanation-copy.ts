import type { StatisticBoardRuleExplanationResponse, StatisticRuleFlowStep, StatisticRuleMetricDefinition } from '../types/api';

const technicalCopyReplacements: Array<[RegExp, string]> = [
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
