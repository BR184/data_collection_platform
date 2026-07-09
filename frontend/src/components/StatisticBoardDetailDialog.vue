<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Close } from '@element-plus/icons-vue';
import StatisticBoardDetailCell from './StatisticBoardDetailCell.vue';
import RecordTableFilterFields from './base/RecordTableFilterFields.vue';
import SmartTableHeader from './base/SmartTableHeader.vue';
import { tableHeaderMinimumWidth } from './base/table-header-layout';
import { useFloatingHorizontalScrollbar } from '../composables/useFloatingHorizontalScrollbar';
import type {
  StatisticDetailCellValue,
  StatisticDetailColumn,
  StatisticDetailLinkValue,
  StatisticDetailResponse,
} from '../types/api';
import type { RecordTableFilterField } from '../types/record-table';
// 统计板明细弹窗承接图表点击后的记录列表，保持和主看板一致的排序、分页和下钻快速筛选语义。
// 业务筛选口径由父级查询上下文决定，弹窗内快速筛选只进一步缩小当前下钻结果集。

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  detail: StatisticDetailResponse | null;
  pagination: {
    page: number;
    size: number;
    sortField?: string;
    sortOrder?: string;
  };
  quickFilterValues: Record<string, string>;
  quickFilterInputDrafts: Record<string, string>;
  detailTableClass?: string;
  detailCellValue: (record: Record<string, unknown>, column: StatisticDetailColumn) => StatisticDetailCellValue;
  onSortChange: (event: { column: unknown; prop: string; order: 'ascending' | 'descending' | null }) => void;
  onCurrentChange: (page: number) => void;
  onSizeChange: (size: number) => void;
  onQuickFilterInputUpdate: (key: string, value: string) => void;
  onQuickFilterChange: (key: string, value: string | string[] | null) => void;
  onResetQuickFilters: () => void;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

const tableShellRef = ref<HTMLElement>();
const quickFiltersExpanded = ref(false);

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
const detailQuickFilterFields = computed<RecordTableFilterField[]>(() => {
  const fields: RecordTableFilterField[] = [
    {
      key: 'detailKeyword',
      label: '任意关键字',
      type: 'input',
      placeholder: '输入任意关键字搜索',
      width: 280,
      clearable: true,
    },
  ];
  const seen = new Set<string>();
  for (const column of props.detail?.columns ?? []) {
    if (seen.has(column.key)) {
      continue;
    }
    seen.add(column.key);
    if (isDetailSelectFilterColumn(column)) {
      const options = detailColumnFilterOptions(column);
      if (!options.length) {
        continue;
      }
      fields.push({
        key: `detailFilter.${column.key}`,
        label: column.label,
        type: 'select',
        placeholder: column.label,
        width: detailFilterFieldWidth(column, options),
        clearable: true,
        options,
        selectMode: 'compact',
      });
      continue;
    }
    if (isDetailInputFilterColumn(column)) {
      fields.push({
        key: `detailFilter.${column.key}`,
        label: column.label,
        type: 'input',
        placeholder: detailInputPlaceholder(column),
        width: detailInputFilterFieldWidth(column),
        clearable: true,
      });
    }
  }
  return fields;
});
const quickFilterCount = computed(() => detailQuickFilterFields.value.length);
const quickFilterToggleText = computed(() =>
  quickFiltersExpanded.value ? '收起快速筛选' : `快速筛选（${quickFilterCount.value}）`,
);
const quickFilterToggleIcon = computed(() => (quickFiltersExpanded.value ? ArrowUp : ArrowDown));
const currentSortSummary = computed(() => {
  const fieldKey = String(props.pagination.sortField ?? '').trim();
  const direction = String(props.pagination.sortOrder ?? '').trim();
  if (!fieldKey || !direction) {
    return '';
  }
  const fieldLabel = (props.detail?.columns ?? []).find((column) => column.key === fieldKey)?.label
    ?? readableDetailSortFieldLabel(fieldKey);
  return `${fieldLabel} / ${readableDetailSortDirection(direction)}`;
});

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

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      quickFiltersExpanded.value = false;
    }
  },
);

