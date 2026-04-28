import { ref } from 'vue';
import type { StatisticBoardRuleExplanationResponse } from '../types/api';

interface UseRuleExplanationPanelOptions {
  load: () => Promise<StatisticBoardRuleExplanationResponse>;
  fallback: (reason: string) => StatisticBoardRuleExplanationResponse;
  warn?: (message: string) => void;
  openFallbackReason?: string;
  unsupportedWarning?: string;
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
    if (!ruleExplanation.value && !ruleExplanationLoading.value) {
      if (options.openFallbackReason) {
        ruleExplanation.value = options.fallback(options.openFallbackReason);
      } else {
        await loadRuleExplanation();
      }
    }
    if (ruleExplanation.value && !ruleExplanation.value.supported) {
      options.warn?.(ruleExplanation.value.unsupportedReason || options.unsupportedWarning || '当前统计表暂不支持规则说明');
    }
    ruleExplanationVisible.value = true;
  }

  function handleRuleExplanationVisibleChange(visible: boolean) {
    ruleExplanationVisible.value = visible;
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
    handleRuleExplanationVisibleChange,
    resetRuleExplanation,
  };
}
