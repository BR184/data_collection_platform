import { ref } from 'vue';
import type { StatisticBoardRuleExplanationResponse } from '../types/api';

interface UseRuleExplanationPanelOptions {
  load: () => Promise<StatisticBoardRuleExplanationResponse>;
  fallback: (reason: string) => StatisticBoardRuleExplanationResponse;
}

export function useRuleExplanationPanel(options: UseRuleExplanationPanelOptions) {
  const ruleExplanationVisible = ref(false);
  const ruleExplanationLoading = ref(false);
  const ruleExplanation = ref<StatisticBoardRuleExplanationResponse | null>(null);

  async function loadRuleExplanation() {
    ruleExplanationLoading.value = true;
    try {
      ruleExplanation.value = await options.load();
    } catch {
      ruleExplanation.value = options.fallback('规则说明加载失败，请稍后重试。');
    } finally {
      ruleExplanationLoading.value = false;
    }
  }

  async function openRuleExplanation() {
    ruleExplanationVisible.value = true;
    if (!ruleExplanation.value && !ruleExplanationLoading.value) {
      await loadRuleExplanation();
    }
  }

  function resetRuleExplanation() {
    ruleExplanation.value = null;
  }

  return {
    ruleExplanation,
    ruleExplanationLoading,
    ruleExplanationVisible,
    loadRuleExplanation,
    openRuleExplanation,
    resetRuleExplanation,
  };
}
