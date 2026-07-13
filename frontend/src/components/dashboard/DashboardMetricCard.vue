<script setup lang="ts">
import { Download } from '@element-plus/icons-vue';
import type { AnalyticsDashboardMetric, AnalyticsDashboardRule } from '../../types/api';
import RuleHintIcon from './RuleHintIcon.vue';

defineProps<{
  metric: AnalyticsDashboardMetric;
  rule?: AnalyticsDashboardRule | null;
}>();

const emit = defineEmits<{
  (event: 'title-click', metric: AnalyticsDashboardMetric): void;
  (event: 'rule-click', rule: AnalyticsDashboardRule | null): void;
  (event: 'export', metric: AnalyticsDashboardMetric): void;
}>();

function handleTitleClick(metric: AnalyticsDashboardMetric) {
  if (metric.detail) {
    emit('title-click', metric);
  }
}
</script>

<template>
  <article class="dashboard-metric-card">
    <header class="dashboard-metric-card__header">
      <button
        type="button"
        class="dashboard-metric-card__title"
        :class="{ 'is-clickable': metric.detail }"
        :disabled="!metric.detail"
        @click="handleTitleClick(metric)"
      >
        {{ metric.title }}
      </button>
      <RuleHintIcon v-if="metric.ruleKey" :rule="rule" @click="emit('rule-click', $event)" />
      <el-button
        v-if="metric.export"
        text
        circle
        size="small"
        aria-label="下载指标数据"
        @click.stop="emit('export', metric)"
      >
        <el-icon><Download /></el-icon>
      </el-button>
    </header>
    <div class="dashboard-metric-card__value">
      <span>{{ metric.displayValue }}</span>
      <small v-if="metric.unit">{{ metric.unit }}</small>
    </div>
  </article>
</template>

<style scoped>
.dashboard-metric-card {
  min-width: 0;
  padding: 18px;
  background: #fff;
  border: 1px solid #e5eaf1;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgb(15 23 42 / 4%);
}

.dashboard-metric-card__header {
  display: flex;
  align-items: center;
  min-height: 24px;
  gap: 6px;
}

.dashboard-metric-card__title {
  min-width: 0;
  padding: 0;
  overflow: hidden;
  color: #475569;
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: transparent;
  border: 0;
}

.dashboard-metric-card__title.is-clickable {
  color: #1d4ed8;
  cursor: pointer;
}

.dashboard-metric-card__header :deep(.el-button) {
  margin-left: auto;
}

.dashboard-metric-card__value {
  display: flex;
  align-items: baseline;
  margin-top: 12px;
  color: #0f172a;
  font-size: 30px;
  font-weight: 650;
  gap: 5px;
}

.dashboard-metric-card__value small {
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
}
</style>