function detailColumnFilterOptions(column: StatisticDetailColumn) {
  const values = new Set<string>();
  for (const value of props.detail?.quickFilterOptions?.[column.key] ?? []) {
    addDetailFilterOptionValues(values, value);
  }
  for (const row of detailRows.value) {
    addDetailFilterOptionValues(values, row.cells[column.key]?.label);
  }
  return Array.from(values)
    .sort((left, right) => left.localeCompare(right, 'zh-Hans-CN'))
    .slice(0, 200)
    .map((value) => ({ label: value, value }));
}

function addDetailFilterOptionValues(target: Set<string>, value: unknown) {
  const raw = String(value ?? '').trim();
  if (!raw || raw === '-') {
    return;
  }
  for (const part of splitTags(raw)) {
    if (part && part !== '-') {
      target.add(part);
    }
  }
}

function isDetailInputFilterColumn(column: StatisticDetailColumn) {
  const normalizedKey = column.key.replace(/[-_]/g, '').toLowerCase();
  return normalizedKey === 'iid'
    || normalizedKey === 'issueiid'
    || normalizedKey === 'mriid'
    || normalizedKey === 'mergerequestiid'
    || /编号|标题/.test(column.label)
    || /title$/i.test(column.key);
}

function detailInputPlaceholder(column: StatisticDetailColumn) {
  if (/编号/.test(column.label)) {
    return `输入${column.label}`;
  }
  if (/标题/.test(column.label) || /(title|name)$/i.test(column.key)) {
    return `输入${column.label}`;
  }
  return column.label;
}

function detailInputFilterFieldWidth(column: StatisticDetailColumn) {
  if (/标题/.test(column.label) || /(title|name)$/i.test(column.key)) {
    return 240;
  }
  return 150;
}

function isDetailSelectFilterColumn(column: StatisticDetailColumn) {
  if (isPersonDetailFilterColumn(column)) {
    return true;
  }
  if (column.type === 'tag' || column.type === 'tags') {
    return true;
  }
  const normalizedKey = column.key.replace(/[-_]/g, '').toLowerCase();
  if ([
    'modulenames',
    'projectname',
    'severitylevel',
    'prioritylevel',
    'bugstatus',
    'category',
    'delaycause',
    'reasoncategory',
    'illegalreason',
    'state',
    'issuestate',
    'testingphase',
    'milestonetitle',
    'functionname',
    'authorname',
    'assigneename',
    'ownername',
    'mergedby',
    'targetbranch',
  ].includes(normalizedKey)) {
    return true;
  }
  return /模块|项目|状态|严重程度|优先级|测试阶段|里程碑|功能|原因|类型|创建人|提交人|处理人|负责人|合并人|目标分支/.test(column.label);
}

function isPersonDetailFilterColumn(column: StatisticDetailColumn) {
  const normalizedKey = column.key.replace(/[-_]/g, '').toLowerCase();
  return [
    'authorname',
    'assigneename',
    'ownername',
    'reviewowner',
    'reviewername',
    'mergedby',
    'createdby',
    'updatedby',
    'charger',
  ].includes(normalizedKey)
    || /创建人|提交人|处理人|负责人|责任人|合并人|走查人|被走查人|评审人|评审专家|作者|审核人|指派人|用户/.test(column.label);
}

function detailFilterFieldWidth(column: StatisticDetailColumn, options: Array<{ label: string; value: string }> = []) {
  const longest = options.reduce((max, option) => Math.max(max, visualTextLength(option.label)), column.label.length);
  const estimated = Math.round(longest * 12 + 54);
  if (/标题|内容|描述|说明|方案|备注|标签/.test(column.label)) {
    return Math.max(180, Math.min(260, estimated));
  }
  if (/时间|日期/.test(column.label)) {
    return Math.max(170, Math.min(220, estimated));
  }
  return Math.max(132, Math.min(220, estimated));
}

