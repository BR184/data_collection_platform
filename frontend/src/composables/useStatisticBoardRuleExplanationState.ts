import { computed, type Ref } from 'vue';
import type { StatisticBoardRuleExplanationResponse } from '../types/api';
import { buildRuleExplanationOverview } from '../components/statistic-board-rule-explanation';
import { businessRuleExplanation } from '../utils/rule-explanation-copy';

export function useStatisticBoardRuleExplanationState(
  ruleExplanation: Ref<StatisticBoardRuleExplanationResponse | null>,
) {
  const readableRuleExplanation = computed(() => businessRuleExplanation(ruleExplanation.value));
  const ruleExplanationSteps = computed(() => readableRuleExplanation.value?.flowSteps ?? []);
  const ruleExplanationMetrics = computed(() => readableRuleExplanation.value?.metricDefinitions ?? []);
  const ruleExclusionSteps = computed(() => ruleExplanationSteps.value.slice(1));
  const ruleExplanationOverview = computed(() => buildRuleExplanationOverview(readableRuleExplanation.value));
  const ruleFirstInputCount = computed(() => ruleExplanationOverview.value.firstInputCount);
  const ruleFinalOutputCount = computed(() => ruleExplanationOverview.value.finalOutputCount);
  const ruleFinalRetainedRate = computed(() => ruleExplanationOverview.value.finalRetainedRate);
  const qaFriendlyRuleSummary = computed(() => ruleExplanationOverview.value.summary);

  return {
    ruleExplanationSteps,
    ruleExplanationMetrics,
    ruleExclusionSteps,
    ruleExplanationOverview,
    ruleFirstInputCount,
    ruleFinalOutputCount,
    ruleFinalRetainedRate,
    qaFriendlyRuleSummary,
  };
}
