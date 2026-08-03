<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRef, useSlots, watch } from 'vue';
// 通用记录表封装分页、排序、关键词和条件筛选，是多个正式记录页的交互底座。
// 表格不理解业务字段含义，只根据列配置和事件把用户意图传回页面层。
import { ArrowDown, ArrowUp, Refresh } from '@element-plus/icons-vue';
import BaseSearchInput from './BaseSearchInput.vue';
import BaseRecordTableCell from './BaseRecordTableCell.vue';
import {
  resolveRecordTableCellDisplay,
  shouldShowRecordTableOverflowTooltip,
} from './base-record-table-cell';
import RecordTableFilterFields from './RecordTableFilterFields.vue';
import SmartTableHeader from './SmartTableHeader.vue';
import TableFunctionBar from './TableFunctionBar.vue';
import { tableHeaderMinimumWidth } from './table-header-layout';
import { useDebouncedTask, useDelayedLoading } from './use-record-table-timers';
import { useFloatingHorizontalScrollbar } from '../../composables/useFloatingHorizontalScrollbar';
import { useStickyTableMaxHeight, useTableStickyHeaderPreference } from '../../composables/useTableStickyHeader';
import { filterDimensionKey, filterFieldsByBlockedDimensions } from '../filter-priority';
import type {
  RecordTableActiveFilterTag,
  RecordTableColumn,
  RecordTableFilterField,
  RecordTableFilterValue,
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
    expandColumnFixedLeft?: boolean;
    rowActionsWidth?: number;
    loadingDelay?: number;
    pageSizeOptions?: number[];
    searchPlaceholder?: string;
    emptyDescription?: string;
    showSearch?: boolean;
    showRefresh?: boolean;
    primaryFilters?: RecordTableFilterField[];
    advancedFilters?: RecordTableFilterField[];
    hiddenFilterKeys?: string[];
    disabledFilterKeys?: string[];
    highlightedFilterKeys?: string[];
    filterValues?: Record<string, unknown>;
    activeFilterTags?: RecordTableActiveFilterTag[];
    advancedVisible?: boolean;
    queryButtonText?: string;
    keywordAutoSearch?: boolean;
    keywordAutoSearchDelay?: number;
    quickFilterMode?: boolean;
    quickFilterTogglePlacement?: 'primary-actions' | 'filter-builder';
    filterBuilderExpanded?: boolean;
    settingsScopeKey?: string;
    sortBy?: string;
    sortOrder?: string;
    defaultSortBy?: string;
    defaultSortOrder?: string;
    showCurrentSort?: boolean;
    quickFilterChangeGuard?: (key: string, value: RecordTableFilterValue) => boolean;
  }>(),
  {
    loading: false,
    keyword: '',
    loadingDelay: 0,
    pageSizeOptions: () => [10, 20, 50, 100],
    rowKey: 'id',
    expandedRowKeys: () => [],
    expandColumnVisible: true,
    expandColumnFixedLeft: false,
    rowActionsWidth: 120,
    searchPlaceholder: '输入任意关键字搜索',
    emptyDescription: '当前暂无可展示记录',
    showSearch: true,
    showRefresh: true,
    primaryFilters: () => [],
    advancedFilters: () => [],
    hiddenFilterKeys: () => [],
    disabledFilterKeys: () => [],
    highlightedFilterKeys: () => [],
    filterValues: () => ({}),
    activeFilterTags: () => [],
    advancedVisible: false,
    queryButtonText: '查询',
    keywordAutoSearch: false,
    keywordAutoSearchDelay: 600,
    quickFilterMode: false,
    quickFilterTogglePlacement: 'primary-actions',
    filterBuilderExpanded: false,
    settingsScopeKey: '',
    sortBy: '',
    sortOrder: '',
    defaultSortBy: '',
    defaultSortOrder: 'desc',
    showCurrentSort: true,
    quickFilterChangeGuard: () => true,
  },
);

const emit = defineEmits<{
  (event: 'search', keyword: string): void;
  (event: 'reset'): void;
  (event: 'refresh'): void;
  (event: 'size-change', size: number): void;
  (event: 'current-change', page: number): void;
  (event: 'sort-change', payload: { prop: string; order: 'ascending' | 'descending' | null }): void;
  (event: 'filter-change', payload: { key: string; value: RecordTableFilterValue }): void;
  (event: 'query', keyword: string): void;
  (event: 'clear-filter', key: string): void;
  (event: 'update:advancedVisible', value: boolean): void;
  (event: 'expand-change', row: Record<string, unknown>, expandedRows: Record<string, unknown>[]): void;
}>();