function visualTextLength(value: string) {
  return Array.from(value).reduce((total, char) => total + (char.charCodeAt(0) > 255 ? 1 : 0.56), 0);
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

function isDetailTagColumn(column: StatisticDetailColumn) {
  return column.type === 'tag' || column.type === 'tags' || ['labels', 'moduleNames'].includes(column.key);
}

function detailBodyAlign(column: StatisticDetailColumn) {
  return isDetailLeftAlignedTextColumn(column) ? 'left' : 'center';
}

function detailColumnClassName(column: StatisticDetailColumn) {
  return isDetailLeftAlignedTextColumn(column) ? 'stat-detail-cell--left' : undefined;
}

function isDetailLeftAlignedTextColumn(column: StatisticDetailColumn) {
  if (isDetailCenteredShortColumn(column)) {
    return false;
  }
  return /标题|合并请求内容|内容|描述|说明|方案|备注|详情|消息/.test(column.label)
    || /(title|content|description|solution|remark|note|message|summary|detail)$/i.test(column.key)
    || /mergeRequestContent/i.test(column.key);
}

function isDetailCenteredShortColumn(column: StatisticDetailColumn) {
  if (column.type === 'number' || column.type === 'link' || column.type === 'tag' || column.type === 'tags') {
    return true;
  }
  const normalizedKey = column.key.replace(/[-_]/g, '').toLowerCase();
  return /编号|状态|严重程度|优先级|时间|日期|模块|功能/.test(column.label)
    || ['id', 'iid', 'issueid', 'issueiid', 'issuenumber', 'mrid', 'mriid'].includes(normalizedKey)
    || /(status|state|level|priority|severity|phase|time|date)$/.test(normalizedKey)
    || /At$/.test(column.key);
}

function readableDetailSortFieldLabel(fieldKey: string) {
  const fallbackLabels: Record<string, string> = {
    syncedAt: '同步时间',
    updatedAt: '更新时间',
    createdAt: '创建时间',
    submittedAt: '提交时间',
    mergedAt: '合并时间',
    iid: '编号',
    issueIid: '议题编号',
    title: '标题',
    moduleNames: '模块名',
    severity: '严重程度',
    status: '状态',
  };
  return fallbackLabels[fieldKey] ?? '当前字段';
}

function readableDetailSortDirection(direction: string) {
  if (direction === 'asc' || direction === 'ascending') {
    return '升序';
  }
  if (direction === 'desc' || direction === 'descending') {
    return '降序';
  }
  return '默认顺序';
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    class="stat-detail-dialog"
    :style="dialogStyle"
    top="4vh"
    align-center
    :show-close="false"
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <template #header="{ close, titleId, titleClass }">
      <div class="stat-detail-header">
        <h2 :id="titleId" :class="['stat-detail-title', titleClass]">
          {{ detail?.title || '明细数据' }}
        </h2>
        <div v-if="detail" class="stat-detail-header-actions">
          <el-button
            class="app-action-button app-action-button--filter stat-detail-filter-toggle"
            plain
            :icon="quickFilterToggleIcon"
            @click="quickFiltersExpanded = !quickFiltersExpanded"
          >
            {{ quickFilterToggleText }}
          </el-button>
          <div v-if="currentSortSummary" class="stat-detail-sortbar">
            <span class="stat-detail-sortbar-label">当前排序</span>
            <el-tag effect="plain" type="info" size="small">{{ currentSortSummary }}</el-tag>
          </div>
        </div>
        <el-button
          class="stat-detail-close"
          text
          :icon="Close"
          aria-label="关闭明细弹窗"
          @click="close"
        />
      </div>
    </template>
    <div class="stat-detail-shell" :class="{ 'is-loading-empty': loading && !detail }" v-loading="loading">
      <el-collapse-transition>
        <div v-show="detail && quickFiltersExpanded" class="stat-detail-filterbar">
          <div class="stat-detail-quick-fields">
            <RecordTableFilterFields
              :filters="detailQuickFilterFields"
              :filter-values="quickFilterValues"
              :input-drafts="quickFilterInputDrafts"
              keyword-field-visible
              :default-input-width="150"
              :default-select-width="150"
              @input-update="onQuickFilterInputUpdate"
              @input-change="onQuickFilterChange"
              @input-search="(key) => onQuickFilterChange(key, quickFilterInputDrafts[key] ?? quickFilterValues[key] ?? '')"
              @input-clear="(key) => onQuickFilterChange(key, '')"
              @filter-change="onQuickFilterChange"
            />
          </div>
          <div class="stat-detail-filter-actions">
            <el-button class="app-action-button app-action-button--reset" @click="onResetQuickFilters">
              重置
            </el-button>
          </div>
        </div>
      </el-collapse-transition>
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
          :fit="false"
          class="stat-detail-table"
          :class="detailTableClass"
          @sort-change="onSortChange"
          @expand-change="handleExpandChange"
        >
          <el-table-column v-if="hasExpandColumns" type="expand" width="42" align="center" header-align="center">
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
                    <StatisticBoardDetailCell
                      :column="column"
                      :cell="row.cells[column.key]"
                      :align="detailBodyAlign(column)"
                      multiline
                    />
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
            :align="detailBodyAlign(column)"
            header-align="center"
            :class-name="detailColumnClassName(column)"
            :show-overflow-tooltip="!isDetailTagColumn(column)"
          >
            <template #header>
              <SmartTableHeader :label="column.label" />
            </template>
            <template #default="{ row }: { row: DetailDisplayRow }">
              <StatisticBoardDetailCell
                :column="column"
                :cell="row.cells[column.key]"
                :align="detailBodyAlign(column)"
              />
            </template>
          </el-table-column>
        </el-table>
        <Teleport to="body">
          <div
            v-show="isFloatingScrollbarVisible"
            ref="floatingScrollbarRef"
            class="stat-detail-floating-horizontal"
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
:global(.stat-detail-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  width: min(1728px, calc(100vw - 24px));
  max-width: calc(100vw - 24px);
  max-height: calc(100vh - 18px);
  padding: 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px !important;
  box-shadow: var(--el-box-shadow-dark) !important;
  box-sizing: border-box;
}

:global(.stat-detail-dialog .el-dialog__header) {
  padding: 0 10px 8px !important;
  margin-right: 0;
  border-bottom: 0 !important;
  background: transparent !important;
}

.stat-detail-header {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.stat-detail-title {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 30px;
  font-weight: 800;
  line-height: 40px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.stat-detail-title.el-dialog__title) {
  color: var(--el-text-color-primary) !important;
  font-size: 30px !important;
  font-weight: 800 !important;
  line-height: 40px !important;
}

.stat-detail-header-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-width: 0;
}

.stat-detail-filter-toggle {
  flex: 0 0 auto;
  min-height: 34px;
  padding: 0 14px;
  font-size: 14px;
  font-weight: 600;
}

.stat-detail-close {
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--el-text-color-secondary);
}

:global(.stat-detail-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  padding: 0 !important;
  box-sizing: border-box;
}

.stat-detail-shell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  min-height: 0;
  max-height: calc(100vh - 70px);
}

