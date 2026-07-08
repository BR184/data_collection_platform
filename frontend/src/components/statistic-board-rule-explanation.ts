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
    return `第 ${index + 1} 步输出 ${step.outputCount} 条，未排除数据。`;
  }
  return `第 ${index + 1} 步输出 ${step.outputCount} 条，排除 ${removed} 条，保留比例 ${ruleStepRetainedRate(step)}。`;
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
      summary: explanation.summary || '当前规则说明暂无处理流程数据。',
    };
  }

  return {
    firstInputCount,
    finalOutputCount,
    finalRetainedRate,
    summary: `原始数据 ${firstInputCount} 条，最终保留 ${finalOutputCount} 条，保留比例 ${finalRetainedRate}。`,
  };
}
