<script setup lang="ts">
import RuleExplanationDrawer from './RuleExplanationDrawer.vue';
import type {
  StatisticBoardRuleExplanationResponse,
  StatisticRuleFlowStep,
  StatisticRuleMetricDefinition,
} from '../types/api';

defineProps<{
  modelValue: boolean;
  loading: boolean;
  explanation: StatisticBoardRuleExplanationResponse | null;
  steps: StatisticRuleFlowStep[];
  metrics: StatisticRuleMetricDefinition[];
  exclusionSteps: StatisticRuleFlowStep[];
  firstInputCount: number;
  finalOutputCount: number;
  finalRetainedRate: string;
  qaFriendlySummary: string;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();
</script>

<template>
  <RuleExplanationDrawer
    :model-value="modelValue"
    :loading="loading"
    :title="explanation?.title || '规则说明'"
    :supported="Boolean(explanation?.supported)"
    :unsupported-reason="explanation?.unsupportedReason || '当前统计表暂不支持规则说明。'"
    :summary-main="qaFriendlySummary"
    :summary="explanation?.summary"
    :overview-cards="[
      { label: '原始数据', value: firstInputCount },
      { label: '最终保留', value: finalOutputCount },
      { label: '最终保留比例', value: finalRetainedRate },
    ]"
    :info-items="[
      { label: '当前使用规则版本', value: explanation?.version },
      { label: '这次统计包含哪些数据', value: explanation?.scopeDescription },
    ]"
    :exclusion-steps="exclusionSteps"
    :process-steps="steps"
    :metrics="metrics"
    exclusion-title="哪些会被排除"
    process-title="数据是怎么一步步变少的"
    metrics-title="最后这些数字怎么算"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>
