<script setup lang="ts">
import { Download } from '@element-plus/icons-vue';
import type {
  AnalyticsDashboardChart,
  AnalyticsDashboardRule,
} from '../../types/api';
import EChartPanel from '../charts/EChartPanel.vue';
import type { EChartPointClickEvent } from '../charts/echart-panel-events';
import RuleHintIcon from './RuleHintIcon.vue';

withDefaults(
  defineProps<{
    chart: AnalyticsDashboardChart;
    rule?: AnalyticsDashboardRule | null;
    loading?: boolean;
    height?: number;
  }>(),
  { rule: null, loading: false, height: 320 },
);

const emit = defineEmits<{
  (event: 'title-click', chart: AnalyticsDashboardChart): void;
  (event: 'point-click', point: EChartPointClickEvent, chart: AnalyticsDashboardChart): void;
  (event: 'rule-click', rule: AnalyticsDashboardRule | null): void;
  (event: 'export', chart: AnalyticsDashboardChart): void;
}>();
</script>

<template>
  <article class="dashboard-chart-card">
    <header class="dashboard-chart-card__header">
      <div class="dashboard-chart-card__heading">
        <div class="dashboard-chart-card__title-line">
          <button
            type="button"
            class="dashboard-chart-card__title"
            :class="{ 'is-clickable': chart.detail }"
            :disabled="!chart.detail"
            @click="emit('title-click', chart)"
          >
            {{ chart.title }}
          </button>
          <RuleHintIcon v-if="chart.ruleKey" :rule="rule" @click="emit('rule-click', $event)" />
        </div>
        <p v-if="chart.subtitle">{{ chart.subtitle }}</p>
      </div>
      <el-button
        v-if="chart.export"
        text
        circle
        aria-label="下载图表数据"
        @click.stop="emit('export', chart)"
      >
        <el-icon><Download /></el-icon>
      </el-button>
    </header>
    <EChartPanel
      :option="chart.option"
      :height="height"
      :loading="loading"
      @point-click="emit('point-click', $event, chart)"
    />
  </article>
</template>

<style scoped>
.dashboard-chart-card {
  min-width: 0;
  padding: 20px;
  background: #fff;
  border: 1px solid #e5eaf1;
  border-radius: 14px;
  box-shadow: 0 10px 28px rgb(15 23 42 / 5%);
}

.dashboard-chart-card__header,
.dashboard-chart-card__title-line {
  display: flex;
  align-items: center;
}

.dashboard-chart-card__header {
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 16px;
}

.dashboard-chart-card__heading {
  min-width: 0;
}

.dashboard-chart-card__title-line {
  gap: 6px;
}

.dashboard-chart-card__title {
  padding: 0;
  color: #172033;
  font: inherit;
  font-size: 16px;
  font-weight: 650;
  text-align: left;
  background: transparent;
  border: 0;
}

.dashboard-chart-card__title.is-clickable {
  color: #1d4ed8;
  cursor: pointer;
}

.dashboard-chart-card__heading p {
  margin: 5px 0 0;
  color: #64748b;
  font-size: 13px;
}
</style>
