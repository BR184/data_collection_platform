<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { EditPen, Plus, WarningFilled } from '@element-plus/icons-vue';
// 问题项面板负责展示专家问题列表和新增入口。
// 它只接收父级传入的问题数据，实际加载与保存流程交给 review-data composable。
import SmartTableHeader from '../../components/base/SmartTableHeader.vue';
import { shouldShowRecordTableOverflowTooltip } from '../../components/base/base-record-table-cell';
import { tableHeaderMinimumWidth } from '../../components/base/table-header-layout';
import { useFloatingHorizontalScrollbar } from '../../composables/useFloatingHorizontalScrollbar';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import type { RecordTableColumn } from '../../types/record-table';

const props = defineProps<{
  record: ReviewDataRecordRowResponse;
  loading: boolean;
  rows: Record<string, unknown>[];
  columns: RecordTableColumn[];
  onCreateProblemItem: (recordId: number) => void | Promise<void>;
  onEditProblemItem: (recordId: number, item: ReviewDataProblemItemResponse) => void | Promise<void>;
  onDeleteProblemItem: (recordId: number, itemId: number) => void | Promise<void>;
  canManage?: boolean;
  canCreate?: boolean;
  canEdit?: boolean;
  canDelete?: boolean | ((row: Record<string, unknown>) => boolean);
}>();

const canCreateProblem = computed(() => props.canCreate ?? props.canManage ?? false);
const canEditProblem = computed(() => props.canEdit ?? props.canManage ?? false);
const canDeleteProblem = computed(() => props.canDelete ?? props.canManage ?? false);
const showActions = computed(() => canEditProblem.value || canDeleteProblem.value);

function canDeleteProblemRow(row: Record<string, unknown>) {
  if (typeof props.canDelete === 'function') {
    return props.canDelete(row);
  }
  return canDeleteProblem.value;
}

const ACTION_COLUMN_WIDTH = 136;
const flexibleTextColumnWeights: Record<string, number> = {
  problemDescription: 2,
  suggestedSolution: 2,
  rejectionReason: 1,
};
const tableShellRef = ref<HTMLElement>();
const availableTableWidth = ref(0);
let resizeObserver: ResizeObserver | undefined;

const {
  floatingScrollbarRef,
  floatingTrackRef,
  scrollbarAwake,
  hasHorizontalOverflow,
  isFloatingScrollbarVisible,
  floatingScrollbarStyle,
  floatingThumbStyle,
  wakeHorizontalScrollbar,
  handleHorizontalWheel,
  handleFloatingTrackPointerDown,
  handleFloatingThumbPointerDown,
  handleFloatingScrollbarPointerUp,
} = useFloatingHorizontalScrollbar({
  tableShellRef,
  watchedSources: [
    () => props.rows,
    () => props.columns,
    () => showActions.value,
    availableTableWidth,
  ],
});

function isPendingReview(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataProblemItemResponse | undefined;
  return String(raw?.problemStatus ?? '').trim() === '未评审';
}

function effectiveColumnMinWidth(column: RecordTableColumn) {
  const reservePx = column.sortable ? 20 : 8;
  const minimumFloor = column.type === 'number' ? 56 : column.type === 'tag' ? 72 : 78;
  return Math.max(column.minWidth ?? 0, tableHeaderMinimumWidth(column.label, reservePx, column.headerLines), minimumFloor);
}

function effectiveColumnWidth(column: RecordTableColumn) {
  return Math.max(column.width ?? 0, effectiveColumnMinWidth(column));
}

const baseTableContentWidth = computed(() =>
  props.columns.reduce(
    (total, column) => total + effectiveColumnWidth(column),
    showActions.value ? ACTION_COLUMN_WIDTH : 0,
  ) + 2,
);

const flexibleTextWeightTotal = computed(() =>
  props.columns.reduce((total, column) => total + (flexibleTextColumnWeights[column.key] ?? 0), 0),
);

const columnRenderWidths = computed<Record<string, number>>(() => {
  const extraWidth = Math.max(0, availableTableWidth.value - baseTableContentWidth.value);
  const totalWeight = flexibleTextWeightTotal.value;
  return Object.fromEntries(
    props.columns.map((column) => {
      const baseWidth = effectiveColumnWidth(column);
      const weight = flexibleTextColumnWeights[column.key] ?? 0;
      const addedWidth = extraWidth > 0 && totalWeight > 0 && weight > 0
        ? Math.floor((extraWidth * weight) / totalWeight)
        : 0;
      return [column.key, baseWidth + addedWidth];
    }),
  );
});

