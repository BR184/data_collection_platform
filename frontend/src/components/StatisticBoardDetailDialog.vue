<script setup lang="ts">
import { computed, ref } from 'vue';
import StatisticBoardDetailCell from './StatisticBoardDetailCell.vue';
import SmartTableHeader from './base/SmartTableHeader.vue';
import { tableHeaderMinimumWidth } from './base/table-header-layout';
import { useFloatingHorizontalScrollbar } from '../composables/useFloatingHorizontalScrollbar';
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

const tableContentWidth = computed(() => {
  const expandColumnWidth = hasExpandColumns.value ? 42 : 0;
  const dataColumnWidth = mainTableColumns.value.reduce((total, column) => {
    return total + (column.width ?? effectiveDetailColumnMinWidth(column));
  }, 0);
  return expandColumnWidth + dataColumnWidth + 2;
});

const tableShellStyle = computed(() => ({
  '--stat-detail-table-content-width': `${tableContentWidth.value}px`,
}));

const dialogStyle = computed(() => ({
  '--stat-detail-table-content-width': `${tableContentWidth.value}px`,
}));

const {
  floatingScrollbarRef,
  scrollbarAwake,
  hasHorizontalOverflow,
  isFloatingScrollbarVisible,
  horizontalSpacerWidth,
  floatingScrollbarStyle,
  wakeHorizontalScrollbar,
  handleHorizontalWheel,
  handleFloatingHorizontalScroll,
  scheduleHorizontalScrollbarUpdate,
} = useFloatingHorizontalScrollbar({
  tableShellRef,
  watchedSources: [
    detailRows,
    mainTableColumns,
    expandColumns,
    () => props.modelValue,
  ],
});

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

async function handleExpandChange() {
  await scheduleHorizontalScrollbarUpdate();
  wakeHorizontalScrollbar();
}

function effectiveDetailColumnMinWidth(column: StatisticDetailColumn) {
  return Math.max(column.minWidth ?? 0, tableHeaderMinimumWidth(column.label, column.sortable ? 24 : 8), 78);
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="detail?.title || '明细数据'"
    class="stat-detail-dialog"
    :style="dialogStyle"
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
        :style="tableShellStyle"
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
          :fit="true"
          class="stat-detail-table"
          :class="detailTableClass"
          @sort-change="onSortChange"
          @expand-change="handleExpandChange"
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
            :min-width="effectiveDetailColumnMinWidth(column)"
            :sortable="column.sortable ? 'custom' : false"
            show-overflow-tooltip
          >
            <template #header>
              <SmartTableHeader :label="column.label" />
            </template>
            <template #default="{ row }: { row: DetailDisplayRow }">
              <StatisticBoardDetailCell :column="column" :cell="row.cells[column.key]" />
            </template>
          </el-table-column>
        </el-table>
        <div
          v-show="isFloatingScrollbarVisible"
          ref="floatingScrollbarRef"
          class="stat-detail-floating-horizontal"
          :style="floatingScrollbarStyle"
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
:global(.stat-detail-dialog) {
  display: flex;
  flex-direction: column;
  width: min(1680px, calc(100vw - 40px));
  max-width: calc(100vw - 40px);
  max-height: calc(100vh - 48px);
  border-radius: 8px;
}

:global(.stat-detail-dialog .el-dialog__header) {
  padding: 16px 20px 12px;
  margin-right: 0;
  border-bottom: 1px solid #eef1f5;
}

:global(.stat-detail-dialog .el-dialog__title) {
  color: #1f2329;
  font-size: 15px;
  font-weight: 600;
}

:global(.stat-detail-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  padding: 12px 16px 14px;
  box-sizing: border-box;
}

.stat-detail-shell {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  min-height: 0;
  max-height: calc(100vh - 132px);
}

.stat-detail-expand-panel {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px 12px 42px;
  background: #fafcff;
}

.stat-detail-expand-descriptions {
  width: 100%;
  table-layout: fixed;
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
  width: 100%;
  min-width: 0;
  max-width: 100%;
  flex: 1 1 auto;
  max-height: min(70vh, calc(100vh - 206px));
  overflow: auto;
  outline: none;
  scrollbar-gutter: stable;
}

.stat-detail-table {
  min-width: 100%;
  width: max(100%, var(--stat-detail-table-content-width, 960px));
}

.stat-detail-table :deep(.el-table__inner-wrapper),
.stat-detail-table :deep(.el-table__header-wrapper),
.stat-detail-table :deep(.el-table__body-wrapper),
.stat-detail-table :deep(.el-table__header),
.stat-detail-table :deep(.el-table__body) {
  width: 100% !important;
}

.stat-detail-table-shell :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.stat-detail-floating-horizontal {
  position: fixed;
  z-index: 2200;
  height: 16px;
  padding: 3px 0 2px;
  overflow-x: auto;
  overflow-y: hidden;
  pointer-events: auto;
  opacity: 1;
  scrollbar-width: auto;
  border-radius: 8px;
  background: transparent;
  box-shadow: none;
}

.stat-detail-floating-horizontal::-webkit-scrollbar {
  height: 8px;
}

.stat-detail-floating-horizontal::-webkit-scrollbar-track {
  background: transparent;
}

.stat-detail-floating-horizontal::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(96, 98, 102, 0.55);
}

.stat-detail-floating-horizontal:hover::-webkit-scrollbar-thumb {
  background: rgba(96, 98, 102, 0.72);
}

.stat-detail-floating-horizontal-spacer {
  height: 1px;
}

.stat-detail-table :deep(td .cell) {
  display: flex;
  align-items: center;
  min-height: 24px;
  width: 100%;
  line-height: 1.35 !important;
  overflow: visible;
}

.stat-detail-table :deep(td.el-table__cell) {
  padding: 6px 0 !important;
  vertical-align: middle;
}

.stat-detail-table :deep(.el-table__row) {
  min-height: 36px;
}

.stat-detail-table :deep(.el-table__expanded-cell .cell) {
  display: block;
  min-height: 0;
  width: 100%;
  overflow: visible;
}

.stat-detail-table :deep(td.el-table__expanded-cell) {
  padding: 0 !important;
}

.detail-pagination {
  flex: 0 0 auto;
  display: flex;
  justify-content: flex-end;
  padding-top: 2px;
}

@media (max-width: 960px) {
  :global(.stat-detail-dialog) {
    width: calc(100vw - 16px);
    max-width: calc(100vw - 16px);
  }

  :global(.stat-detail-dialog .el-dialog__header) {
    padding: 14px 14px 10px;
  }

  :global(.stat-detail-dialog .el-dialog__body) {
    padding: 10px 10px 12px;
  }
}

</style>
