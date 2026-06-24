<script setup lang="ts">
import type { StatisticDetailColumn } from '../types/api';

interface DetailDisplayCell {
  label: string;
  href: string | null;
  tags: string[];
}

defineProps<{
  column: StatisticDetailColumn;
  cell: DetailDisplayCell | undefined;
  multiline?: boolean;
}>();

const LABEL_LIKE_SINGLE_COLUMN_KEYS = new Set([
  'bugStatus',
  'category',
  'delayCause',
  'delayType',
  'functionName',
  'illegalReason',
  'priorityLevel',
  'primaryPhaseLabel',
  'reasonCategory',
  'severityLabel',
  'severityLevel',
  'state',
  'systemTestLabel',
  'testingPhase',
]);

const LABEL_LIKE_MULTI_COLUMN_KEYS = new Set(['labels', 'moduleNames']);

const GITLAB_LABEL_PALETTE = [
  '#6699cc',
  '#5cb85c',
  '#f0ad4e',
  '#d9534f',
  '#8fbc8f',
  '#428bca',
  '#d66a6a',
  '#7b68ee',
  '#00a6a6',
  '#b37feb',
  '#6f42c1',
  '#db6d28',
  '#1f75cb',
  '#009966',
];

function isGitlabLabelColumn(column: StatisticDetailColumn) {
  return (
    column.type === 'tag' ||
    column.type === 'tags' ||
    LABEL_LIKE_SINGLE_COLUMN_KEYS.has(column.key) ||
    LABEL_LIKE_MULTI_COLUMN_KEYS.has(column.key)
  );
}

function isGitlabMultiLabelColumn(column: StatisticDetailColumn) {
  return column.type === 'tags' || LABEL_LIKE_MULTI_COLUMN_KEYS.has(column.key);
}

function gitlabLabelStyle(label: string) {
  const backgroundColor = gitlabLabelColor(label);
  return {
    '--gitlab-label-bg': backgroundColor,
    '--gitlab-label-color': readableTextColor(backgroundColor),
    '--gitlab-label-border': darkenHexColor(backgroundColor, 0.16),
  };
}

function gitlabLabelColor(label: string) {
  if (label.includes('一级') || label === 'P1') {
    return '#d9534f';
  }
  if (label.includes('二级') || label === 'P2' || label.includes('申请延期')) {
    return '#f0ad4e';
  }
  if (label.includes('三级') || label === 'P3') {
    return '#428bca';
  }
  if (label.includes('已修复') || label.includes('完成') || label === '已关闭') {
    return '#5cb85c';
  }
  if (label.includes('系统测试') || label.includes('回归测试')) {
    return '#6699cc';
  }
  const hash = Array.from(label).reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 0);
  return GITLAB_LABEL_PALETTE[hash % GITLAB_LABEL_PALETTE.length];
}

function readableTextColor(hexColor: string) {
  const { red, green, blue } = hexToRgb(hexColor);
  const luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255;
  return luminance > 0.62 ? '#1f2329' : '#ffffff';
}

function darkenHexColor(hexColor: string, amount: number) {
  const { red, green, blue } = hexToRgb(hexColor);
  return rgbToHex(red * (1 - amount), green * (1 - amount), blue * (1 - amount));
}

function hexToRgb(hexColor: string) {
  const normalized = hexColor.replace('#', '');
  const value = Number.parseInt(normalized, 16);
  return {
    red: (value >> 16) & 255,
    green: (value >> 8) & 255,
    blue: value & 255,
  };
}

function rgbToHex(red: number, green: number, blue: number) {
  return `#${[red, green, blue]
    .map((value) => Math.max(0, Math.min(255, Math.round(value))).toString(16).padStart(2, '0'))
    .join('')}`;
}
</script>

<template>
  <div
    v-if="isGitlabMultiLabelColumn(column)"
    class="detail-cell-tags detail-cell-gitlab-labels"
    :class="{ 'detail-cell-tags--multiline': multiline }"
    :title="cell?.label"
  >
    <span
      v-for="tag in cell?.tags ?? []"
      :key="`${column.key}-${tag}`"
      class="detail-gitlab-label"
      :style="gitlabLabelStyle(tag)"
      :title="tag"
    >
      {{ tag }}
    </span>
    <span v-if="!(cell?.tags ?? []).length" class="detail-cell-empty">-</span>
  </div>
  <span
    v-else-if="isGitlabLabelColumn(column) && cell?.label && cell.label !== '-'"
    class="detail-gitlab-label"
    :style="gitlabLabelStyle(cell.label)"
    :title="cell.label"
  >
    {{ cell.label }}
  </span>
  <span v-else-if="isGitlabLabelColumn(column)" class="detail-cell-empty">-</span>
  <a
    v-else-if="cell?.href"
    class="detail-cell-link"
    :href="cell.href || undefined"
    target="_blank"
    rel="noopener noreferrer"
  >
    {{ cell.label }}
  </a>
  <span v-else class="detail-cell-text" :class="{ 'detail-cell-text--multiline': multiline }" :title="cell?.label">
    {{ cell?.label }}
  </span>
</template>

<style scoped>
.detail-cell-link {
  color: #1677ff;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.detail-cell-link:hover {
  color: #4096ff;
}

.detail-cell-text {
  display: inline-block;
  max-width: 100%;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-cell-text--multiline {
  white-space: normal;
  overflow-wrap: anywhere;
}

.detail-cell-empty {
  color: rgba(31, 35, 41, 0.42);
}

.detail-cell-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: nowrap;
  max-width: 100%;
  min-height: 20px;
  overflow: hidden;
}

.detail-cell-tags--multiline {
  flex-wrap: wrap;
  overflow: visible;
}

.detail-cell-gitlab-labels {
  justify-content: flex-start;
}

.detail-gitlab-label {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  min-height: 18px;
  padding: 1px 6px;
  border: 1px solid var(--gitlab-label-border);
  border-radius: 4px;
  background: var(--gitlab-label-bg);
  color: var(--gitlab-label-color);
  font-size: 11px;
  font-weight: 600;
  line-height: 14px;
  white-space: nowrap;
  text-align: left;
}
</style>
