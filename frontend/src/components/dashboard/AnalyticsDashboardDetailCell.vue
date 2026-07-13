<script setup lang="ts">
import { computed } from 'vue';
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
    <a
      v-else-if="format === 'link' && link"
      class="analytics-detail-cell__link"
      :href="link.href"
      target="_blank"
      rel="noopener noreferrer"
    >
      {{ link.label }}
    </a>
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

.analytics-detail-cell__text,
.analytics-detail-cell__link {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analytics-detail-cell__link {
  display: inline-block;
  max-width: 100%;
  color: var(--el-color-primary);
  text-decoration: none;
}

.analytics-detail-cell__link:hover {
  text-decoration: underline;
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