const keywordDraft = ref(props.keyword);
const inputFilterDrafts = ref<Record<string, string>>({});
const localFilterValues = ref<Record<string, unknown>>({});
const primaryFiltersExpanded = ref(false);
const tableShellRef = ref<HTMLElement>();
const tableViewportWidth = ref(0);
let tableFrameResizeObserver: ResizeObserver | undefined;
const { stickyHeaderEnabled } = useTableStickyHeaderPreference(() => props.settingsScopeKey);
const {
  tableMaxHeightValue,
  scheduleTableMaxHeightUpdate,
} = useStickyTableMaxHeight(tableShellRef, stickyHeaderEnabled);
const visiblePrimaryFilters = computed(() => filterFieldsByBlockedDimensions(props.primaryFilters, props.hiddenFilterKeys));
const visibleAdvancedFilters = computed(() => filterFieldsByBlockedDimensions(props.advancedFilters, props.hiddenFilterKeys));
const allFilters = computed(() => [...visiblePrimaryFilters.value, ...visibleAdvancedFilters.value]);
const hiddenFilterDimensions = computed(() => new Set(props.hiddenFilterKeys.map(filterDimensionKey).filter(Boolean)));
const disabledFilterDimensions = computed(() => new Set(props.disabledFilterKeys.map(filterDimensionKey).filter(Boolean)));
const highlightedFilterDimensions = computed(() => new Set(props.highlightedFilterKeys.map(filterDimensionKey).filter(Boolean)));
const { displayedLoading } = useDelayedLoading(toRef(props, 'loading'), computed(() => props.loadingDelay ?? 0));
const keywordAutoSearchTask = useDebouncedTask(toRef(props, 'keywordAutoSearchDelay'));
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
  () => props.filterValues,
  (value) => {
    localFilterValues.value = { ...value };
  },
  { immediate: true, deep: true },
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

watch(
  [
    () => props.advancedVisible,
    () => props.filterBuilderExpanded,
    () => props.rows.length,
    () => props.columns.length,
  ],
  () => {
    void nextTick(scheduleTableMaxHeightUpdate);
  },
);

