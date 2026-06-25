<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useFloatingHorizontalScrollbar } from '../composables/useFloatingHorizontalScrollbar';
import StatisticBoardDetailCell from './StatisticBoardDetailCell.vue';
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

const tableShellRef = ref<HTMLElement>();

interface DetailDisplayCell {
  label: string;
  href: string | null;
  tags: string[];
  labelColors: Record<string, string>;
}

interface DetailDisplayRow {
  record: Record<string, unknown>;
  cells: Record<string, DetailDisplayCell>;
}

const detailRows = computed<DetailDisplayRow[]>(() =>
  (props.detail?.records ?? []).map((record) => ({
    record,
    cells: Object.fromEntries(
      (props.detail?.columns ?? []).map((column) => [column.key, createDetailCell(record, column)]),
    ),
  })),
);

const mainTableColumns = computed(() => (props.detail?.columns ?? []).filter((column) => !column.expandOnly));

const expandColumns = computed(() => (props.detail?.columns ?? []).filter((column) => column.expandOnly));

const hasExpandColumns = computed(() => expandColumns.value.length > 0);

const {
  floatingScrollbarRef,
  scrollbarAwake,
  hasHorizontalOverflow,
  horizontalSpacerWidth,
  wakeHorizontalScrollbar,
  handleHorizontalWheel,
  handleFloatingHorizontalScroll,
  scheduleHorizontalScrollbarUpdate,
} = useFloatingHorizontalScrollbar({
  tableShellRef,
  watchedSources: [detailRows, mainTableColumns],
});

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void scheduleHorizontalScrollbarUpdate();
    }
  },
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
    labelColors: extractLabelColors(record),
  };
}

function extractLabelColors(record: Record<string, unknown>) {
  const raw = record._labelColors;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(raw as Record<string, unknown>)
      .filter((entry): entry is [string, string] => typeof entry[1] === 'string' && entry[1].trim().length > 0)
      .map(([label, color]) => [label, color.trim()]),
  );
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
      <div
        v-if="detail"
        ref="tableShellRef"
        class="stat-detail-table-shell"
        :class="{ 'is-scrollbar-awake': scrollbarAwake, 'has-horizontal-overflow': hasHorizontalOverflow }"
        tabindex="0"
        @mouseenter="wakeHorizontalScrollbar"
        @mousemove="wakeHorizontalScrollbar"
        @focusin="wakeHorizontalScrollbar"
        @wheel="handleHorizontalWheel"
      >
        <el-table
          :data="detailRows"
          border
          stripe
          size="small"
          class="stat-detail-table"
          :class="detailTableClass"
          @expand-change="scheduleHorizontalScrollbarUpdate"
          @sort-change="onSortChange"
        >
          <el-table-column v-if="hasExpandColumns" type="expand" width="42">
            <template #default="{ row }: { row: DetailDisplayRow }">
              <div class="stat-detail-expand-panel">
                <el-descriptions :column="2" border size="small" class="stat-detail-expand-descriptions">
                <el-descriptions-item
                  v-for="(column, index) in expandColumns"
                  :key="`expand-${column.key}-${index}`"
                    :label="column.label"
                    label-class-name="stat-detail-expand-label"
                    class-name="stat-detail-expand-content"
                  >
                    <StatisticBoardDetailCell :column="column" :cell="row.cells[column.key]" multiline />
                  </el-descriptions-item>
                </el-descriptions>
              </div>
            </template>
          </el-table-column>

          <el-table-column
            v-for="(column, index) in mainTableColumns"
            :key="`main-${column.key}-${index}`"
            :prop="column.key"
            :label="column.label"
            :width="column.width || undefined"
            :min-width="column.minWidth || 140"
            :sortable="column.sortable ? 'custom' : false"
            show-overflow-tooltip
          >
            <template #default="{ row }: { row: DetailDisplayRow }">
              <StatisticBoardDetailCell :column="column" :cell="row.cells[column.key]" />
            </template>
          </el-table-column>
        </el-table>
        <div
          v-show="hasHorizontalOverflow"
          ref="floatingScrollbarRef"
          class="stat-detail-floating-horizontal"
          aria-hidden="true"
          @mouseenter="wakeHorizontalScrollbar"
          @scroll="handleFloatingHorizontalScroll"
        >
          <div class="stat-detail-floating-horizontal-spacer" :style="{ width: `${horizontalSpacerWidth}px` }" />
        </div>
      </div>

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
.stat-detail-expand-panel {
  padding: 10px 16px 12px 42px;
  background: #fafcff;
}

.stat-detail-expand-descriptions {
  width: 100%;
}

.stat-detail-expand-descriptions :deep(.el-descriptions__label.stat-detail-expand-label) {
  width: 128px;
  color: #536274;
  font-weight: 600;
  background: #f4f7fb;
}

.stat-detail-expand-descriptions :deep(.el-descriptions__content.stat-detail-expand-content) {
  min-width: 180px;
  color: #1f2329;
}

.stat-detail-table-shell {
  position: relative;
  outline: none;
  scrollbar-gutter: stable;
}

.stat-detail-table :deep(.el-table__header th) {
  vertical-align: middle;
  padding: 6px 0;
}

.stat-detail-table :deep(td .cell) {
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

.stat-detail-table :deep(.el-table__expanded-cell .cell) {
  display: block;
  min-height: 0;
  overflow: visible;
}

.stat-detail-table :deep(.el-table__column-filter-trigger),
.stat-detail-table :deep(.caret-wrapper) {
  width: 28px;
}

.stat-detail-table :deep(.caret-wrapper) {
  height: 28px;
  justify-content: center;
}

.stat-detail-floating-horizontal {
  position: sticky;
  right: 14px;
  bottom: 2px;
  left: 0;
  z-index: 3;
  height: 12px;
  overflow-x: auto;
  overflow-y: hidden;
  pointer-events: none;
  opacity: 0;
  scrollbar-width: thin;
  scrollbar-color: rgb(148 163 184 / 72%) transparent;
  transition: opacity 0.14s ease;
}

.stat-detail-table-shell:hover .stat-detail-floating-horizontal,
.stat-detail-table-shell:focus-within .stat-detail-floating-horizontal,
.stat-detail-table-shell.is-scrollbar-awake .stat-detail-floating-horizontal {
  pointer-events: auto;
  opacity: 1;
}

.stat-detail-floating-horizontal::-webkit-scrollbar {
  height: 8px;
}

.stat-detail-floating-horizontal::-webkit-scrollbar-track {
  background: transparent;
}

.stat-detail-floating-horizontal::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgb(148 163 184 / 72%);
}

.stat-detail-floating-horizontal-spacer {
  height: 1px;
}
</style>