.stat-detail-shell.is-loading-empty {
  min-height: 112px;
}

.stat-detail-shell :deep(.el-loading-mask) {
  border-radius: 18px;
  overflow: visible;
}

.stat-detail-shell :deep(.el-loading-spinner) {
  margin-top: 0;
  transform: translateY(-50%);
}

.stat-detail-sortbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}

.stat-detail-filterbar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
  box-sizing: border-box;
  overflow: hidden;
}

.stat-detail-quick-fields {
  display: flex;
  align-items: center;
  align-content: flex-start;
  flex: 1 1 auto;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  max-width: 100%;
}

.stat-detail-quick-fields :deep(.record-filter-control) {
  max-width: min(100%, 280px);
}

.stat-detail-quick-fields :deep(.record-filter-main-keyword) {
  max-width: min(100%, 320px);
}

.stat-detail-quick-fields :deep(.record-filter-select) {
  min-width: 128px;
}

.stat-detail-filter-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  flex-wrap: wrap;
}

.stat-detail-filter-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.stat-detail-sortbar-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .stat-detail-filterbar {
    flex-direction: column;
  }

  .stat-detail-filter-actions {
    width: 100%;
  }
}

.stat-detail-expand-panel {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px 10px 42px;
  background: var(--el-fill-color-extra-light);
}

