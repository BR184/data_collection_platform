<script setup lang="ts">
import { computed } from 'vue';
import { TopRight } from '@element-plus/icons-vue';
import type { AnalyticsDashboardDetailColumn } from '../../types/api';
import {
  analyticsDetailTags,
  formatAnalyticsDetailValue,
  normalizeAnalyticsDetailFormat,
  safeAnalyticsDetailLink,
} from './analytics-dashboard-detail-cell';

const props = defineProps<{
  column: AnalyticsDashboardDetailColumn;
  value: unknown;
}>();

const format = computed(() => normalizeAnalyticsDetailFormat(props.column));
const displayValue = computed(() => formatAnalyticsDetailValue(props.value, props.column));
const tags = computed(() => analyticsDetailTags(props.value));
const link = computed(() => safeAnalyticsDetailLink(props.value));
</script>

<template>
  <div class="analytics-detail-cell">
    <div v-if="format === 'tag' || format === 'tags'" class="analytics-detail-cell__tags">
      <el-tag v-for="tag in tags" :key="tag" size="small" effect="light">{{ tag }}</el-tag>
      <span v-if="!tags.length" class="analytics-detail-cell__empty">-</span>
    </div>
    <div
      v-else-if="format === 'link' && link"
      class="analytics-detail-cell__link-cell"
    >
      <span class="analytics-detail-cell__text" :title="link.label">{{ link.label }}</span>
      <a
        class="analytics-detail-cell__link-action"
        :href="link.href"
        target="_blank"
        rel="noopener noreferrer"
        :aria-label="`打开${link.label}`"
        :title="`打开${link.label}`"
        @click.stop
      >
        <el-icon><TopRight /></el-icon>
      </a>
    </div>
    <span v-else class="analytics-detail-cell__text" :title="displayValue">{{ displayValue }}</span>
  </div>
</template>

<style scoped>
.analytics-detail-cell,
.analytics-detail-cell__text {
  display: block;
  min-width: 0;
  max-width: 100%;
}

.analytics-detail-cell__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-detail-cell__link-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  gap: 6px;
}

.analytics-detail-cell__link-action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  padding: 2px 5px;
  color: #475569;
  font-size: 13px;
  line-height: 1;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  text-decoration: none;
}

.analytics-detail-cell__link-action:hover {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-3);
  background: var(--el-color-primary-light-9);
}

.analytics-detail-cell__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.analytics-detail-cell__empty {
  color: #98a2b3;
}
</style>
