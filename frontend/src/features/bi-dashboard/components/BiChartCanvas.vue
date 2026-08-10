<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type PropType } from 'vue';
import type { BiChart } from '../charts/BiChart';
import { init, type EChartsType } from '../charts/bi-echarts-runtime';
import { registerBiChartTheme } from '../charts/theme';

const emit = defineEmits<{
  (event: 'point-click', value: { dataIndex: number; name: string }): void;
}>();

const props = defineProps({
  chart: { type: Object as PropType<BiChart<never>>, required: true },
  data: { type: null as unknown as PropType<unknown>, required: true },
  height: { type: Number, default: 340 },
});

const rootRef = ref<HTMLDivElement | null>(null);
let instance: EChartsType | null = null;
let resizeObserver: ResizeObserver | null = null;

const typedChart = computed(() => props.chart as unknown as BiChart<unknown>);
const hasData = computed(() => typedChart.value.hasData(props.data));

function render(): void {
  if (!rootRef.value || !hasData.value) {
    instance?.clear();
    return;
  }
  if (!instance) {
    instance = init(rootRef.value, registerBiChartTheme(), { renderer: 'canvas' });
    instance.on('click', (event) => {
      if (typeof event.dataIndex === 'number') {
        emit('point-click', { dataIndex: event.dataIndex, name: String(event.name ?? '') });
      }
    });
  }
  const width = rootRef.value.clientWidth;
  const height = rootRef.value.clientHeight;
  instance.setOption(typedChart.value.build(props.data, { mode: 'view', width, height }), true);
}

watch(() => [props.chart, props.data] as const, render, { deep: true });

onMounted(() => {
  render();
  if (rootRef.value && 'ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(() => {
      instance?.resize();
      render();
    });
    resizeObserver.observe(rootRef.value);
  }
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  instance?.dispose();
  resizeObserver = null;
  instance = null;
});
</script>

<template>
  <div v-if="hasData" ref="rootRef" class="bi-chart-canvas" :style="{ height: `${height}px` }" />
</template>

<style scoped>
.bi-chart-canvas {
  width: 100%;
  min-width: 0;
}
</style>
