<script setup lang="ts">
import { computed, ref, toRef, useSlots, watch } from 'vue';
// 通用记录表封装分页、排序、关键词和条件筛选，是多个正式记录页的交互底座。
// 表格不理解业务字段含义，只根据列配置和事件把用户意图传回页面层。
import { ArrowDown, ArrowUp, QuestionFilled, Refresh } from '@element-plus/icons-vue';
import BaseSearchInput from './BaseSearchInput.vue';
import BaseRecordTableCell from './BaseRecordTableCell.vue';
import RecordTableFilterFields from './RecordTableFilterFields.vue';
import { useDebouncedTask, useDelayedLoading } from './use-record-table-timers';
import { useFloatingHorizontalScrollbar } from '../../composables/useFloatingHorizontalScrollbar';
import type {
  RecordTableActiveFilterTag,
  RecordTableColumn,
  RecordTableFilterField,
} from '../../types/record-table';

const props = withDefaults(
  defineProps<{
    columns: RecordTableColumn[];
    rows: Record<string, unknown>[];
    loading?: boolean;
    keyword?: string;
    page: number;
    pageSize: number;
    total: number;
    rowKey?: string;
    expandedRowKeys?: Array<string | number>;
    expandColumnVisible?: boolean;
    rowActionsWidth?: number;
    loadingDelay?: number;
    pageSizeOptions?: number[];
    searchPlaceholder?: string;
    emptyDescription?: string;
    showSearch?: boolean;
    showRefresh?: boolean;
    primaryFilters?: RecordTableFilterField[];
    advancedFilters?: RecordTableFilterField[];
    filterValues?: Record<string, unknown>;
    activeFilterTags?: RecordTableActiveFilterTag[];
    advancedVisible?: boolean;
    queryButtonText?: string;
    keywordAutoSearch?: boolean;
    keywordAutoSearchDelay?: number;
    quickFilterMode?: boolean;
    quickFilterTogglePlacement?: 'primary-actions' | 'filter-builder';
  }>(),
  {
    loading: false,
    keyword: '',
    loadingDelay: 0,
    pageSizeOptions: () => [10, 20, 50, 100],
    rowKey: 'id',
    expandedRowKeys: () => [],
    expandColumnVisible: true,
    rowActionsWidth: 120,
    searchPlaceholder: '请输入关键字搜索',
    emptyDescription: '当前暂无可展示记录',
    showSearch: true,
    showRefresh: true,
    primaryFilters: () => [],
    advancedFilters: () => [],
    filterValues: () => ({}),
    activeFilterTags: () => [],
    advancedVisible: false,
    queryButtonText: '查询',
    keywordAutoSearch: false,
    keywordAutoSearchDelay: 600,
    quickFilterMode: false,
    quickFilterTogglePlacement: 'primary-actions',
  },
);

const emit = defineEmits<{
  (event: 'search', keyword: string): void;
  (event: 'reset'): void;
  (event: 'refresh'): void;
  (event: 'size-change', size: number): void;
  (event: 'current-change', page: number): void;
  (event: 'sort-change', payload: { prop: string; order: 'ascending' | 'descending' | null }): void;
  (event: 'filter-change', payload: { key: string; value: string | string[] | null }): void;
  (event: 'query', keyword: string): void;
  (event: 'clear-filter', key: string): void;
  (event: 'update:advancedVisible', value: boolean): void;
  (event: 'expand-change', row: Record<string, unknown>, expandedRows: Record<string, unknown>[]): void;
}>();

const keywordDraft = ref(props.keyword);
const inputFilterDrafts = ref<Record<string, string>>({});
const primaryFiltersExpanded = ref(false);
const tableShellRef = ref<HTMLElement>();
const allFilters = computed(() => [...props.primaryFilters, ...props.advancedFilters]);
const { displayedLoading } = useDelayedLoading(toRef(props, 'loading'), computed(() => props.loadingDelay ?? 0));
const keywordAutoSearchTask = useDebouncedTask(toRef(props, 'keywordAutoSearchDelay'));
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
  watchedSources: [
    () => props.rows,
    () => props.columns,
    () => props.expandedRowKeys,
  ],
});

watch(
  () => props.keyword,
  (value) => {
    keywordDraft.value = value;
  },
);

