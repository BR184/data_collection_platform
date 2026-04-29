import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import { useStatisticBoardRuleExplanationState } from './useStatisticBoardRuleExplanationState';
import type { StatisticBoardRuleExplanationResponse } from '../types/api';

function createExplanation(): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: 'code-review',
    supported: true,
    title: '规则说明',
    version: 'v1',
    scopeDescription: '当前数据范围',
    summary: '后端摘要',
    flowSteps: [
      {
        key: 'input',
        title: '输入',
        description: '读取数据',
        inputCount: 10,
        outputCount: 10,
        samples: [],
      },
      {
        key: 'filter',
        title: '过滤',
        description: '过滤无效记录',
        inputCount: 10,
        outputCount: 4,
        samples: [],
      },
    ],
    metricDefinitions: [
      {
        key: 'blocked',
        label: '阻塞数',
        definition: '阻塞问题数量',
        formula: 'count(blocked)',
      },
    ],
  };
}

describe('useStatisticBoardRuleExplanationState', () => {
  it('derives overview, exclusion steps, and metric definitions from rule explanation', () => {
    const ruleExplanation = ref<StatisticBoardRuleExplanationResponse | null>(createExplanation());

    const state = useStatisticBoardRuleExplanationState(ruleExplanation);

    expect(state.ruleExplanationSteps.value.map((step) => step.key)).toEqual(['input', 'filter']);
    expect(state.ruleExclusionSteps.value.map((step) => step.key)).toEqual(['filter']);
    expect(state.ruleExplanationMetrics.value.map((metric) => metric.key)).toEqual(['blocked']);
    expect(state.ruleFirstInputCount.value).toBe(10);
    expect(state.ruleFinalOutputCount.value).toBe(4);
    expect(state.ruleFinalRetainedRate.value).toBe('40.0%');
    expect(state.qaFriendlyRuleSummary.value).toBe(
      '当前结果一共基于 10 条原始数据逐步筛选，最后保留 4 条，最终保留比例为 40.0%。',
    );
  });

  it('falls back to empty derived state when rule explanation is not loaded', () => {
    const state = useStatisticBoardRuleExplanationState(ref(null));

    expect(state.ruleExplanationSteps.value).toEqual([]);
    expect(state.ruleExplanationMetrics.value).toEqual([]);
    expect(state.ruleExclusionSteps.value).toEqual([]);
    expect(state.ruleFirstInputCount.value).toBe(0);
    expect(state.ruleFinalOutputCount.value).toBe(0);
    expect(state.ruleFinalRetainedRate.value).toBe('0%');
    expect(state.qaFriendlyRuleSummary.value).toBe('');
  });
});
