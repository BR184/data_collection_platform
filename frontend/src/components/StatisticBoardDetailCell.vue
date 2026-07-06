<script setup lang="ts">
import type { StatisticDetailColumn } from '../types/api';

interface DetailDisplayCell {
  label: string;
  href: string | null;
  tags: string[];
  labelColors: Record<string, string>;
}

defineProps<{
  column: StatisticDetailColumn;
  cell: DetailDisplayCell | undefined;
  multiline?: boolean;
  align?: 'left' | 'center' | 'right';
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

function shouldRenderAsTagList(column: StatisticDetailColumn, cell: DetailDisplayCell | undefined) {
  return isGitlabMultiLabelColumn(column) || (isGitlabLabelColumn(column) && (cell?.tags?.length ?? 0) > 1);
}

function gitlabLabelStyle(label: string, labelColors: Record<string, string> = {}) {
  const backgroundColor = gitlabLabelColor(label, labelColors);
  return {
    '--gitlab-label-bg': backgroundColor,
    '--gitlab-label-color': readableTextColor(backgroundColor),
    '--gitlab-label-border': darkenHexColor(backgroundColor, 0.16),
  };
}

function gitlabLabelColor(label: string, labelColors: Record<string, string>) {
  const realColor = labelColors[label];
  if (realColor) {
    return realColor;
  }
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
    class="detail-display-cell"
    :class="[
      `detail-display-cell--${align ?? 'center'}`,
      {
        'detail-display-cell--tag-list': shouldRenderAsTagList(column, cell),
        'detail-display-cell--single-tag': !shouldRenderAsTagList(column, cell) && isGitlabLabelColumn(column),
        'detail-display-cell--multiline': multiline,
      },
    ]"
  >
    <div
      v-if="shouldRenderAsTagList(column, cell)"
      class="detail-cell-tags detail-cell-gitlab-labels"
      :class="{ 'detail-cell-tags--multiline': multiline }"
      :title="cell?.label"
    >
      <span
        v-for="tag in cell?.tags ?? []"
        :key="`${column.key}-${tag}`"
        class="detail-gitlab-label"
        :style="gitlabLabelStyle(tag, cell?.labelColors)"
        :title="tag"
      >
        {{ tag }}
      </span>
      <span v-if="!(cell?.tags ?? []).length" class="detail-cell-empty">-</span>
    </div>
    <span
      v-else-if="isGitlabLabelColumn(column) && cell?.label && cell.label !== '-'"
      class="detail-gitlab-label"
      :style="gitlabLabelStyle(cell.label, cell.labelColors)"
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
  </div>
</template>

<style scoped>
.detail-display-cell {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.detail-display-cell--tag-list {
  align-items: center;
}

.detail-display-cell--single-tag {
  min-width: 0;
}

.detail-display-cell--multiline {
  align-items: center;
}

.detail-display-cell--left {
  justify-content: flex-start;
}

.detail-display-cell--right {
  justify-content: flex-end;
}

.detail-cell-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  padding: 0 3px;
  border-radius: 3px;
  color: inherit !important;
  font-weight: inherit;
  text-decoration: none !important;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-display-cell--left .detail-cell-link {
  justify-content: flex-start;
  text-align: left;
}

.detail-display-cell--right .detail-cell-link {
  justify-content: flex-end;
  text-align: right;
}

.detail-cell-link:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.detail-cell-text {
  display: block;
  width: 100%;
  max-width: 100%;
  color: #1f2329;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail-cell-text--multiline {
  white-space: normal;
  text-align: center;
  overflow-wrap: anywhere;
}

.detail-display-cell--left .detail-cell-text {
  text-align: left;
}

.detail-display-cell--right .detail-cell-text {
  text-align: right;
}

.detail-cell-empty {
  color: rgba(31, 35, 41, 0.42);
}

.detail-cell-tags {
  display: flex;
  align-items: center;
  align-content: center;
  justify-content: center;
  gap: 4px;
  flex-wrap: wrap;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 20px;
  overflow: visible;
}

.detail-cell-tags--multiline {
  flex-wrap: wrap;
  overflow: visible;
}

.detail-cell-gitlab-labels {
  justify-content: center;
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
  text-align: center;
}
</style>