const tableContentWidth = computed(() =>
  Math.max(
    baseTableContentWidth.value,
    props.columns.reduce(
      (total, column) => total + (
        columnRenderWidths.value[column.key]
        ?? effectiveColumnMinWidth(column)
      ),
      showActions.value ? ACTION_COLUMN_WIDTH : 0,
    ) + 2,
  ),
);

const problemTableStyle = computed(() => ({
  width: `${tableContentWidth.value}px`,
  minWidth: '100%',
}));

function problemColumnClassName(column: RecordTableColumn) {
  return isLongTextColumn(column) ? 'problem-subtable-cell--left' : '';
}

function columnBodyAlign(column: RecordTableColumn) {
  if (isLongTextColumn(column)) {
    return 'left';
  }
  return column.align ?? 'center';
}

function isLongTextColumn(column: RecordTableColumn) {
  return ['documentPosition', 'problemDescription', 'suggestedSolution', 'rejectionReason'].includes(column.key);
}

function updateAvailableTableWidth() {
  availableTableWidth.value = Math.floor(tableShellRef.value?.clientWidth ?? 0);
}

onMounted(() => {
  resizeObserver = new ResizeObserver(updateAvailableTableWidth);
  if (tableShellRef.value) {
    resizeObserver.observe(tableShellRef.value);
  }
  void nextTick(updateAvailableTableWidth);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
});

watch(
  () => [props.columns, showActions.value],
  () => void nextTick(updateAvailableTableWidth),
  { deep: true },
);
</script>

<template>
  <div class="problem-panel">
    <div class="problem-panel-head">
      <div class="problem-panel-title">
        <span>评审问题清单</span>
        <el-tag size="small" effect="plain">已录入 {{ rows.length }} 条</el-tag>
      </div>
      <el-button v-if="canCreateProblem" type="primary" text :icon="Plus" @click="onCreateProblemItem(record.id)">新增问题</el-button>
    </div>

    <div
      ref="tableShellRef"
      class="problem-subtable-frame"
      :class="{ 'is-scrollbar-awake': scrollbarAwake, 'has-horizontal-overflow': hasHorizontalOverflow }"
      tabindex="0"
      @mouseenter="wakeHorizontalScrollbar"
      @mousemove="wakeHorizontalScrollbar"
      @focusin="wakeHorizontalScrollbar"
      @wheel="handleHorizontalWheel"
    >
      <el-table
        v-loading="loading"
        :data="rows"
        class="problem-subtable"
        border
        stripe
        empty-text="当前评审下还没有录入问题清单。"
        :fit="false"
        row-key="id"
        :style="problemTableStyle"
      >
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :prop="column.key"
        :label="column.label"
        :width="columnRenderWidths[column.key]"
        :min-width="effectiveColumnMinWidth(column)"
        :fixed="column.fixed"
        :align="columnBodyAlign(column)"
        header-align="center"
        :class-name="problemColumnClassName(column)"
        :show-overflow-tooltip="shouldShowRecordTableOverflowTooltip(column)"
      >
        <template #header>
          <SmartTableHeader :label="column.label" :lines="column.headerLines" align="center" prefer-stacked />
        </template>
        <template #default="{ row }">
          <template v-if="column.type === 'tag'">
            <el-tag
              v-for="tag in row[column.key] as Array<{ label: string; type?: 'success' | 'warning' | 'info' | 'danger' | 'primary' }>"
              :key="tag.label"
              size="small"
              :type="tag.type ?? 'info'"
              effect="plain"
            >
              {{ tag.label }}
            </el-tag>
          </template>
          <span v-else-if="column.key === 'reviewerName'" class="problem-reviewer-cell">
            <el-icon v-if="isPendingReview(row)" class="problem-reviewer-warning">
              <WarningFilled />
            </el-icon>
            <span :class="{ 'problem-reviewer-name--offset': isPendingReview(row) }">{{ row[column.key] }}</span>
          </span>
          <span v-else class="problem-cell-text">{{ row[column.key] }}</span>
        </template>
      </el-table-column>

      <el-table-column v-if="showActions" label="操作" :width="ACTION_COLUMN_WIDTH" fixed="right" align="center" header-align="center">
        <template #default="{ row }">
          <div class="problem-actions">
            <el-button
              v-if="canEditProblem"
              class="problem-action-edit"
              type="primary"
              plain
              size="small"
              :icon="EditPen"
              @click="onEditProblemItem(record.id, row.__raw as ReviewDataProblemItemResponse)"
            >
              编辑
            </el-button>
            <el-button
              v-if="canDeleteProblemRow(row)"
              class="problem-action-delete"
              type="danger"
              text
              size="small"
              @click="onDeleteProblemItem(record.id, (row.__raw as ReviewDataProblemItemResponse).id)"
            >
              删除
            </el-button>
          </div>
        </template>
      </el-table-column>
      </el-table>
      <Teleport to="body">
        <div
          v-show="isFloatingScrollbarVisible"
          ref="floatingScrollbarRef"
          class="review-problem-floating-horizontal"
          :style="floatingScrollbarStyle"
          aria-hidden="true"
          @mouseenter="wakeHorizontalScrollbar"
          @pointerup="handleFloatingScrollbarPointerUp"
        >
          <div
            ref="floatingTrackRef"
            class="platform-floating-horizontal-track"
            @pointerdown="handleFloatingTrackPointerDown"
          >
            <div
              class="platform-floating-horizontal-thumb"
              :style="floatingThumbStyle"
              @pointerdown="handleFloatingThumbPointerDown"
            />
          </div>
        </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.problem-panel {
  display: grid;
  gap: 12px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding: 12px 16px 14px;
  background: #f8fafc;
  overflow: hidden;
}

