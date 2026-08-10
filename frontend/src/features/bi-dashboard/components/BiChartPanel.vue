<script setup lang="ts">
import { Download } from '@element-plus/icons-vue';
import { computed, ref, type PropType } from 'vue';
import { ElMessage } from '../../../element-plus-services';
import { authState } from '../../../composables/auth-state';
import { hasPermission } from '../../../feature-manifest';
import type { BiChart } from '../charts/BiChart';
import { exportBiChartPng } from '../charts/export-chart';
import type { BiDataStatus, BiPageKey } from '../data/types';
import BiChartCanvas from './BiChartCanvas.vue';

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  chart: { type: Object as PropType<BiChart<never>>, required: true },
  data: { type: null as unknown as PropType<unknown>, required: true },
  height: { type: Number, default: 340 },
  status: { type: String as PropType<BiDataStatus>, default: 'READY' },
  statusMessage: { type: String, default: '' },
  productVersionId: { type: Number, required: true },
  pageKey: { type: String as PropType<BiPageKey>, required: true },
  sourceVersion: { type: String, default: '' },
  layout: {
    type: String as PropType<'full' | 'compact' | 'wide' | 'primary' | 'secondary' | 'half' | 'wide-only'>,
    default: 'full',
  },
  variant: { type: String as PropType<'default' | 'analysis' | 'pie' | 'summary'>, default: 'default' },
});

const emit = defineEmits<{
  (event: 'point-click', value: { dataIndex: number; name: string }): void;
}>();

const exporting = ref(false);
const typedChart = computed(() => props.chart as unknown as BiChart<unknown>);
const hasData = computed(() => typedChart.value.hasData(props.data));
const canDownload = computed(() => hasData.value
  && Boolean(props.sourceVersion)
  && hasPermission(authState.currentUser, 'bi.dashboard.download'));
const stateTitle = computed(() => {
  if (props.status === 'ERROR') return '图表加载失败';
  if (props.status === 'INCOMPLETE') return '数据暂不完整';
  if (props.status === 'NOT_APPLICABLE') return '当前范围不适用';
  return '当前范围暂无数据';
});

async function download(): Promise<void> {
  if (!canDownload.value || exporting.value) return;
  exporting.value = true;
  try {
    await exportBiChartPng({
      productVersionId: props.productVersionId,
      pageKey: props.pageKey,
      sourceVersion: props.sourceVersion,
      title: props.title,
      chart: typedChart.value,
      data: props.data,
    });
    ElMessage.success('图表 PNG 已生成');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '图表下载失败');
  } finally {
    exporting.value = false;
  }
}
</script>

<template>
  <section class="bi-chart-panel" :class="[`bi-chart-panel--${layout}`, `bi-chart-panel--${variant}`]">
    <header class="bi-chart-panel__header">
      <div class="bi-chart-panel__heading">
        <h3>{{ title }}</h3>
        <p v-if="subtitle">{{ subtitle }}</p>
      </div>
      <div class="bi-chart-panel__actions">
        <slot name="actions" />
        <el-tooltip content="下载完整数据 PNG" placement="top">
          <el-button
            class="bi-icon-button"
            :icon="Download"
            circle
            :disabled="!canDownload"
            :loading="exporting"
            :aria-label="`下载${title}`"
            @click="download"
          />
        </el-tooltip>
      </div>
    </header>

    <el-alert
      v-if="status !== 'READY' && hasData"
      class="bi-chart-panel__notice"
      :type="status === 'ERROR' ? 'error' : 'warning'"
      :closable="false"
      :title="statusMessage || stateTitle"
      show-icon
    />

    <BiChartCanvas
      v-if="hasData"
      :chart="chart"
      :data="data"
      :height="height"
      @point-click="emit('point-click', $event)"
    />
    <div v-else class="bi-chart-panel__state" :style="{ minHeight: `${Math.min(height, 280)}px` }">
      <strong>{{ stateTitle }}</strong>
      <span>{{ statusMessage || '当前筛选范围内没有可绘制的真实数据。' }}</span>
    </div>
    <footer v-if="$slots.footer" class="bi-chart-panel__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.bi-chart-panel {
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 14px 16px 10px;
  border: 0;
  border-radius: 6px;
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.06);
}

.bi-chart-panel--full { grid-column: span 12; }
.bi-chart-panel--compact { grid-column: span 12; }
.bi-chart-panel--wide { grid-column: span 12; }
.bi-chart-panel--primary { grid-column: span 12; }
.bi-chart-panel--secondary { grid-column: span 12; }
.bi-chart-panel--half { grid-column: span 12; }
.bi-chart-panel--wide-only { grid-column: span 12; }

.bi-chart-panel__header {
  min-height: 42px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.bi-chart-panel--pie .bi-chart-panel__header {
  min-height: 30px;
  margin-bottom: 4px;
}

/* 摘要卡统一使用静态页的紧凑标题行，避免通用标题槽拉高相邻卡片。 */
.bi-chart-panel--summary .bi-chart-panel__header {
  min-height: 30px;
  margin-bottom: 4px;
}

.bi-chart-panel--summary,
.bi-chart-panel--pie {
  height: auto;
  align-self: start;
}

.bi-chart-panel__heading {
  min-width: 0;
}

.bi-chart-panel__heading h3 {
  margin: 0;
  color: #27364a;
  font-size: 15px;
  font-weight: 650;
  line-height: 22px;
  letter-spacing: 0;
}

.bi-chart-panel__heading p {
  margin: 3px 0 0;
  color: #98a2b3;
  font-size: 12px;
  line-height: 18px;
}

.bi-chart-panel__actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.bi-icon-button {
  width: 30px;
  height: 30px;
}

.bi-chart-panel__notice {
  margin: 8px 0 4px;
}

.bi-chart-panel__footer {
  flex: 0 0 auto;
  margin: 2px 0 2px;
  color: #667085;
  font-size: 12px;
  line-height: 18px;
}

.bi-chart-panel__state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #64748b;
  text-align: center;
}

.bi-chart-panel__state strong {
  color: #475569;
  font-size: 14px;
}

.bi-chart-panel__state span {
  max-width: 420px;
  font-size: 12px;
  line-height: 20px;
}

@media (min-width: 1180px) {
  .bi-chart-panel--compact { grid-column: span 4; }
  .bi-chart-panel--wide { grid-column: span 8; }
  .bi-chart-panel--wide,
  .bi-chart-panel--compact,
  .bi-chart-panel--wide-only {
    min-height: 356px;
  }
}

@media (min-width: 1260px) {
  .bi-chart-panel--half { grid-column: span 6; }
  .bi-chart-panel--primary { grid-column: span 7; }
  .bi-chart-panel--secondary { grid-column: span 5; }
}

@media (min-width: 1680px) {
  .bi-chart-panel--wide-only { grid-column: span 6; }
}

@media (max-width: 760px) {
  .bi-chart-panel--full,
  .bi-chart-panel--compact,
  .bi-chart-panel--wide,
  .bi-chart-panel--primary,
  .bi-chart-panel--secondary,
  .bi-chart-panel--half,
  .bi-chart-panel--wide-only { grid-column: span 1; }
}
</style>
