import { describe, expect, it } from 'vitest';
import {
  buildRuleExplanationOverview,
  metricFormulaSummary,
  ruleStepRemovedCount,
  ruleStepRetainedRate,
  ruleStepSummary,
} from './statistic-board-rule-explanation';

describe('statistic board rule explanation', () => {
  it('builds overview from supported flow steps', () => {
    const overview = buildRuleExplanationOverview({
      supported: true,
      summary: 'ignored',
      flowSteps: [
        { key: 'a', title: 'a', description: 'a', inputCount: 20, outputCount: 12, samples: [] },
        { key: 'b', title: 'b', description: 'b', inputCount: 12, outputCount: 5, samples: [] },
      ],
    });

    expect(overview.firstInputCount).toBe(20);
    expect(overview.finalOutputCount).toBe(5);
    expect(overview.finalRetainedRate).toBe('25.0%');
    expect(overview.summary).toContain('原始数据 20 条');
  });

  it('falls back to backend summary when no flow steps exist', () => {
    const overview = buildRuleExplanationOverview({
      supported: true,
      summary: '后端摘要',
      flowSteps: [],
    });

    expect(overview.summary).toBe('后端摘要');
  });

  it('formats step statistics and formulas', () => {
    const step = { inputCount: 10, outputCount: 6 };

    expect(ruleStepRemovedCount(step)).toBe(4);
    expect(ruleStepRetainedRate(step)).toBe('60.0%');
    expect(ruleStepSummary(step, 1)).toContain('排除 4 条');
    expect(metricFormulaSummary({ label: '修复率', definition: '已修复 / 总数' })).toBe('修复率：已修复 / 总数');
  });
});
