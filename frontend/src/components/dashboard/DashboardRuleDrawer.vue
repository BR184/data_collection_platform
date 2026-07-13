<script setup lang="ts">
import type { AnalyticsDashboardRule } from '../../types/api';

withDefaults(
  defineProps<{
    modelValue: boolean;
    rule?: AnalyticsDashboardRule | null;
    title?: string;
  }>(),
  {
    rule: null,
    title: '统计规则',
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    size="440px"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="rule" class="dashboard-rule-drawer__content">
      <h3>{{ rule.title }}</h3>
      <dl>
        <template v-if="rule.formula">
          <dt>计算公式</dt>
          <dd>{{ rule.formula }}</dd>
        </template>
        <template v-if="rule.scope">
          <dt>统计范围</dt>
          <dd>{{ rule.scope }}</dd>
        </template>
        <template v-if="rule.target">
          <dt>目标值</dt>
          <dd>{{ rule.target }}</dd>
        </template>
        <template v-if="rule.description">
          <dt>说明</dt>
          <dd>{{ rule.description }}</dd>
        </template>
      </dl>
    </div>
    <el-empty v-else description="当前指标暂无规则说明" />
  </el-drawer>
</template>

<style scoped>
.dashboard-rule-drawer__content h3 {
  margin: 0 0 18px;
  color: #172033;
  font-size: 18px;
}

.dashboard-rule-drawer__content dl {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  margin: 0;
  gap: 14px 12px;
}

.dashboard-rule-drawer__content dt {
  color: #64748b;
  font-weight: 600;
}

.dashboard-rule-drawer__content dd {
  margin: 0;
  color: #1f2937;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
</style>
