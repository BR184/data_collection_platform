<script setup lang="ts">
import type { PropType } from 'vue';

export interface BiQualityTargetItem {
  key: string;
  label: string;
  value: string;
  target: string;
  status: 'success' | 'danger' | 'neutral';
  statusLabel: string;
}

defineProps({
  items: { type: Array as PropType<BiQualityTargetItem[]>, required: true },
});
</script>

<template>
  <section class="bi-target-panel" aria-label="质量目标及达标情况">
    <header class="bi-target-panel__header">
      <h2>质量目标及达标情况</h2>
      <span>{{ items.filter((item) => item.status === 'success').length }} 项达标</span>
    </header>
    <div class="bi-target-panel__grid">
      <article v-for="item in items" :key="item.key" class="bi-target" :class="`is-${item.status}`">
        <div class="bi-target__label">{{ item.label }}</div>
        <strong class="bi-target__value">{{ item.value }}</strong>
        <div class="bi-target__meta">
          <span>{{ item.target }}</span>
          <b>{{ item.statusLabel }}</b>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.bi-target-panel {
  margin-bottom: 2px;
  overflow: hidden;
  border: 1px solid rgba(145, 204, 117, 0.28);
  border-radius: 6px;
  background: #fff;
}

.bi-target-panel__header {
  min-height: 44px;
  padding: 9px 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid rgba(145, 204, 117, 0.2);
}

.bi-target-panel__header h2 { margin: 0; color: #27364a; font-size: 15px; line-height: 22px; }
.bi-target-panel__header span { color: #667085; font-size: 12px; }
.bi-target-panel__grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.bi-target { min-width: 0; padding: 14px 18px 15px; }
.bi-target + .bi-target { border-left: 1px solid rgba(145, 204, 117, 0.2); }
.bi-target.is-success { background: rgba(145, 204, 117, 0.08); }
.bi-target.is-danger { background: rgba(238, 102, 102, 0.08); }
.bi-target__label { color: #475467; font-size: 13px; font-weight: 600; line-height: 20px; }
.bi-target__value { display: block; margin-top: 7px; color: #344054; font-size: 28px; line-height: 34px; font-variant-numeric: tabular-nums; }
.bi-target.is-success .bi-target__value { color: #47724f; }
.bi-target.is-danger .bi-target__value { color: #993e45; }
.bi-target__meta { margin-top: 7px; display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #667085; font-size: 12px; }
.bi-target__meta b { padding: 0 9px; border-radius: 999px; font-weight: 650; line-height: 22px; white-space: nowrap; }
.bi-target.is-success .bi-target__meta b { background: rgba(145, 204, 117, 0.18); color: #47724f; }
.bi-target.is-danger .bi-target__meta b { background: rgba(238, 102, 102, 0.16); color: #993e45; }
.bi-target.is-neutral .bi-target__meta b { background: rgba(154, 159, 176, 0.16); color: #5b6570; }

@media (max-width: 760px) {
  .bi-target-panel__grid { grid-template-columns: 1fr; }
  .bi-target + .bi-target { border-left: 0; border-top: 1px solid rgba(145, 204, 117, 0.2); }
}
</style>
