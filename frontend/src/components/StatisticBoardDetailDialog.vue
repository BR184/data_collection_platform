<script setup lang="ts">
import { computed } from 'vue';
import type {
  StatisticDetailCellValue,
  StatisticDetailColumn,
  StatisticDetailLinkValue,
  StatisticDetailResponse,
} from '../types/api';
// 统计板明细弹窗承接图表点击后的记录列表，保持和主看板一致的排序与分页语义。
// 弹窗只负责展示和导出，明细数据的筛选口径由父级传入的查询上下文决定。

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  detail: StatisticDetailResponse | null;
  pagination: {
    page: number;
    size: number;
  };
  detailTableClass?: string;
  detailCellValue: (record: Record<string, unknown>, column: StatisticDetailColumn) => StatisticDetailCellValue;
  onSortChange: (event: { column: unknown; prop: string; order: 'ascending' | 'descending' | null }) => void;
  onCurrentChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

interface DetailDisplayCell {
  label: string;
  href: string | null;
  tags: string[];
}

interface DetailDisplayRow {
  record: Record<string, unknown>;
  cells: Record<string, DetailDisplayCell>;
}

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

const detailRows = computed<DetailDisplayRow[]>(() =>
  (props.detail?.records ?? []).map((record) => ({
    record,
    cells: Object.fromEntries(
      (props.detail?.columns ?? []).map((column) => [column.key, createDetailCell(record, column)]),
    ),
  })),
);

function isStructuredCellValue(value: StatisticDetailCellValue): value is StatisticDetailLinkValue {
  return value != null && typeof value === 'object' && 'label' in value;
}

function createDetailCell(record: Record<string, unknown>, column: StatisticDetailColumn): DetailDisplayCell {
  const value = props.detailCellValue(record, column);
  const label = String(isStructuredCellValue(value) ? value.label || '-' : value ?? '-');
  const href = isStructuredCellValue(value) && value.href ? value.href : null;
  return {
    label,
    href,
    tags: splitTags(label),
  };
}

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

function splitTags(value: unknown) {
  const rawValue = String(value ?? '').trim();
  if (!rawValue || rawValue === '-') {
    return [];
  }
  return rawValue
    .split(/[、,，]/)
    .map((value) => value.trim())
    .filter(Boolean);
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
  <el-dialog
    :model-value="modelValue"
    :title="detail?.title || '明细数据'"
    class="stat-detail-dialog"
    width="72%"
    top="8vh"
    align-center
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="stat-detail-shell" v-loading="loading">
      <el-table
        v-if="detail"
        :data="detailRows"
        border
        stripe
        size="small"
        class="stat-detail-table"
        :class="detailTableClass"
        @sort-change="onSortChange"
      >
        <el-table-column
          v-for="column in detail.columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :width="column.width || undefined"
          :min-width="column.minWidth || 140"
          :sortable="column.sortable ? 'custom' : false"
          show-overflow-tooltip
        >
          <template #default="{ row }: { row: DetailDisplayRow }">
            <div
              v-if="isGitlabMultiLabelColumn(column)"
              class="detail-cell-tags detail-cell-gitlab-labels"
              :title="row.cells[column.key]?.label"
            >
              <span
                v-for="tag in row.cells[column.key]?.tags ?? []"
                :key="`${column.key}-${tag}`"
                class="detail-gitlab-label"
                :style="gitlabLabelStyle(tag)"
                :title="tag"
              >
                {{ tag }}
              </span>
              <span v-if="!(row.cells[column.key]?.tags ?? []).length" class="detail-cell-empty">-</span>
            </div>
            <span
              v-else-if="isGitlabLabelColumn(column) && row.cells[column.key]?.label && row.cells[column.key]?.label !== '-'"
              class="detail-gitlab-label"
              :style="gitlabLabelStyle(row.cells[column.key]?.label ?? '')"
              :title="row.cells[column.key]?.label"
            >
              {{ row.cells[column.key]?.label }}
            </span>
            <span v-else-if="isGitlabLabelColumn(column)" class="detail-cell-empty">-</span>
            <a
              v-else-if="row.cells[column.key]?.href"
              class="detail-cell-link"
              :href="row.cells[column.key]?.href || undefined"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ row.cells[column.key]?.label }}
            </a>
            <span v-else class="detail-cell-text">{{ row.cells[column.key]?.label }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="detail-pagination">
        <el-pagination
          v-if="detail"
          :current-page="pagination.page"
          :page-size="pagination.size"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="detail.total"
          @current-change="onCurrentChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </el-dialog>
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

.stat-detail-table :deep(.el-table__header th) {
  vertical-align: middle;
  padding: 6px 0;
}

.stat-detail-table :deep(.cell) {
  display: flex;
  align-items: center;
  min-height: 24px;
  width: 100%;
  line-height: 1.35 !important;
  overflow: hidden;
}

.stat-detail-table :deep(td.el-table__cell) {
  padding: 6px 0 !important;
  vertical-align: middle;
}

.stat-detail-table :deep(.el-table__row) {
  height: 36px;
}

.stat-detail-table :deep(.el-table__column-filter-trigger),
.stat-detail-table :deep(.caret-wrapper) {
  width: 28px;
}

.stat-detail-table :deep(.caret-wrapper) {
  height: 28px;
  justify-content: center;
}
</style>