watch(
  [allFilters, () => props.filterValues],
  () => {
    const nextDrafts: Record<string, string> = {};
    for (const filter of allFilters.value) {
      if (filter.type !== 'input') {
        continue;
      }
      nextDrafts[filter.key] = String(props.filterValues[filter.key] ?? '');
    }
    inputFilterDrafts.value = nextDrafts;
  },
  { immediate: true, deep: true },
);

const slots = useSlots();
const hasRowActions = computed(() => Boolean(slots['row-actions']));
const hasExpand = computed(() => Boolean(slots.expand));
const hasFilterBuilder = computed(() => Boolean(slots['filter-builder']));
const hasPrimaryActions = computed(() => Boolean(slots['primary-actions']));
const hasToolbarPrefix = computed(() => Boolean(slots['toolbar-prefix']));
const hasContextPrefix = computed(() => Boolean(slots['context-prefix']));
const hasToolbarActions = computed(() => Boolean(slots['toolbar-actions']));
const hasPrimaryFilters = computed(() => props.primaryFilters.length > 0);
const hasAdvancedFilters = computed(() => props.advancedFilters.length > 0);
const hasActiveFilterTags = computed(() => props.activeFilterTags.length > 0);
const hasStandaloneSearch = computed(() => props.showSearch && !props.primaryFilters.some((item) => item.key === 'keyword'));
const hasRecordFieldFilters = computed(() => hasPrimaryFilters.value || hasAdvancedFilters.value);
const shouldShowPrimaryQueryActions = computed(() => hasRecordFieldFilters.value || !hasFilterBuilder.value);
const collapsedPrimaryFilterLimit = 5;
const quickFilterContentVisible = computed(() => !props.quickFilterMode || primaryFiltersExpanded.value);
const compactPrimaryFilters = computed(() =>
  props.quickFilterMode
    ? (primaryFiltersExpanded.value ? props.primaryFilters : [])
    : props.primaryFilters.slice(0, collapsedPrimaryFilterLimit),
);
const extraPrimaryFilters = computed(() =>
  props.quickFilterMode ? [] : props.primaryFilters.slice(collapsedPrimaryFilterLimit),
);
const quickFilterControlCount = computed(() => props.primaryFilters.length + (hasStandaloneSearch.value ? 1 : 0));
const hiddenPrimaryFilterCount = computed(() =>
  primaryFiltersExpanded.value ? 0 : (props.quickFilterMode ? quickFilterControlCount.value : extraPrimaryFilters.value.length),
);
const shouldShowPrimaryFilterToggle = computed(() =>
  props.quickFilterMode ? hasPrimaryFilters.value : props.primaryFilters.length > collapsedPrimaryFilterLimit,
);
const shouldShowPrimaryQueryButtons = computed(() => !props.quickFilterMode || primaryFiltersExpanded.value);
const shouldShowPrimaryFilterToggleInFilterBuilder = computed(() =>
  shouldShowPrimaryFilterToggle.value && props.quickFilterTogglePlacement === 'filter-builder' && hasFilterBuilder.value,
);
const shouldShowPrimaryFilterToggleInPrimaryActions = computed(() =>
  shouldShowPrimaryFilterToggle.value && !shouldShowPrimaryFilterToggleInFilterBuilder.value,
);
const hasPrimaryQueryActionButtons = computed(() =>
  shouldShowPrimaryFilterToggleInPrimaryActions.value || hasAdvancedFilters.value || shouldShowPrimaryQueryButtons.value,
);
const primaryFilterToggleIcon = computed(() => (primaryFiltersExpanded.value ? ArrowUp : ArrowDown));
const primaryFilterToggleText = computed(() => {
  if (props.quickFilterMode) {
    return primaryFiltersExpanded.value
      ? '收起快速筛选'
      : `快速筛选${hiddenPrimaryFilterCount.value ? `（${hiddenPrimaryFilterCount.value}）` : ''}`;
  }
  return primaryFiltersExpanded.value
    ? '收起筛选'
    : `展开筛选${hiddenPrimaryFilterCount.value ? `（${hiddenPrimaryFilterCount.value}）` : ''}`;
});

function togglePrimaryFilters() {
  primaryFiltersExpanded.value = !primaryFiltersExpanded.value;
}