const slots = useSlots();
const hasRowActions = computed(() => Boolean(slots['row-actions']));
const hasExpand = computed(() => Boolean(slots.expand));
const hasFilterBuilder = computed(() => Boolean(slots['filter-builder']));
const hasPrimaryActions = computed(() => Boolean(slots['primary-actions']));
const hasToolbarPrefix = computed(() => Boolean(slots['toolbar-prefix']));
const hasContextPrefix = computed(() => Boolean(slots['context-prefix']));
const hasToolbarActions = computed(() => Boolean(slots['toolbar-actions']));
const hasPrimaryFilters = computed(() => visiblePrimaryFilters.value.length > 0);
const hasAdvancedFilters = computed(() => visibleAdvancedFilters.value.length > 0);
const hasStandaloneSearch = computed(() =>
  props.showSearch
    && !hiddenFilterDimensions.value.has(filterDimensionKey('keyword'))
    && !visiblePrimaryFilters.value.some((item) => item.key === 'keyword'),
);
const hasRecordFieldFilters = computed(() => hasPrimaryFilters.value || hasAdvancedFilters.value);
const shouldShowPrimaryQueryActions = computed(() => hasRecordFieldFilters.value || !hasFilterBuilder.value);
const collapsedPrimaryFilterLimit = 5;
const quickFilterContentVisible = computed(() => !props.quickFilterMode || primaryFiltersExpanded.value);
const compactPrimaryFilters = computed(() =>
  props.quickFilterMode
    ? (primaryFiltersExpanded.value ? visiblePrimaryFilters.value : [])
    : visiblePrimaryFilters.value.slice(0, collapsedPrimaryFilterLimit),
);
const extraPrimaryFilters = computed(() =>
  props.quickFilterMode ? [] : visiblePrimaryFilters.value.slice(collapsedPrimaryFilterLimit),
);
const orderedCompactPrimaryFilters = computed(() => prioritizeKeywordFilter(compactPrimaryFilters.value));
const orderedExtraPrimaryFilters = computed(() => prioritizeKeywordFilter(extraPrimaryFilters.value));
const quickFilterControlCount = computed(() => visiblePrimaryFilters.value.length + (hasStandaloneSearch.value ? 1 : 0));
const hiddenPrimaryFilterCount = computed(() =>
  primaryFiltersExpanded.value ? 0 : (props.quickFilterMode ? quickFilterControlCount.value : extraPrimaryFilters.value.length),
);
const shouldShowPrimaryFilterToggle = computed(() =>
  props.quickFilterMode ? hasPrimaryFilters.value : visiblePrimaryFilters.value.length > collapsedPrimaryFilterLimit,
);
const shouldSuppressPrimaryQueryButtons = computed(() =>
  props.quickFilterMode
    && props.filterBuilderExpanded
    && hasFilterBuilder.value
    && props.quickFilterTogglePlacement === 'filter-builder',
);
const shouldShowPrimaryQueryButtons = computed(() =>
  (!props.quickFilterMode || primaryFiltersExpanded.value) && !shouldSuppressPrimaryQueryButtons.value,
);
const shouldShowPrimaryQueryButton = computed(() => !props.quickFilterMode && shouldShowPrimaryQueryButtons.value);
const shouldShowPrimaryResetButton = computed(() =>
  props.quickFilterMode
    ? primaryFiltersExpanded.value && !shouldSuppressPrimaryQueryButtons.value
    : shouldShowPrimaryQueryButtons.value,
);
const shouldShowPrimaryFilterToggleInFilterBuilder = computed(() =>
  shouldShowPrimaryFilterToggle.value && props.quickFilterTogglePlacement === 'filter-builder' && hasFilterBuilder.value,
);
const shouldShowPrimaryFilterToggleInPrimaryActions = computed(() =>
  shouldShowPrimaryFilterToggle.value && !shouldShowPrimaryFilterToggleInFilterBuilder.value,
);
const hasPrimaryQueryActionButtons = computed(() =>
  shouldShowPrimaryFilterToggleInPrimaryActions.value
    || hasAdvancedFilters.value
    || shouldShowPrimaryQueryButton.value
    || shouldShowPrimaryResetButton.value,
);
const hasVisiblePrimaryFilterControls = computed(() =>
  compactPrimaryFilters.value.length > 0
    || (hasStandaloneSearch.value && quickFilterContentVisible.value)
    || (primaryFiltersExpanded.value && extraPrimaryFilters.value.length > 0),
);
const shouldShowPrimaryFilterRow = computed(() =>
  hasVisiblePrimaryFilterControls.value || (shouldShowPrimaryQueryActions.value && hasPrimaryQueryActionButtons.value),
);
const currentSortSummary = computed(() => {
  const fieldKey = String(props.sortBy || props.defaultSortBy || '').trim();
  if (!fieldKey) {
    return '';
  }
  const column = props.columns.find((item) => item.key === fieldKey);
  const fieldLabel = column?.label ?? readableSortFieldLabel(fieldKey);
  return `${fieldLabel} / ${readableSortDirection(props.sortOrder || props.defaultSortOrder)}`;
});
const shouldShowCurrentSort = computed(() =>
  props.showCurrentSort && Boolean(currentSortSummary.value) && props.columns.some((column) => column.sortable),
);
const quickFilterSummaryChips = computed(() => buildQuickFilterSummaryChips());
const primaryFilterToggleIcon = computed(() => (primaryFiltersExpanded.value ? ArrowUp : ArrowDown));
const baseColumnRenderWidths = computed<Record<string, number>>(() =>
  Object.fromEntries(props.columns.map((column) => [column.key, effectiveColumnWidth(column)])),
);
const columnRenderWidths = computed<Record<string, number>>(() => {
  const widths = { ...baseColumnRenderWidths.value };
  const expandWidth = hasExpand.value ? (props.expandColumnVisible ? 42 : 1) : 0;
  const rowActionsWidth = hasRowActions.value ? props.rowActionsWidth : 0;
  const baseContentWidth = expandWidth
    + rowActionsWidth
    + props.columns.reduce((total, column) => total + (widths[column.key] ?? 0), 0)
    + 2;
  const extraWidth = Math.floor(tableViewportWidth.value - baseContentWidth);
  if (extraWidth <= 0) {
    return widths;
  }

  const flexibleColumns = props.columns
    .map((column) => ({ column, weight: columnFlexWeight(column) }))
    .filter((item) => item.weight > 0);
  const totalWeight = flexibleColumns.reduce((total, item) => total + item.weight, 0);
  if (!totalWeight) {
    return widths;
  }

  for (const { column, weight } of flexibleColumns) {
    widths[column.key] = Math.ceil((widths[column.key] ?? effectiveColumnWidth(column)) + (extraWidth * weight) / totalWeight);
  }
  return widths;
});
const tableContentWidth = computed(() => {
  const expandWidth = hasExpand.value ? (props.expandColumnVisible ? 42 : 1) : 0;
  const rowActionsWidth = hasRowActions.value ? props.rowActionsWidth : 0;
  const dataColumnsWidth = props.columns.reduce((total, column) => total + (columnRenderWidths.value[column.key] ?? effectiveColumnWidth(column)), 0);
  return expandWidth + rowActionsWidth + dataColumnsWidth + 2;
});
const recordTableStyle = computed(() => ({
  width: `${tableContentWidth.value}px`,
  minWidth: '100%',
}));
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
  void nextTick(scheduleTableMaxHeightUpdate);
}

function isFilterKeyDisabled(key: string) {
  return disabledFilterDimensions.value.has(filterDimensionKey(key));
}

function isFilterKeyHighlighted(key: string) {
  return highlightedFilterDimensions.value.has(filterDimensionKey(key));
}

function prioritizeKeywordFilter(filters: RecordTableFilterField[]) {
  const keywordFilters = filters.filter((filter) => filter.key === 'keyword');
  const otherFilters = filters.filter((filter) => filter.key !== 'keyword');
  return [...keywordFilters, ...otherFilters];
}

function updateTableViewportWidth() {
  tableViewportWidth.value = tableShellRef.value?.clientWidth ?? 0;
  scheduleTableMaxHeightUpdate();
}

onMounted(() => {
  tableFrameResizeObserver = new ResizeObserver(updateTableViewportWidth);
  if (tableShellRef.value) {
    tableFrameResizeObserver.observe(tableShellRef.value);
  }
  void nextTick(updateTableViewportWidth);
});

onBeforeUnmount(() => {
  tableFrameResizeObserver?.disconnect();
});

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