.stat-detail-expand-descriptions {
  width: 100%;
  table-layout: fixed;
}

.stat-detail-expand-descriptions :deep(.el-descriptions__label.stat-detail-expand-label) {
  width: 128px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
  background: var(--el-fill-color-light);
  text-align: center;
  vertical-align: middle;
}

.stat-detail-expand-descriptions :deep(.el-descriptions__content.stat-detail-expand-content) {
  min-width: 180px;
  color: var(--el-text-color-primary);
  text-align: center;
  vertical-align: middle;
}

.stat-detail-table-shell {
  position: relative;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  flex: 1 1 auto;
  max-height: min(78vh, calc(100vh - 122px));
  overflow-x: hidden;
  overflow-y: auto;
  outline: none;
  --platform-contained-table-radius: 2px;
  --platform-contained-table-inner-radius: 1px;
  border: 0 !important;
  border-radius: 2px !important;
  background: transparent;
  box-shadow: none !important;
  clip-path: none !important;
  scrollbar-gutter: auto;
}

.stat-detail-table {
  min-width: 100%;
  width: max(100%, var(--stat-detail-table-content-width, 960px));
  border: 1px solid var(--el-border-color-light) !important;
  border-radius: 2px !important;
  overflow: hidden;
  background: var(--platform-table-header-bg, #f8fafc);
}

.stat-detail-table::before,
.stat-detail-table::after {
  display: none !important;
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
  z-index: 2600;
  height: 16px;
  padding: 5px 0;
  overflow: visible;
  pointer-events: auto;
  opacity: 1;
  background: transparent;
  box-shadow: none;
}

.stat-detail-table :deep(td .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 26px;
  width: 100% !important;
  min-width: 0;
  box-sizing: border-box;
  text-align: center;
  line-height: 1.35 !important;
  overflow: visible;
}

.stat-detail-table :deep(td .cell > .el-tooltip) {
  display: flex !important;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
  text-align: center;
}

.stat-detail-table :deep(td .cell:has(.detail-cell-tags)),
.stat-detail-table :deep(td .cell:has(.detail-gitlab-label)) {
  overflow: visible;
  white-space: normal;
}

.stat-detail-table :deep(td.stat-detail-cell--left .cell) {
  justify-content: flex-start;
  text-align: left !important;
}

.stat-detail-table :deep(td.stat-detail-cell--left .cell > .el-tooltip) {
  justify-content: flex-start;
  text-align: left !important;
}

.stat-detail-table :deep(th .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  width: 100%;
  text-align: center;
}

.stat-detail-table :deep(td.el-table__cell) {
  padding: 5px 0 !important;
  vertical-align: middle;
}

.stat-detail-table :deep(.el-table__row) {
  min-height: 34px;
}

.stat-detail-table :deep(th.el-table__cell) {
  padding: 7px 0 !important;
  background: var(--el-fill-color-lighter) !important;
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
  margin-top: 0 !important;
  padding: 0;
}

.detail-pagination :deep(.el-pagination) {
  --el-pagination-button-height: 26px;
  --el-pagination-button-width: 26px;
  --el-pagination-font-size: 13px;
  min-height: 26px;
}

.detail-pagination :deep(.el-select .el-select__wrapper) {
  min-height: 26px;
}

@media (max-width: 960px) {
  :global(.stat-detail-dialog.el-dialog) {
    width: calc(100vw - 12px);
    max-width: calc(100vw - 12px);
    padding: 8px;
    border-radius: 8px !important;
  }

  :global(.stat-detail-dialog .el-dialog__header) {
    padding: 0 8px 6px !important;
  }

  :global(.stat-detail-dialog .el-dialog__body) {
    padding: 4px 5px 6px;
  }

  .stat-detail-header {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .stat-detail-title {
    font-size: 24px;
    line-height: 32px;
  }

  :global(.stat-detail-title.el-dialog__title) {
    font-size: 24px !important;
    line-height: 32px !important;
  }

  .stat-detail-header-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
    flex-wrap: wrap;
  }
}

</style>