function handleSearch() {
  const normalizedKeyword = keywordDraft.value.trim();
  emit('search', normalizedKeyword);
  emit('query', normalizedKeyword);
}

function handleReset() {
  keywordAutoSearchTask.clear();
  keywordDraft.value = '';
  const nextDrafts = { ...inputFilterDrafts.value };
  for (const key of Object.keys(nextDrafts)) {
    nextDrafts[key] = '';
  }
  inputFilterDrafts.value = nextDrafts;
  emit('reset');
}

function handleFilterChange(key: string, value: string | string[] | null) {
  emit('filter-change', { key, value });
}

function handleExpandChange(row: Record<string, unknown>, expandedRows: Record<string, unknown>[]) {
  emit('expand-change', row, expandedRows);
  void scheduleHorizontalScrollbarUpdate();
  wakeHorizontalScrollbar();
}

function toggleAdvancedVisible() {
  emit('update:advancedVisible', !props.advancedVisible);
}

function getInputFilterDraft(key: string) {
  return inputFilterDrafts.value[key] ?? String(props.filterValues[key] ?? '');
}

function updateInputFilterDraft(key: string, value: string) {
  inputFilterDrafts.value = {
    ...inputFilterDrafts.value,
    [key]: value,
  };
}

function commitInputFilterValue(key: string, value = getInputFilterDraft(key)) {
  const normalizedValue = String(value ?? '').trim();
  updateInputFilterDraft(key, normalizedValue);
  emit('filter-change', { key, value: normalizedValue });
  return normalizedValue;
}

function commitAllInputFilters() {
  const committedValues: Record<string, string> = {};
  for (const filter of allFilters.value) {
    if (filter.type !== 'input') {
      continue;
    }
    committedValues[filter.key] = commitInputFilterValue(filter.key);
  }
  return committedValues;
}

function resolveQueryKeyword(committedInputFilters: Record<string, string>) {
  if ('keyword' in committedInputFilters) {
    return committedInputFilters.keyword;
  }
  return keywordDraft.value.trim();
}

function handleQueryClick() {
  keywordAutoSearchTask.clear();
  const committedInputFilters = commitAllInputFilters();
  emit('query', resolveQueryKeyword(committedInputFilters));
}

function handleInputFilterSearch(key: string) {
  keywordAutoSearchTask.clear();
  commitInputFilterValue(key);
  if (!props.keywordAutoSearch || key !== 'keyword') {
    handleQueryClick();
  }
}

function handleInputFilterUpdate(key: string, value: string) {
  updateInputFilterDraft(key, value);
  if (props.keywordAutoSearch && key === 'keyword') {
    keywordAutoSearchTask.schedule(() => {
      commitInputFilterValue(key, value);
    });
  }
}

function handleInputFilterClear(key: string) {
  keywordAutoSearchTask.clear();
  commitInputFilterValue(key, '');
}

function emitStandaloneKeywordSearch(value = keywordDraft.value) {
  emit('search', String(value ?? '').trim());
}

function handleStandaloneKeywordUpdate(value: string) {
  keywordDraft.value = value;
  if (props.keywordAutoSearch && hasStandaloneSearch.value) {
    keywordAutoSearchTask.schedule(() => {
      emitStandaloneKeywordSearch(value);
    });
  }
}

function handleStandaloneKeywordSearch() {
  keywordAutoSearchTask.clear();
  if (props.keywordAutoSearch && hasStandaloneSearch.value) {
    emitStandaloneKeywordSearch();
    return;
  }
  handleSearch();
}

function handleStandaloneKeywordClear() {
  keywordAutoSearchTask.clear();
  keywordDraft.value = '';
  if (props.keywordAutoSearch && hasStandaloneSearch.value) {
    emitStandaloneKeywordSearch('');
    return;
  }
  handleReset();
}
</script>

