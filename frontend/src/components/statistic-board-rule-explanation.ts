import type { StatisticBoardRuleExplanationResponse } from '../types/api';

interface RuleFlowStepLike {
  inputCount: number;
  outputCount: number;
}

export interface RuleExplanationOverview {
  firstInputCount: number;
  finalOutputCount: number;
  finalRetainedRate: string;
  summary: string;
}

export function createFallbackRuleExplanation(boardKey: string, reason: string): StatisticBoardRuleExplanationResponse {
  return {
    boardKey,
    supported: false,
    title: '规则说明',
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

export function ruleStepRemovedCount(step: { inputCount: number; outputCount: number }) {
  return Math.max(step.inputCount - step.outputCount, 0);
}

export function ruleStepRetainedRate(step: RuleFlowStepLike) {
  if (!step.inputCount) {
    return '0%';
  }
  return `${((step.outputCount / step.inputCount) * 100).toFixed(1)}%`;
}

export function ruleStepSummary(step: RuleFlowStepLike, index: number) {
  const removed = ruleStepRemovedCount(step);
  if (removed <= 0) {
    return `第 ${index + 1} 步处理后，数据没有减少，仍保留 ${step.outputCount} 条。`;
  }
  return `第 ${index + 1} 步处理后，剩余 ${step.outputCount} 条，较上一步减少 ${removed} 条，保留比例 ${ruleStepRetainedRate(step)}。`;
}

export function metricFormulaSummary(metric: { label: string; definition: string }) {
  return `${metric.label}：${metric.definition}`;
}

export function buildRuleExplanationOverview(
  explanation: Pick<StatisticBoardRuleExplanationResponse, 'supported' | 'summary' | 'flowSteps'> | null | undefined,
): RuleExplanationOverview {
  const flowSteps = explanation?.flowSteps ?? [];
  const firstInputCount = flowSteps[0]?.inputCount ?? 0;
  const finalOutputCount = flowSteps.length ? flowSteps[flowSteps.length - 1].outputCount : 0;
  const finalRetainedRate = ruleStepRetainedRate({
    inputCount: firstInputCount,
    outputCount: finalOutputCount,
  });

  if (!explanation?.supported) {
    return {
      firstInputCount,
      finalOutputCount,
      finalRetainedRate,
      summary: '',
    };
  }

  if (!flowSteps.length) {
    return {
      firstInputCount,
      finalOutputCount,
      finalRetainedRate,
      summary: explanation.summary || '当前页面已经启用规则说明，但暂时没有可展示的统计过程。',
    };
  }

  return {
    firstInputCount,
    finalOutputCount,
    finalRetainedRate,
    summary: `当前结果一共基于 ${firstInputCount} 条原始数据逐步筛选，最后保留 ${finalOutputCount} 条，最终保留比例为 ${finalRetainedRate}。`,
  };
}