function handleFilterChange(key: string, value: RecordTableFilterValue) {
  if (isQuickFilterControlKey(key) && !props.quickFilterChangeGuard(key, value)) {
    localFilterValues.value = { ...props.filterValues };
    return;
  }
  localFilterValues.value = {
    ...localFilterValues.value,
    [key]: value,
  };
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
  return inputFilterDrafts.value[key] ?? String(localFilterValues.value[key] ?? '');
}

function updateInputFilterDraft(key: string, value: string) {
  inputFilterDrafts.value = {
    ...inputFilterDrafts.value,
    [key]: value,
  };
}

function commitInputFilterValue(key: string, value = getInputFilterDraft(key)) {
  const normalizedValue = String(value ?? '').trim();
  if (isQuickFilterControlKey(key) && !props.quickFilterChangeGuard(key, normalizedValue)) {
    updateInputFilterDraft(key, String(localFilterValues.value[key] ?? ''));
    return String(localFilterValues.value[key] ?? '');
  }
  updateInputFilterDraft(key, normalizedValue);
  localFilterValues.value = {
    ...localFilterValues.value,
    [key]: normalizedValue,
  };
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
  if (props.quickFilterMode) {
    return;
  }
  if (!props.keywordAutoSearch || key !== 'keyword') {
    handleQueryClick();
  }
}

function handleQuickInputFilterChange(key: string, value = getInputFilterDraft(key)) {
  commitInputFilterValue(key, value);
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
  const normalizedValue = String(value ?? '').trim();
  if (isQuickFilterControlKey('keyword') && !props.quickFilterChangeGuard('keyword', normalizedValue)) {
    keywordDraft.value = String(props.keyword ?? '');
    return;
  }
  emit('search', normalizedValue);
}

function isQuickFilterControlKey(key: string) {
  if (!props.quickFilterMode) {
    return false;
  }
  if (key === 'keyword' && hasStandaloneSearch.value) {
    return true;
  }
  return visiblePrimaryFilters.value.some((filter) => filterDimensionKey(filter.key) === filterDimensionKey(key));
}

function effectiveColumnWidth(column: RecordTableColumn) {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const lowerBound = effectiveColumnLowerBound(column);
  const contentWidth = estimateColumnContentWidth(column);
  const suggestedWidth = column.width ?? 0;
  const upperBound = Math.max(lowerBound, suggestedWidth, defaultColumnUpperBound(column));
  return Math.ceil(clamp(Math.max(lowerBound, contentWidth), lowerBound, upperBound));
}

function effectiveColumnMinWidth(column: RecordTableColumn) {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  return effectiveColumnLowerBound(column);
}

function effectiveColumnLowerBound(column: RecordTableColumn) {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const reservePx = (column.sortable ? 24 : 8) + (column.headerTooltip ? 16 : 0);
  return Math.max(adjustedConfiguredMinWidth(column), tableHeaderMinimumWidth(column.label, reservePx, column.headerLines), columnTypeFloor(column));
}

function adjustedConfiguredMinWidth(column: RecordTableColumn) {
  if (!column.minWidth) {
    return 0;
  }
  if (column.type === 'tags') {
    return column.minWidth;
  }
  if (isNarrativeColumn(column)) {
    return column.minWidth;
  }
  return Math.min(column.minWidth, columnTypeFloor(column) + 48);
}

function columnTypeFloor(column: RecordTableColumn) {
  if (isProjectLikeColumn(column)) {
    return 108;
  }
  if (column.type === 'number') {
    return 58;
  }
  if (column.type === 'datetime') {
    return 128;
  }
  if (column.type === 'tags') {
    return 132;
  }
  if (column.type === 'tag') {
    return 86;
  }
  if (column.type === 'link') {
    return 78;
  }
  return 68;
}

function defaultColumnUpperBound(column: RecordTableColumn) {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  if (isProjectLikeColumn(column)) {
    return 156;
  }
  if (isNarrativeColumn(column)) {
    return column.fixed ? 360 : 460;
  }
  if (column.type === 'tags') {
    return 420;
  }
  if (column.type === 'datetime') {
    return 176;
  }
  if (column.type === 'number') {
    return 128;
  }
  if (column.type === 'tag') {
    return 140;
  }
  if (column.type === 'link') {
    return 132;
  }
  return 172;
}

function columnFlexWeight(column: RecordTableColumn) {
  if (column.fixed || isPersonNameColumn(column) || isProjectLikeColumn(column)) {
    return 0;
  }
  if (isNarrativeColumn(column)) {
    return 3.2;
  }
  if (column.type === 'tags') {
    return 2;
  }
  if (/非法类型|标签|原因|说明/.test(column.label)) {
    return 1.2;
  }
  return 0;
}

function estimateColumnContentWidth(column: RecordTableColumn) {
  if (isPersonNameColumn(column)) {
    return effectivePersonNameColumnWidth(column);
  }
  const values = props.rows.slice(0, 80).map((row) => row[column.key]);
  if (!values.length) {
    return columnTypeFloor(column);
  }
  const maxContentPx = values.reduce((max, value) => Math.max(max, cellVisualWidthPx(value, column)), 0);
  const basePadding = column.type === 'tags'
    ? 48
    : column.type === 'tag'
      ? 36
      : column.type === 'number'
        ? 34
        : column.type === 'link'
          ? 38
          : isProjectLikeColumn(column)
            ? 34
          : isCompactTextColumn(column)
            ? 18
            : 26;
  return maxContentPx + basePadding;
}

