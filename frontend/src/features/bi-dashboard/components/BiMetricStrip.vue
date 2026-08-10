<script setup lang="ts">
import type { PropType } from 'vue';

export interface BiMetricItem {
  label: string;
  value: string;
  detail?: string;
  status?: 'success' | 'danger' | 'neutral';
}

defineProps({
  items: { type: Array as PropType<BiMetricItem[]>, required: true },
  variant: { type: String as PropType<'default' | 'supporting' | 'review' | 'quality'>, default: 'default' },
});
</script>

<template>
  <section
    class="bi-metric-strip"
    :class="{
      'bi-metric-strip--four': items.length === 4,
      'bi-metric-strip--many': items.length > 4,
      [`bi-metric-strip--${variant}`]: true,
    }"
    :style="{ '--metric-count': Math.min(items.length, 6) }"
  >
    <article v-for="item in items" :key="item.label" class="bi-metric" :class="`is-${item.status ?? 'neutral'}`">
      <span class="bi-metric__label">{{ item.label }}</span>
      <strong class="bi-metric__value">{{ item.value }}</strong>
      <span v-if="item.detail" class="bi-metric__detail">{{ item.detail }}</span>
    </article>
  </section>
</template>

<style scoped>
.bi-metric-strip {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(var(--metric-count), minmax(0, 1fr));
  border: 1px solid #e8ecf2;
  border-radius: 6px;
  background: #ffffff;
  overflow: hidden;
}

.bi-metric {
  position: relative;
  min-width: 0;
  min-height: 92px;
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  border-right: 1px solid #e8edf4;
}

.bi-metric:last-child {
  border-right: 0;
}

.bi-metric__label {
  color: #475467;
  font-size: 12px;
  line-height: 18px;
}

.bi-metric__value {
  min-width: 0;
  overflow: hidden;
  color: #5470c6;
  font-size: 27px;
  font-weight: 700;
  line-height: 30px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bi-metric__detail {
  color: #7b8797;
  font-size: 11px;
  line-height: 16px;
}

.bi-metric.is-success .bi-metric__value { color: #47724f; }
.bi-metric.is-danger .bi-metric__value { color: #993e45; }
.bi-metric-strip--supporting .bi-metric { min-height: 78px; padding-top: 11px; padding-bottom: 11px; }
.bi-metric-strip--supporting .bi-metric__value { font-size: 24px; line-height: 30px; }
.bi-metric-strip--review .bi-metric { min-height: 94px; }
.bi-metric-strip--quality .bi-metric { min-height: 88px; }
.bi-metric-strip--quality .bi-metric__value { font-size: 25px; }

@media (max-width: 1280px) {
  .bi-metric-strip--many { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .bi-metric-strip--many .bi-metric:nth-child(3n) { border-right: 0; }
  .bi-metric-strip--many .bi-metric:nth-child(n + 4) { border-top: 1px solid #e8edf4; }

  .bi-metric-strip--four { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bi-metric-strip--four .bi-metric:nth-child(2n) { border-right: 0; }
  .bi-metric-strip--four .bi-metric:nth-child(n + 3) { border-top: 1px solid #e8edf4; }
}

@media (max-width: 760px) {
  .bi-metric-strip { gap: 10px; border: 0; background: transparent; overflow: visible; }
  .bi-metric { border: 1px solid #e8ecf2; border-radius: 6px; }
  .bi-metric:last-child { border-right: 1px solid #e8ecf2; }
  .bi-metric-strip--many { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bi-metric-strip--many .bi-metric:nth-child(3n) { border-right: 1px solid #e8ecf2; }
  .bi-metric-strip--many .bi-metric:nth-child(n + 3) { border-top: 1px solid #e8ecf2; }
  .bi-metric-strip--four { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .bi-metric-strip--four .bi-metric:nth-child(2n) { border-right: 1px solid #e8ecf2; }
  .bi-metric-strip--four .bi-metric:nth-child(n + 3) { border-top: 1px solid #e8ecf2; }
}
</style>
