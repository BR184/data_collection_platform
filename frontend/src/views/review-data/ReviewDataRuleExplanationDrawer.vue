<script setup lang="ts">
import { computed } from 'vue';
import RuleExplanationDrawer from '../../components/RuleExplanationDrawer.vue';
import type { ReviewDataRuleExplanationContent } from './review-data-rule-explanation';

const props = defineProps<{
  visible: boolean;
  content: ReviewDataRuleExplanationContent;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
}>();

const guidanceCards = computed(() =>
  props.content.fieldDefinitions.map((field) => ({
    key: field.key,
    title: field.label,
    description: field.description,
    guidance: field.guidance,
    note: field.note,
    badge: '关键字段',
  })),
);
</script>

<template>
  <RuleExplanationDrawer
    :model-value="visible"
    :title="content.title"
    :supported="true"
    :summary-main="content.summary"
    :summary="content.scopeDescription"
    :info-items="[{ label: '当前使用规则版本', value: content.version }, { label: '适用场景', value: '评审数据录入、查看与统计口径' }]"
    :guidance-cards="guidanceCards"
    :metrics="content.metricDefinitions"
    :questions="content.commonQuestions"
    guidance-title="填写指南"
    metrics-title="数据是怎么计算的"
    questions-title="常见问题"
    @update:model-value="emit('update:visible', $event)"
  />
</template>