function cellVisualWidthPx(value: unknown, column: RecordTableColumn) {
  const display = resolveRecordTableCellDisplay(value);
  if (column.type === 'tags') {
    if (!display.tags.length) {
      return estimateTextWidthPx('-');
    }
    const visibleLabels = display.tags.slice(0, 3).map((tag) => tag.label);
    const longestLabelWidth = visibleLabels.reduce((max, label) => Math.max(max, estimateTextWidthPx(label)), 0);
    const combinedWidth = visibleLabels.reduce((total, label) => total + estimateTextWidthPx(label), 0) + Math.max(0, visibleLabels.length - 1) * 34;
    return Math.max(longestLabelWidth + 24, Math.min(combinedWidth, 220));
  }
  if (column.type === 'tag') {
    return Math.min(estimateTextWidthPx(display.primaryTag?.label ?? '-') + 20, 260);
  }
  if (column.type === 'link') {
    return estimateTextWidthPx(display.link?.label ?? '-');
  }
  return estimateTextWidthPx(display.text);
}

function isNarrativeColumn(column: RecordTableColumn) {
  return /(title|content|description|solution|reason|remark|note|message|summary|detail)/i.test(column.key)
    || /标题|内容|描述|说明|方案|原因|备注|详情|消息/.test(column.label);
}

function isCompactTextColumn(column: RecordTableColumn) {
  if (isNarrativeColumn(column) || isProjectLikeColumn(column) || column.type === 'tags') {
    return false;
  }
  return /模块|负责人|责任人|处理人|创建人|提交人|合并人|作者|状态|类型|类别|分支/.test(column.label)
    || /(module|owner|assignee|author|user|status|state|type|branch|name)$/i.test(column.key);
}

function isProjectLikeColumn(column: RecordTableColumn) {
  if (column.type === 'number' || /id$/i.test(column.key)) {
    return false;
  }
  return /所属项目|项目名称|代码库/.test(column.label)
    || /^(projectName|repositoryName|repositoryPath|projectPath|repository)$/.test(column.key);
}

function isPersonNameColumn(column: RecordTableColumn) {
  if (column.type === 'tags' || column.type === 'tag' || column.type === 'datetime' || column.type === 'number' || column.type === 'link') {
    return false;
  }
  return /被走查人|合并人|负责人|责任人|处理人|创建人|提交人|评审专家|审查人|审核人|作者/.test(column.label)
    || /(owner|assignee|author|reviewer|reviewOwner|mergedBy|createdBy|updatedBy|userName|user)$/i.test(column.key);
}

function personNameColumnWidth(column: RecordTableColumn) {
  const sortReserve = column.sortable ? 24 : 8;
  const tooltipReserve = column.headerTooltip ? 14 : 0;
  return 60 + sortReserve + tooltipReserve;
}

function effectivePersonNameColumnWidth(column: RecordTableColumn) {
  const sortReserve = column.sortable ? 24 : 8;
  const tooltipReserve = column.headerTooltip ? 14 : 0;
  return Math.max(
    personNameColumnWidth(column),
    tableHeaderMinimumWidth(column.label, sortReserve + tooltipReserve, column.headerLines),
  );
}

