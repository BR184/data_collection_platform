<script setup lang="ts">
import { computed, ref, toRef, useSlots, watch } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import BaseSearchInput from './BaseSearchInput.vue';
import BaseRecordTableCell from './BaseRecordTableCell.vue';
import RecordTableFilterFields from './RecordTableFilterFields.vue';
import { useDebouncedTask, useDelayedLoading } from './use-record-table-timers';
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
    keywordAutoSearchDelay: 400,
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
const allFilters = computed(() => [...props.primaryFilters, ...props.advancedFilters]);
const { displayedLoading } = useDelayedLoading(toRef(props, 'loading'), computed(() => props.loadingDelay ?? 0));
const keywordAutoSearchTask = useDebouncedTask(toRef(props, 'keywordAutoSearchDelay'));

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
  <div class="record-table-workspace">
    <section v-if="hasContextPrefix" class="record-context-panel">
      <slot name="context-prefix" />
    </section>

    <section v-if="hasFilterBuilder || hasPrimaryActions || hasPrimaryFilters || hasAdvancedFilters || showSearch" class="record-filter-panel">
      <div class="record-filter-primary">
        <slot name="filter-builder" />

        <RecordTableFilterFields
          :filters="primaryFilters"
          :filter-values="filterValues"
          :input-drafts="inputFilterDrafts"
          keyword-field-visible
          :default-input-width="168"
          :default-select-width="180"
          :default-date-range-width="280"
          @input-update="handleInputFilterUpdate"
          @input-change="commitInputFilterValue"
          @input-search="handleInputFilterSearch"
          @input-clear="handleInputFilterClear"
          @filter-change="handleFilterChange"
        />

        <BaseSearchInput
          v-if="hasStandaloneSearch"
          :model-value="keywordDraft"
          class="record-table-search"
          :placeholder="searchPlaceholder"
          @update:model-value="handleStandaloneKeywordUpdate"
          @search="handleStandaloneKeywordSearch"
          @clear="handleStandaloneKeywordClear"
        />

        <div
          class="record-filter-primary-actions"
          :class="{ 'record-filter-primary-actions-inline': hasFilterBuilder }"
        >
          <el-button v-if="hasAdvancedFilters" @click="toggleAdvancedVisible">
            {{ advancedVisible ? '收起高级筛选' : '高级筛选' }}
          </el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button type="primary" @click="handleQueryClick">
            {{ queryButtonText }}
          </el-button>
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

    <div class="record-table-frame">
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
  padding: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.98));
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 12px 28px rgba(15, 23, 42, 0.04);
}

.record-filter-panel {
  display: grid;
  gap: 10px;
  padding: 4px 2px 10px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.record-context-panel {
  padding: 0 2px 4px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.record-filter-primary {
  display: flex;
  align-items: stretch;
  gap: 8px;
  flex-wrap: wrap;
}

.record-filter-primary-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.record-filter-slot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  flex-wrap: wrap;
}

.record-filter-primary-actions-inline {
  flex: 0 0 auto;
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
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
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}

.record-table-frame {
  overflow-x: auto;
  overflow-y: hidden;
  border-radius: 12px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  background: #fff;
}

.record-table {
  width: max-content;
  min-width: 100%;
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
</style>