<template>
  <div
    class="record-table-workspace"
    :class="{
      'record-table-workspace--quick-filters': quickFilterMode,
      'record-table-workspace--quick-filters-expanded': quickFilterMode && primaryFiltersExpanded,
    }"
  >
    <section v-if="hasContextPrefix" class="record-context-panel">
      <slot name="context-prefix" />
    </section>

    <section v-if="hasFilterBuilder || hasPrimaryActions || hasPrimaryFilters || hasAdvancedFilters || showSearch" class="record-filter-panel">
      <div v-if="hasFilterBuilder" class="record-condition-panel">
        <slot
          name="filter-builder"
          :quick-filter-toggle-visible="shouldShowPrimaryFilterToggleInFilterBuilder"
          :quick-filter-toggle-text="primaryFilterToggleText"
          :quick-filter-toggle-icon="primaryFilterToggleIcon"
          :toggle-quick-filter="togglePrimaryFilters"
        />
      </div>

      <div class="record-filter-primary">
        <div class="record-filter-query-strip">
          <div class="record-filter-fields-stack">
            <div class="record-filter-fields-cluster">
              <RecordTableFilterFields
                :filters="compactPrimaryFilters"
                :filter-values="filterValues"
                :input-drafts="inputFilterDrafts"
                keyword-field-visible
                :default-input-width="156"
                :default-select-width="168"
                :default-date-range-width="272"
                @input-update="handleInputFilterUpdate"
                @input-change="commitInputFilterValue"
                @input-search="handleInputFilterSearch"
                @input-clear="handleInputFilterClear"
                @filter-change="handleFilterChange"
              />

              <BaseSearchInput
                v-if="hasStandaloneSearch && quickFilterContentVisible"
                :model-value="keywordDraft"
                class="record-table-search"
                :placeholder="searchPlaceholder"
                @update:model-value="handleStandaloneKeywordUpdate"
                @search="handleStandaloneKeywordSearch"
                @clear="handleStandaloneKeywordClear"
              />
            </div>

            <el-collapse-transition>
              <div
                v-show="primaryFiltersExpanded && extraPrimaryFilters.length"
                class="record-filter-fields-cluster record-filter-extra-fields"
              >
                <RecordTableFilterFields
                  :filters="extraPrimaryFilters"
                  :filter-values="filterValues"
                  :input-drafts="inputFilterDrafts"
                  :default-input-width="156"
                  :default-select-width="168"
                  :default-date-range-width="272"
                  @input-update="handleInputFilterUpdate"
                  @input-change="commitInputFilterValue"
                  @input-search="handleInputFilterSearch"
                  @input-clear="handleInputFilterClear"
                  @filter-change="handleFilterChange"
                />
              </div>
            </el-collapse-transition>
          </div>

          <div v-if="shouldShowPrimaryQueryActions && hasPrimaryQueryActionButtons" class="record-filter-primary-actions">
            <el-button
              v-if="shouldShowPrimaryFilterToggleInPrimaryActions"
              plain
              :icon="primaryFilterToggleIcon"
              @click="togglePrimaryFilters"
            >
              {{ primaryFilterToggleText }}
            </el-button>
            <el-button v-if="hasAdvancedFilters" @click="toggleAdvancedVisible">
              {{ advancedVisible ? '收起高级筛选' : '高级筛选' }}
            </el-button>
            <template v-if="shouldShowPrimaryQueryButtons">
              <el-button type="primary" @click="handleQueryClick">
                {{ queryButtonText }}
              </el-button>
              <el-button @click="handleReset">重置</el-button>
            </template>
          </div>
        </div>

        <div v-if="hasPrimaryActions" class="record-filter-slot-actions">
          <slot name="primary-actions" />
        </div>
      </div>

      <el-collapse-transition>
        <div v-show="advancedVisible && hasAdvancedFilters" class="record-filter-advanced">
          <RecordTableFilterFields
            :filters="advancedFilters"
            :filter-values="filterValues"
            :input-drafts="inputFilterDrafts"
            :default-input-width="168"
            :default-select-width="168"
            :default-date-range-width="280"
            @input-update="handleInputFilterUpdate"
            @input-change="commitInputFilterValue"
            @input-search="handleInputFilterSearch"
            @input-clear="handleInputFilterClear"
            @filter-change="handleFilterChange"
          />
        </div>
      </el-collapse-transition>
    </section>

    <section v-if="hasActiveFilterTags" class="record-filter-tags">
      <span class="record-filter-tags-label">已选条件</span>
      <el-tag
        v-for="tag in activeFilterTags"
        :key="tag.key"
        closable
        effect="plain"
        class="record-filter-tag"
        @close="emit('clear-filter', tag.key)"
      >
        {{ tag.label }}：{{ tag.value }}
      </el-tag>
    </section>

    <div v-if="hasToolbarPrefix || hasToolbarActions || showRefresh" class="record-table-toolbar">
      <div class="record-table-toolbar-main">
        <slot name="toolbar-prefix" />
      </div>

      <div class="record-table-toolbar-actions">
        <slot name="toolbar-actions" />
        <el-button v-if="showRefresh" :icon="Refresh" @click="emit('refresh')">刷新</el-button>
      </div>
    </div>

    <div
      ref="tableShellRef"
      class="record-table-frame"
      :class="{ 'is-scrollbar-awake': scrollbarAwake, 'has-horizontal-overflow': hasHorizontalOverflow }"
      tabindex="0"
      @mouseenter="wakeHorizontalScrollbar"
      @mousemove="wakeHorizontalScrollbar"
      @focusin="wakeHorizontalScrollbar"
      @wheel="handleHorizontalWheel"
    >
      <el-table
        v-loading="displayedLoading"
        :data="rows"
        :row-key="rowKey"
        :expand-row-keys="expandedRowKeys"
        border
        stripe
        class="record-table"
        style="width: max-content; min-width: 100%"
        @sort-change="emit('sort-change', $event)"
        @expand-change="handleExpandChange"
      >
        <el-table-column
          v-if="hasExpand"
          type="expand"
          :width="expandColumnVisible ? 42 : 1"
          :class-name="expandColumnVisible ? undefined : 'record-table-expand-column-hidden'"
          :label-class-name="expandColumnVisible ? undefined : 'record-table-expand-column-hidden'"
        >
          <template #default="{ row }">
            <slot name="expand" :row="row" />
          </template>
        </el-table-column>

        <el-table-column
          v-for="column in columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :sortable="column.sortable ? 'custom' : false"
          :width="column.width"
          :min-width="column.minWidth"
          :fixed="column.fixed"
          :align="column.align ?? 'center'"
          :header-align="column.headerAlign ?? 'center'"
          :show-overflow-tooltip="column.showOverflowTooltip ?? true"
        >
          <template v-if="column.headerTooltip" #header>
            <span class="record-table-header-help">
              <span>{{ column.label }}</span>
              <el-tooltip :content="column.headerTooltip" placement="top">
                <el-icon class="record-table-header-help-icon">
                  <QuestionFilled />
                </el-icon>
              </el-tooltip>
            </span>
          </template>
          <template #default="{ row }">
            <slot
              v-if="$slots[`cell-${column.key}`]"
              :name="`cell-${column.key}`"
              :row="row"
              :value="row[column.key]"
            />
            <BaseRecordTableCell v-else :column="column" :value="row[column.key]" />
          </template>
        </el-table-column>

        <el-table-column v-if="hasRowActions" label="操作" :width="rowActionsWidth" fixed="right">
          <template #default="{ row }">
            <slot name="row-actions" :row="row" />
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="emptyDescription" />
        </template>
      </el-table>
      <div
        v-show="hasHorizontalOverflow"
        ref="floatingScrollbarRef"
        class="record-table-floating-horizontal"
        aria-hidden="true"
        @mouseenter="wakeHorizontalScrollbar"
        @scroll="handleFloatingHorizontalScroll"
      >
        <div class="record-table-floating-horizontal-spacer" :style="{ width: `${horizontalSpacerWidth}px` }" />
      </div>
    </div>

    <div class="record-table-pagination">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="pageSizeOptions"
        :total="total"
        @size-change="emit('size-change', $event)"
        @current-change="emit('current-change', $event)"
      />
    </div>
  </div>
