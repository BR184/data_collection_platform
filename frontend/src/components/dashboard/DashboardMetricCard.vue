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
      <span class="dashboard-metric-card__title">{{ metric.title }}</span>
      <button
        v-if="metric.detail"
        type="button"
        class="dashboard-metric-card__detail-action"
        @click.stop="handleTitleClick(metric)"
      >
        查看详情
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
    <div class="dashboard-metric-card__value" :data-status="metric.status || 'neutral'">
      <span class="dashboard-metric-card__value-number">{{ metric.displayValue }}</span>
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

.dashboard-metric-card__detail-action {
  flex: 0 0 auto;
  padding: 2px 6px;
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  background: #fff;
  cursor: pointer;
}

.dashboard-metric-card__detail-action:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-3);
  background: var(--el-color-primary-light-9);
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

.dashboard-metric-card__value-number {
  color: inherit;
}

.dashboard-metric-card__value[data-status='success'] {
  color: #16a34a;
}

.dashboard-metric-card__value[data-status='danger'] {
  color: #dc2626;
}

.dashboard-metric-card__value[data-status='neutral'] {
  color: #0f172a;
}

.dashboard-metric-card__value small {
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
}
</style>