function estimateTextWidthPx(value: string) {
  const text = String(value ?? '').trim();
  if (!text || text === '-') {
    return 8;
  }
  return Array.from(text).reduce((total, character) => {
    if (/[\u4e00-\u9fff]/.test(character)) {
      return total + 13;
    }
    if (/[A-Z]/.test(character)) {
      return total + 7;
    }
    if (/[a-z]/.test(character)) {
      return total + 6.4;
    }
    if (/[0-9]/.test(character)) {
      return total + 6.8;
    }
    if (/\s/.test(character)) {
      return total + 4;
    }
    return total + 6.2;
  }, 0);
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function recordTableBodyAlign(column: RecordTableColumn) {
  if (isLeftAlignedTextColumn(column)) {
    return 'left';
  }
  return 'center';
}

function recordTableColumnClassName(column: RecordTableColumn) {
  return isLeftAlignedTextColumn(column) ? 'record-table-cell--left' : undefined;
}

function isLeftAlignedTextColumn(column: RecordTableColumn) {
  if (column.type === 'number' || column.type === 'datetime' || column.type === 'tag' || column.type === 'tags') {
    return false;
  }
  return /标题|合并请求内容|内容|描述|说明|方案|备注|详情|消息/.test(column.label)
    || /(title|content|description|solution|remark|note|message|summary|detail)$/i.test(column.key)
    || /mergeRequestContent/i.test(column.key);
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
  if (props.quickFilterMode) {
    emitStandaloneKeywordSearch();
    return;
  }
  if (props.keywordAutoSearch && hasStandaloneSearch.value) {
    emitStandaloneKeywordSearch();
    return;
  }
  handleSearch();
}

function handleStandaloneKeywordChange() {
  if (props.quickFilterMode) {
    handleStandaloneKeywordSearch();
  }
}

function handleStandaloneKeywordClear() {
  keywordAutoSearchTask.clear();
  keywordDraft.value = '';
  if (props.quickFilterMode) {
    emitStandaloneKeywordSearch('');
    return;
  }
  if (props.keywordAutoSearch && hasStandaloneSearch.value) {
    emitStandaloneKeywordSearch('');
    return;
  }
  handleReset();
}

function readableSortFieldLabel(fieldKey: string) {
  const fallbackLabels: Record<string, string> = {
    id: '编号',
    iid: '编号',
    issueIid: '议题编号',
    mergeRequestIid: '合并请求编号',
    title: '标题',
    name: '名称',
    moduleName: '模块名',
    moduleNames: '模块名',
    projectName: '所属项目',
    repositoryName: '代码库',
    owner: '负责人',
    author: '作者',
    assignee: '处理人',
    mergedBy: '合并人',
    createdAt: '创建时间',
    updatedAt: '更新时间',
    submittedAt: '提交时间',
    mergedAt: '合并时间',
    status: '状态',
    issueStatus: '议题状态',
    severity: '严重程度',
    illegalType: '非法类型',
    illegalTypes: '非法类型',
  };
  return fallbackLabels[fieldKey] ?? '当前字段';
}

function readableSortDirection(direction: string) {
  if (direction === 'asc' || direction === 'ascending') {
    return '升序';
  }
  if (direction === 'desc' || direction === 'descending') {
    return '降序';
  }
  return '默认顺序';
}

function buildQuickFilterSummaryChips() {
  const chips: Array<{ id: string; label: string }> = [];
  if (hasStandaloneSearch.value && String(props.keyword ?? '').trim()) {
    chips.push({
      id: 'quick:keyword',
      label: `${props.searchPlaceholder || '关键字'} ${String(props.keyword).trim()}`,
    });
  }
  for (const filter of visiblePrimaryFilters.value) {
    const value = localFilterValues.value[filter.key];
    const label = formatQuickFilterSummaryValue(filter, value);
    if (!label) {
      continue;
    }
    chips.push({
      id: `quick:${filter.key}`,
      label: `${filter.label} ${label}`,
    });
  }
  return chips;
}

function formatQuickFilterSummaryValue(filter: RecordTableFilterField, value: unknown) {
  if (filter.type === 'daterange') {
    if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
      return '';
    }
    return `${value[0]} ~ ${value[1]}`;
  }
  if (filter.type === 'numberrange') {
    if (!Array.isArray(value)) {
      return '';
    }
    const minimum = value[0];
    const maximum = value[1];
    if (minimum != null && maximum != null) {
      return `${minimum} ~ ${maximum}`;
    }
    if (minimum != null) {
      return `>= ${minimum}`;
    }
    return maximum != null ? `<= ${maximum}` : '';
  }
  if (filter.type === 'select') {
    const values = Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : [String(value ?? '').trim()].filter(Boolean);
    if (!values.length) {
      return '';
    }
    return values.map((item) => filter.options?.find((option) => option.value === item)?.label || item).join('、');
  }
  const text = String(value ?? '').trim();
  return text;
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

    <TableFunctionBar
      v-if="hasFilterBuilder || hasPrimaryFilters || hasAdvancedFilters || showSearch || hasToolbarPrefix || hasPrimaryActions || hasToolbarActions || showRefresh || shouldShowCurrentSort"
      class="record-table-function-bar"
      :quick-visible="shouldShowPrimaryFilterRow && (!quickFilterMode || primaryFiltersExpanded)"
    >
      <template v-if="hasFilterBuilder" #filter>
        <div class="record-condition-panel">
          <slot
            name="filter-builder"
            :quick-filter-toggle-visible="shouldShowPrimaryFilterToggleInFilterBuilder"
            :quick-filter-toggle-text="primaryFilterToggleText"
            :quick-filter-toggle-icon="primaryFilterToggleIcon"
            :quick-filter-summary-chips="quickFilterSummaryChips"
            :toggle-quick-filter="togglePrimaryFilters"
          />
        </div>
      </template>

      <template v-if="shouldShowPrimaryFilterRow" #quick>
        <div class="record-filter-query-strip">
          <div class="record-filter-fields-stack">
            <div class="record-filter-fields-cluster">
              <BaseSearchInput
                v-if="hasStandaloneSearch"
                :model-value="keywordDraft"
                :class="[
                  'record-table-search',
                  { 'record-table-search--quick-keyword': quickFilterMode },
                  { 'record-filter-control--priority-warning': isFilterKeyHighlighted('keyword') },
                ]"
                :placeholder="searchPlaceholder"
                :disabled="isFilterKeyDisabled('keyword')"
                @update:model-value="handleStandaloneKeywordUpdate"
                @change="handleStandaloneKeywordChange"
                @search="handleStandaloneKeywordSearch"
                @clear="handleStandaloneKeywordClear"
              />

              <RecordTableFilterFields
                :filters="orderedCompactPrimaryFilters"
                :filter-values="localFilterValues"
                :input-drafts="inputFilterDrafts"
                keyword-field-visible
                :default-input-width="156"
                :default-select-width="168"
                :default-date-range-width="272"
                :disabled-keys="disabledFilterKeys"
                :highlighted-keys="highlightedFilterKeys"
                @input-update="handleInputFilterUpdate"
                @input-change="handleQuickInputFilterChange"
                @input-search="handleInputFilterSearch"
                @input-clear="handleInputFilterClear"
                @filter-change="handleFilterChange"
              />
            </div>

            <el-collapse-transition>
              <div
                v-show="primaryFiltersExpanded && extraPrimaryFilters.length"
                class="record-filter-fields-cluster record-filter-extra-fields"
              >
                <RecordTableFilterFields
                  :filters="orderedExtraPrimaryFilters"
                  :filter-values="localFilterValues"
                  :input-drafts="inputFilterDrafts"
                  :default-input-width="156"
                  :default-select-width="168"
                  :default-date-range-width="272"
                  :disabled-keys="disabledFilterKeys"
                  :highlighted-keys="highlightedFilterKeys"
                  @input-update="handleInputFilterUpdate"
                  @input-change="handleQuickInputFilterChange"
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
              class="app-action-button app-action-button--filter"
              :icon="primaryFilterToggleIcon"
              @click="togglePrimaryFilters"
            >
              {{ primaryFilterToggleText }}
            </el-button>
            <el-button
              v-if="hasAdvancedFilters"
              class="app-action-button app-action-button--filter"
              @click="toggleAdvancedVisible"
            >
              {{ advancedVisible ? '收起高级筛选' : '高级筛选' }}
            </el-button>
            <template v-if="shouldShowPrimaryQueryButton">
              <el-button class="app-action-button app-action-button--query" @click="handleQueryClick">
                {{ queryButtonText }}
              </el-button>
            </template>
            <template v-if="shouldShowPrimaryResetButton">
              <el-button class="app-action-button app-action-button--reset" @click="handleReset">重置</el-button>
            </template>
          </div>
        </div>
      </template>

      <template v-if="hasToolbarPrefix" #status>
        <slot name="toolbar-prefix" />
      </template>

      <template v-if="hasPrimaryActions || hasToolbarActions || showRefresh || shouldShowCurrentSort" #actions>
        <slot name="primary-actions" />
        <template v-if="shouldShowCurrentSort">
          <span class="record-table-sort-label">当前排序</span>
          <el-tag effect="plain" type="info" class="record-page-sort-tag">
            {{ currentSortSummary }}
          </el-tag>
        </template>
        <slot name="toolbar-actions" />
        <el-button
          v-if="showRefresh"
          class="app-action-button app-action-button--refresh btn-gray"
          :icon="Refresh"
          @click="emit('refresh')"
        >
          刷新
        </el-button>
      </template>
    </TableFunctionBar>

    <el-collapse-transition>
      <div v-show="advancedVisible && hasAdvancedFilters" class="record-filter-advanced">
        <RecordTableFilterFields
          :filters="visibleAdvancedFilters"
          :filter-values="localFilterValues"
          :input-drafts="inputFilterDrafts"
          :default-input-width="168"
          :default-select-width="168"
          :default-date-range-width="280"
          :disabled-keys="disabledFilterKeys"
          :highlighted-keys="highlightedFilterKeys"
          @input-update="handleInputFilterUpdate"
          @input-change="commitInputFilterValue"
          @input-search="handleInputFilterSearch"
          @input-clear="handleInputFilterClear"
          @filter-change="handleFilterChange"
        />
      </div>
    </el-collapse-transition>

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
        :fit="false"
        :max-height="tableMaxHeightValue"
        class="record-table"
        :style="recordTableStyle"
        @sort-change="emit('sort-change', $event)"
        @expand-change="handleExpandChange"
      >
        <el-table-column
          v-if="hasExpand"
          type="expand"
          :width="expandColumnVisible ? 42 : 1"
          :fixed="expandColumnFixedLeft ? 'left' : false"
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
          :width="columnRenderWidths[column.key]"
          :min-width="effectiveColumnMinWidth(column)"
          :fixed="column.fixed"
          :align="recordTableBodyAlign(column)"
          header-align="center"
          :class-name="recordTableColumnClassName(column)"
          :show-overflow-tooltip="shouldShowRecordTableOverflowTooltip(column)"
        >
          <template #header>
            <SmartTableHeader
              :label="column.label"
              :lines="column.headerLines"
              :tooltip="column.headerTooltip"
              align="center"
            />
          </template>
          <template #default="{ row }">
            <slot
              v-if="$slots[`cell-${column.key}`]"
              :name="`cell-${column.key}`"
              :row="row"
              :value="row[column.key]"
            />
            <BaseRecordTableCell
              v-else
              :column="column"
              :value="row[column.key]"
              :align="recordTableBodyAlign(column)"
            />
          </template>
        </el-table-column>

        <el-table-column
          v-if="hasRowActions"
          label="操作"
          :width="rowActionsWidth"
          fixed="right"
          align="center"
          header-align="center"
        >
          <template #default="{ row }">
            <slot name="row-actions" :row="row" />
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="emptyDescription" />
        </template>
      </el-table>
      <Teleport to="body">
        <div
          v-show="isFloatingScrollbarVisible"
          ref="floatingScrollbarRef"
          class="record-table-floating-horizontal"
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
  padding: 12px;
  border: 1px solid rgba(220, 226, 235, 0.92);
  border-radius: 4px;
  background: #fff;
  box-shadow: none;
}