</template>

<style scoped>
.record-table-workspace {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.record-filter-panel {
  display: grid;
  gap: 10px;
  padding: 8px 10px 10px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 10px;
  background: rgba(248, 250, 252, 0.7);
}

.record-context-panel {
  padding: 0 2px 4px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.record-filter-primary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px;
}

.record-table-workspace--quick-filters-expanded .record-filter-primary {
  grid-template-columns: 1fr;
}

.record-table-workspace--quick-filters-expanded .record-filter-slot-actions {
  justify-self: stretch;
  justify-content: flex-end;
}

.record-condition-panel {
  min-width: 0;
  padding: 7px 9px;
  border: 1px dashed rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.78);
}

.record-condition-panel > :slotted(.stat-filter-builder) {
  min-width: 0;
}

.record-filter-query-strip {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px 10px;
  width: 100%;
  min-width: 0;
  min-height: 32px;
}

.record-filter-fields-cluster {
  display: flex;
  align-items: center;
  align-content: flex-start;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  max-width: 100%;
}

.record-filter-fields-stack {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.record-filter-extra-fields {
  padding-top: 2px;
}

.record-filter-primary-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  flex: 0 0 auto;
  justify-content: flex-end;
}

.record-filter-slot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.record-filter-slot-actions {
  justify-self: end;
}

