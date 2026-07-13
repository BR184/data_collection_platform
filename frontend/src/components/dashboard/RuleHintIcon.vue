<script setup lang="ts">
import { computed } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import type { AnalyticsDashboardRule } from '../../types/api';

const props = withDefaults(
  defineProps<{
    rule?: AnalyticsDashboardRule | null;
    size?: number;
  }>(),
  { rule: null, size: 15 },
);

const emit = defineEmits<{
  (event: 'click', rule: AnalyticsDashboardRule | null): void;
}>();

const ruleItems = computed(() => [
  { label: '计算公式', value: props.rule?.formula },
  { label: '统计范围', value: props.rule?.scope },
  { label: '目标值', value: props.rule?.target },
  { label: '说明', value: props.rule?.description },
].filter((item): item is { label: string; value: string } => Boolean(item.value)));
</script>

<template>
  <el-tooltip placement="top" :show-after="200">
    <template #content>
      <div class="rule-hint__content">
        <strong>{{ rule?.title || '规则说明' }}</strong>
        <div v-for="item in ruleItems" :key="item.label" class="rule-hint__item">
          <span>{{ item.label }}：</span>{{ item.value }}
        </div>
        <div v-if="!ruleItems.length" class="rule-hint__item">当前指标暂无补充说明</div>
      </div>
    </template>
    <button
      type="button"
      class="rule-hint"
      :aria-label="`查看${rule?.title || '指标'}规则`"
      @click.stop="emit('click', rule)"
    >
      <el-icon :size="size"><QuestionFilled /></el-icon>
    </button>
  </el-tooltip>
</template>

<style scoped>
.rule-hint {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  color: #64748b;
  line-height: 1;
  cursor: help;
  background: transparent;
  border: 0;
}

.rule-hint:hover,
.rule-hint:focus-visible {
  color: #2563eb;
  outline: none;
}

.rule-hint__content {
  display: grid;
  max-width: 360px;
  gap: 6px;
  line-height: 1.55;
}

.rule-hint__item span {
  color: #cbd5e1;
}
</style>