.record-context-panel {
  padding: 0 2px 4px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.record-condition-panel {
  min-width: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
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

@media (max-width: 1180px) {
  .record-filter-query-strip {
    grid-template-columns: 1fr;
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
  border-radius: 4px;
  background: #f8fafc;
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

.record-table-search--quick-keyword {
  width: 238px;
}

.record-table-search--quick-keyword :deep(.el-input__wrapper) {
  border: 0;
  background: #fff;
  box-shadow: 0 0 0 1px var(--el-color-warning-light-5) inset !important;
}

.record-table-search--quick-keyword :deep(.el-input__wrapper:hover),
.record-table-search--quick-keyword :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--el-color-warning) inset !important;
}

.record-table-search--quick-keyword :deep(.el-input__inner) {
  color: var(--el-text-color-regular);
}

.record-table-search--quick-keyword :deep(.el-input__prefix),
.record-table-search--quick-keyword :deep(.el-input__inner::placeholder) {
  color: var(--el-color-warning-light-5);
}

.record-filter-control--priority-warning {
  animation: record-filter-priority-pulse 0.96s ease-in-out infinite;
}

.record-filter-control--priority-warning :deep(.el-input__wrapper) {
  background: #fff1f2 !important;
  box-shadow:
    0 0 0 2px #f43f5e inset,
    0 0 0 3px rgba(244, 63, 94, 0.16) !important;
}

.record-filter-control--priority-warning :deep(.el-input__inner),
.record-filter-control--priority-warning :deep(.el-input__inner::placeholder) {
  color: #be123c !important;
}

.record-filter-control--priority-warning :deep(.el-input__prefix),
.record-filter-control--priority-warning :deep(.el-input__suffix) {
  color: #e11d48 !important;
}

@keyframes record-filter-priority-pulse {
  0%,
  100% {
    transform: translateY(0);
  }

  50% {
    transform: translateY(-1px);
    filter: saturate(1.2);
  }
}

.record-table-sort-label {
  color: #5f7388;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.record-page-sort-tag {
  max-width: 220px;
  justify-content: center;
}

.record-table-frame {
  position: relative;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow-x: hidden;
  overflow-y: hidden;
  border: 0;
  border-radius: 8px;
  background: transparent;
  outline: none;
  box-shadow: none;
}

.record-table {
  width: 100%;
  min-width: 100%;
  border: 1px solid #d7dee9 !important;
  border-radius: 8px;
  overflow: hidden;
  background: var(--platform-table-header-bg, #f8fafc);
}

.record-table::before,
.record-table::after {
  display: none !important;
}

.record-table :deep(.el-table__inner-wrapper),
.record-table :deep(.el-table__header-wrapper),
.record-table :deep(.el-table__body-wrapper),
.record-table :deep(.el-table__header),
.record-table :deep(.el-table__body) {
  width: 100% !important;
  min-width: 100%;
}

.record-table :deep(.cell) {
  min-width: 0;
}

.record-table :deep(.el-table__body .cell) {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding: 0 8px;
  text-align: center;
  overflow: hidden;
}

.record-table :deep(.el-table__body .cell > .el-tooltip) {
  display: flex !important;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
  text-align: center;
}

.record-table :deep(.el-table__body .cell:has(.record-table-tags)) {
  overflow: hidden;
  white-space: normal;
}

.record-table :deep(.el-table__body .cell:has(.record-table-tags) > .el-tooltip) {
  overflow: hidden;
  white-space: normal;
}

.record-table :deep(.el-table__body .cell:has(.record-table-tag)) {
  overflow: hidden;
  white-space: normal;
}

.record-table :deep(.el-table__body .cell:has(.record-table-tag) > .el-tooltip) {
  overflow: hidden;
  white-space: normal;
}

.record-table :deep(.el-table__body td.record-table-cell--left .cell) {
  justify-content: flex-start;
  text-align: left !important;
}

.record-table :deep(.el-table__body td.record-table-cell--left .cell > .el-tooltip) {
  justify-content: flex-start;
  text-align: left !important;
}

.record-table :deep(.el-table__expanded-cell) {
  padding: 0 !important;
  background: #fff;
}

.record-table-frame :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.record-table-floating-horizontal {
  position: fixed;
  z-index: 1900;
  height: 16px;
  padding: 5px 0;
  overflow: visible;
  pointer-events: auto;
  opacity: 1;
  background: transparent;
  box-shadow: none;
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
  background: #f6f8fb;
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

</style>