.problem-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  flex-wrap: wrap;
}

.problem-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.82);
}

.problem-subtable-frame {
  position: relative;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: hidden;
  overflow-y: hidden;
  outline: none;
  border: 0;
  border-radius: 6px;
  background: transparent;
  box-shadow: none;
}

.problem-subtable {
  border: 1px solid #d7dee9 !important;
  border-radius: 6px;
  overflow: hidden;
  background: var(--platform-table-header-bg, #f8fafc);
}

.problem-subtable :deep(.el-table__inner-wrapper),
.problem-subtable :deep(.el-table__header-wrapper),
.problem-subtable :deep(.el-table__body-wrapper),
.problem-subtable :deep(.el-table__header),
.problem-subtable :deep(.el-table__body) {
  min-width: 0;
}

:deep(.problem-subtable .cell) {
  min-width: 0;
}

:deep(.problem-subtable .el-table__body .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding: 0 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.problem-cell-text {
  display: block;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.problem-subtable .el-tag),
:deep(.problem-subtable .el-tag__content) {
  max-width: 100%;
  white-space: nowrap;
}

:deep(.problem-subtable td.problem-subtable-cell--left .cell) {
  justify-content: flex-start;
  text-align: left !important;
}

:deep(.problem-subtable td.problem-subtable-cell--left .cell > .el-tooltip) {
  justify-content: flex-start;
  text-align: left !important;
}

.problem-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
}

.problem-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.problem-action-edit {
  height: 26px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.2);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.problem-action-edit:hover,
.problem-action-edit:focus {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}

.problem-action-delete {
  height: 26px;
  padding: 0 6px;
  color: rgba(220, 38, 38, 0.72);
  font-size: 12px;
  font-weight: 500;
}

.problem-action-delete:hover,
.problem-action-delete:focus {
  background: rgba(220, 38, 38, 0.08);
  color: #dc2626;
}

:deep(.problem-subtable .el-table__header th.el-table__cell) {
  background: #f6f8fb;
}

.problem-subtable-frame :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.review-problem-floating-horizontal {
  position: fixed;
  z-index: 2600;
  height: 16px;
  padding: 5px 0;
  overflow: visible;
  pointer-events: auto;
  opacity: 1;
  background: transparent;
  box-shadow: none;
}

.problem-reviewer-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  width: 100%;
  overflow: hidden;
  white-space: nowrap;
}

.problem-reviewer-cell > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.problem-reviewer-warning {
  flex: 0 0 auto;
  color: #e6a23c;
  font-size: 16px;
}

.problem-reviewer-name--offset {
  margin-left: 10px;
}

</style>
