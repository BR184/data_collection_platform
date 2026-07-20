<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
// 图表面板封装 ECharts 生命周期，父级只需要传入 option 和点击事件。
// 主题注册、resize 和销毁集中在这里处理，避免各看板重复写图表样板代码。
import type { EChartsOption } from 'echarts';
import type { ECElementEvent } from 'echarts/core';
import { init, type EChartsType } from './echarts-runtime';
import { registerChartTheme } from './chart-theme';
import { normalizeEChartPointClick, type EChartPointClickEvent } from './echart-panel-events';

const props = withDefaults(
  defineProps<{
    option?: EChartsOption | null;
    height?: number;
    loading?: boolean;
    emptyText?: string;
  }>(),
  {
    option: null,
    height: 320,
    loading: false,
    emptyText: '暂无图表数据',
  },
);

const emit = defineEmits<{
  (event: 'point-click', value: EChartPointClickEvent): void;
}>();

const rootRef = ref<HTMLDivElement | null>(null);
let chart: EChartsType | null = null;
let resizeObserver: ResizeObserver | null = null;

const hasOption = computed(() => Boolean(props.option));

function resizeChart() {
  chart?.resize();
}

function ensureChart() {
  if (chart || !rootRef.value) {
    return;
  }
  chart = init(rootRef.value, registerChartTheme(), {
    renderer: 'svg',
  });
  chart.on('click', handlePointClick);
}

function handlePointClick(event: ECElementEvent) {
  emit('point-click', normalizeEChartPointClick(event));
}

function syncLoading() {
  if (!chart) {
    return;
  }
  if (props.loading) {
    chart.showLoading('default', {
      text: '加载中',
      color: '#1677ff',
      textColor: '#6b7280',
      maskColor: 'rgba(255,255,255,0.75)',
    });
    return;
  }
  chart.hideLoading();
}

function renderChart() {
  if (!hasOption.value) {
    chart?.clear();
    return;
  }
  ensureChart();
  if (!chart || !props.option) {
    return;
  }
  chart.setOption(props.option, true);
  syncLoading();
}

watch(
  () => props.option,
  () => {
    renderChart();
  },
  { deep: true },
);

watch(
  () => props.loading,
  () => syncLoading(),
);

onMounted(() => {
  if (rootRef.value && 'ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(() => resizeChart());
    resizeObserver.observe(rootRef.value);
  }
  renderChart();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  chart?.off('click', handlePointClick);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div class="chart-panel">
    <div v-if="!hasOption" class="chart-panel__empty">
      <span>{{ emptyText }}</span>
    </div>
    <div v-show="hasOption" ref="rootRef" class="chart-panel__canvas" :style="{ height: `${height}px` }" />
  </div>
</template>

<style scoped>
.chart-panel {
  min-height: 120px;
}

.chart-panel__canvas {
  width: 100%;
}

/* ECharts draws slider borders, shadows and handles slightly outside its SVG viewport.
 * The surrounding chart/card containers are already visible; the SVG itself must not
 * clip those edge pixels or both horizontal and vertical sliders lose their outer edge. */
.chart-panel__canvas :deep(svg) {
  overflow: visible !important;
}

/* The SVG renderer also creates a relative wrapper around the SVG. That wrapper defaults to
 * overflow:hidden and otherwise clips the vertical slider's rotated handle on the right edge. */
.chart-panel__canvas :deep(> div) {
  overflow: visible !important;
}

.chart-panel__empty {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #98a2b3;
  border: 1px dashed #e5e7eb;
  border-radius: 8px;
  background: #fcfcfd;
}
</style>