@media (max-width: 1180px) {
  .record-filter-primary {
    grid-template-columns: 1fr;
  }

  .record-filter-query-strip {
    grid-template-columns: 1fr;
  }

  .record-filter-slot-actions {
    justify-self: start;
    justify-content: flex-start;
  }

  .record-filter-primary-actions {
    justify-content: flex-start;
  }
}

.record-filter-advanced {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding: 12px;
  border-top: 1px dashed rgba(15, 23, 42, 0.08);
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.88);
}

.record-filter-main-date {
  width: 280px;
}

.record-filter-main-keyword {
  width: 260px;
}

.record-filter-select,
.record-filter-input,
.record-table-search {
  width: 168px;
}

.record-filter-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-height: 28px;
  padding: 0 2px 4px;
}

.record-filter-tags-label {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.5);
  font-weight: 600;
}

.record-filter-tag {
  border-radius: 999px;
}

.record-table-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 40px;
  padding: 2px 2px 0;
}

.record-table-toolbar-main {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1 1 560px;
  flex-wrap: wrap;
}

.record-table-toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  max-width: 100%;
}

.record-table-frame {
  position: relative;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  border-radius: 8px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  background: #fff;
  outline: none;
  scrollbar-gutter: stable;
}

.record-table {
  width: max-content;
  min-width: 100%;
}

.record-table :deep(.cell) {
  min-width: 0;
}

.record-table :deep(.el-table__expanded-cell) {
  padding: 0 !important;
  background: #fff;
}

.record-table-frame :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.record-table-floating-horizontal {
  position: sticky;
  right: 14px;
  bottom: 2px;
  left: 0;
  z-index: 6;
  height: 14px;
  overflow-x: auto;
  overflow-y: hidden;
  pointer-events: auto;
  opacity: 1;
  scrollbar-width: thin;
  scrollbar-color: rgb(148 163 184 / 76%) transparent;
}

.record-table-floating-horizontal::-webkit-scrollbar {
  height: 9px;
}

.record-table-floating-horizontal::-webkit-scrollbar-track {
  background: transparent;
}

.record-table-floating-horizontal::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgb(148 163 184 / 76%);
}

.record-table-floating-horizontal-spacer {
  height: 1px;
}

.record-table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 2px;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper),
:deep(.el-date-editor.el-input__wrapper) {
  min-height: 38px;
  border-radius: 10px;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.08) inset;
}

:deep(.el-input__wrapper:hover),
:deep(.el-select__wrapper:hover),
:deep(.el-date-editor.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.3) inset;
}

:deep(.el-table th.el-table__cell) {
  background: linear-gradient(180deg, #f8fafc, #f1f5f9);
}

.record-table-header-help {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-width: 0;
}

.record-table-header-help-icon {
  flex: 0 0 auto;
  color: rgba(100, 116, 139, 0.86);
  font-size: 14px;
  vertical-align: middle;
}

:deep(.record-table-expand-column-hidden) {
  padding: 0 !important;
}

:deep(.record-table-expand-column-hidden .cell) {
  width: 0;
  padding: 0;
  overflow: hidden;
}

:deep(.record-table-expand-column-hidden .el-table__expand-icon) {
  display: none;
}

@media (max-width: 960px) {
  .record-filter-primary {
    grid-template-columns: 1fr;
  }

  .record-filter-slot-actions {
    justify-content: flex-start;
    min-width: 0;
  }

  .record-table-toolbar {
    grid-template-columns: 1fr;
  }

  .record-table-toolbar-actions {
    justify-content: flex-start;
  }
}
</style>
