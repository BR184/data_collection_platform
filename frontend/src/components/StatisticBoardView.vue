<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
// 统一统计板组件负责把查询条件、摘要卡片、图表和明细下钻串成同一套交互。
// 各业务看板只传入 boardKey 和配置，避免每个页面重复实现刷新、排序和规则说明。
import { ArrowDown, ArrowUp, Download, Sort } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import BaseStatisticTable from './base/BaseStatisticTable.vue';
import StatisticBoardDetailDialog from './StatisticBoardDetailDialog.vue';
import StatisticBoardRuleExplanationDrawer from './StatisticBoardRuleExplanationDrawer.vue';
import StatisticBoardToolbar from './StatisticBoardToolbar.vue';
import DataScopeBar from './data-scope/DataScopeBar.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import {
  type StatisticBoardResponse,
  type StatisticFilterField,
  type StatisticFilterOperator,
} from '../types/api';
import type { StatisticBoardToolbarAction, StatisticBoardUiHooks } from './statistic-board-ui';
import { useStatisticBoardDetail } from '../composables/useStatisticBoardDetail';
import { useStatisticBoardViewPrefs } from '../composables/useStatisticBoardViewPrefs';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { useRuleExplanationPanel } from '../composables/useRuleExplanationPanel';
import { useStatisticRoutePagination } from '../composables/useStatisticRoutePagination';
import { useStatisticBoardSortControls } from '../composables/useStatisticBoardSortControls';
import { useStatisticViewSettings } from '../composables/useStatisticViewSettings';
import { useStatisticBoardRouteController } from '../composables/useStatisticBoardRouteController';
import { refreshStatisticBoardRouteState } from '../composables/useStatisticBoardRouteRefresh';
import { useStatisticBoardData } from '../composables/useStatisticBoardData';
import { useStatisticBoardTableState } from '../composables/useStatisticBoardTableState';
import { useStatisticBoardRuleExplanationState } from '../composables/useStatisticBoardRuleExplanationState';
import { useStatisticBoardRefreshController } from '../composables/useStatisticBoardRefreshController';
import { useStatisticBoardSettingsActions } from '../composables/useStatisticBoardSettingsActions';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { useStatisticBoardTableAdapters } from '../composables/useStatisticBoardTableAdapters';
import { useDataScope } from '../composables/useDataScope';
import { useStatisticBoardDataScope } from '../composables/statistic-board-data-scopes';
import PageSettingsDialog from './PageSettingsDialog.vue';
import { usePageSavedViews } from '../composables/usePageSavedViews';
import { downloadBlob } from '../utils/csv-download';
import {
  type SortDirection,
} from './statistic-board-sorting';
import {
  createEmptyFilterGroup,
  createFilterConditionDraft,
  replaceFilterDraftGroup,
  resetFilterDraftGroup,
  normalizeFilterDraftGroup,
  sanitizeFilterDraftGroup,
  type StatisticFilterDraftGroup,
} from './statistic-board-filters';
import {
  buildFilterGroupFromRouteQuery,
} from './statistic-board-route-query';
import type { LocationQuery } from 'vue-router';
import { useStatisticBoardColumnDrag } from './useStatisticBoardColumnDrag';
import { createFallbackRuleExplanation } from './statistic-board-rule-explanation';
import type { StatisticBoardViewPrefs } from './statistic-board-view-prefs';
import type { RecordTableFilterField } from '../types/record-table';

const props = withDefaults(
  defineProps<{
    boardKey: string;
    uiHooks?: StatisticBoardUiHooks;
  }>(),
  {
    uiHooks: () => ({}),
  },
);

const route = useRoute();
const router = useRouter();
const canRefreshRealtime = computed(() => authState.currentUser.role === 'ADMIN');
const issueExportLoading = ref(false);
const customerIssueExportLoading = ref(false);
const horizontalComparisonExportLoading = ref(false);
const pageSettingsVisible = ref(false);
const pageScopeKey = computed(() => `stat-board:${props.boardKey}`);
const {
  autoRefreshOnEnter,
  setAutoRefreshOnEnter,
} = usePageAutoRefreshPreference(() => pageScopeKey.value);
const dataScopeConfig = useStatisticBoardDataScope(computed(() => props.boardKey));

const dataScope = useDataScope({
  provider: computed(() => dataScopeConfig.value?.provider ?? null),
  options: computed(() => dataScopeConfig.value?.options.value ?? []),
  clearQueryKeysOnChange: [
    'tablePage',
    'detailPage',
    'detailPageSize',
    'detailSortBy',
    'detailSortOrder',
    'detailVisible',
    'detailRowKey',
    'detailColumnKey',
  ],
  extraPatchOnChange: () => ({
    tablePage: '1',
  }),
  mountToShell: false,
  loading: computed(() => dataScopeConfig.value?.loading.value ?? false),
});
const currentDataScopeProvider = computed(() => dataScope.provider.value);
const currentDataScopeOptions = computed(() => dataScope.options.value);
const currentDataScopeValue = computed(() => dataScope.value.value);
const currentDataScopeSummary = computed(() =>
  dataScope.summary.value ? `${dataScope.summary.value.label}：${dataScope.summary.value.value}` : '',
);
const currentDataScopeLoading = computed(() => dataScopeConfig.value?.loading.value ?? false);
const toolbarBoardTitle = computed(() => '');
const routeScopeReady = computed(() => {
  const provider = dataScopeConfig.value?.provider;
  if (!provider || provider.defaultStrategy !== 'first-available') {
    return true;
  }
  const options = dataScopeConfig.value?.options.value ?? [];
  if (!options.length) {
    return Boolean(dataScopeConfig.value?.loaded.value);
  }
  return String(route.query[provider.queryKey] ?? '').trim() !== '';
});

const filterDraft = reactive<StatisticFilterDraftGroup>(createEmptyFilterGroup());
const {
  replaceRouteQuery,
  applyFiltersToRoute: applyFilterDraftToRoute,
  resetFilters,
} = useStatisticBoardRouteController({
  getRouteQuery: () => route.query,
  getRoutePath: () => route.path,
  getRouteHash: () => route.hash,
  replaceRoute: (location) => router.replace(location),
  resetFilterDraft: () => resetFilterDraftGroup(filterDraft),
});
const {
  boardViewPrefs,
  applyStoredViewPrefs,
  persistViewPrefs,
  saveVisibleColumnPrefs,
  restoreDefaultViewPrefs,
  clearCurrentSort,
  updateWidthStrategy,
} = useStatisticBoardViewPrefs({
  boardKey: () => props.boardKey,
  routeQuery: () => route.query,
  replaceRouteQuery,
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
});

const {
  loading,
  board,
  errorMessage,
  loadBoard,
  exportBoard,
} = useStatisticBoardData({
  boardKey: () => props.boardKey,
  getFilterGroup: buildFilterPayload,
  loadBoardData: (boardKey, request) => api.getStatisticBoard(boardKey, request),
  exportBoardFile: (boardKey, request) => api.exportStatisticBoardFile(boardKey, request),
  onBoardLoaded: handleBoardLoaded,
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
  downloadFile: downloadBlob,
});

const extraToolbarActions = computed<StatisticBoardToolbarAction[]>(() => {
  if (props.boardKey === 'customer-issue-defect-summary') {
    return [
      {
        key: 'export-customer-issues',
        label: '下载议题数据',
        icon: Download,
        actionClass: 'app-action-button--export',
        loading: customerIssueExportLoading.value,
        plain: true,
      },
    ];
  }
  if (props.boardKey !== 'system-test-defect-summary') {
    return [];
  }
  return [
    {
      key: 'export-system-test-issues',
      label: '下载议题数据',
      icon: Download,
      actionClass: 'app-action-button--export',
      loading: issueExportLoading.value,
      plain: true,
    },
    {
      key: 'export-system-test-horizontal-comparison',
      label: '系统测试横向对比excel下载',
      icon: Download,
      actionClass: 'app-action-button--export',
      loading: horizontalComparisonExportLoading.value,
      plain: true,
    },
  ];
});

const primaryExportLabel = computed(() => {
  const labels: Record<string, string> = {
    'system-test-defect-summary': '下载当前',
    'system-test-defect-cause': '下载',
    'system-test-delay-analysis': '导出数据',
    'customer-issue-defect-summary': '下载当前',
    'customer-issue-defect-cause': '下载',
  };
  return labels[props.boardKey] ?? '导出';
});

const showPrimaryExport = computed(() => props.boardKey !== 'system-test-phase-statistics');

const {
  currentSortColumn,
  currentSortSummary,
  sortDirectionForColumn,
  toggleColumnSort,
  sortStateLabel,
} = useStatisticBoardSortControls({
  board,
  boardViewPrefs,
  persistViewPrefs,
  replaceRouteQuery,
});

const {
  tableCurrentPage,
  tablePageSize,
  pageSizeOptions,
  syncFromRoute: syncTablePaginationFromRoute,
  handleTableCurrentChange,
  handleTableSizeChange,
  clampPageWithinBounds,
} = useStatisticRoutePagination(props.boardKey);
const {
  activeFilterFields,
  orderedColumnGroups,
  sortedRows,
  totalTableRows,
  paginatedRows,
  tableRenderKey,
  rowHeaderLabel,
  firstColumnWidth,
  firstColumnMinWidth,
} = useStatisticBoardTableState({
  board,
  boardViewPrefs,
  tableCurrentPage,
  tablePageSize,
  boardKey: () => props.boardKey,
});
const quickFilterFieldOrder = [
  'keyword',
  'moduleName',
  'reasonCategory',
  'illegalReason',
  'severityLevel',
  'priorityLevel',
  'bugStatus',
  'category',
  'issueState',
  'authorName',
  'assigneeName',
  'title',
  'issueIid',
];
const quickFilterFields = computed<RecordTableFilterField[]>(() => {
  const scopeQueryKey = currentDataScopeProvider.value?.queryKey ?? '';
  return activeFilterFields.value
    .filter((field) => field.key !== scopeQueryKey)
    .filter((field) => quickFilterFieldOrder.includes(field.key))
    .filter((field) => field.type === 'text' || field.type === 'select')
    .sort((left, right) => quickFilterFieldOrder.indexOf(left.key) - quickFilterFieldOrder.indexOf(right.key))
    .map(toRecordQuickFilterField);
});
const quickFilterValues = computed<Record<string, unknown>>(() =>
  Object.fromEntries(quickFilterFields.value.map((field) => [field.key, quickFilterValue(field.key)])),
);
const quickFilterInputDrafts = computed<Record<string, string>>(() =>
  Object.fromEntries(
    quickFilterFields.value
      .filter((field) => field.type === 'input')
      .map((field) => [field.key, String(quickFilterValues.value[field.key] ?? '')]),
  ),
);
const {
  settingsVisible,
  draftVisibleColumnKeys,
  expandedViewSettingGroups,
  allColumnKeys,
  currentVisibleColumnCount,
  allColumnsSelected,
  partiallySelectedColumns,
  groupCheckAllStates,
  groupIndeterminateStates,
  syncDraftFromVisible,
  openSettings,
  closeSettings,
  toggleAllColumns,
  toggleGroupColumns,
  isColumnSelected,
  toggleColumnSelection,
  handleExpandedViewSettingGroupsChange,
} = useStatisticViewSettings(
  computed(() => board.value?.definition.columnGroups ?? []),
  computed(() => boardViewPrefs.value.visibleColumnKeys),
);
const {
  onGroupDragStart,
  isGroupDragging,
  onGroupDrop,
  onColumnDragStart,
  isColumnDragging,
  onColumnDrop,
  clearDragState,
} = useStatisticBoardColumnDrag(boardViewPrefs, persistViewPrefs);

function handleBoardLoaded(response: StatisticBoardResponse) {
  const routeFilterGroup = buildFilterGroupFromRouteQuery(route.query);
  const nextDraft = normalizeFilterDraftGroup(
    stripRouteScopeFilter(response.appliedFilterGroup ?? routeFilterGroup),
    response.definition.filters,
  );
  replaceFilterDraftGroup(filterDraft, nextDraft);
  applyStoredViewPrefs(response.definition);
  syncDraftFromVisible();
  syncDetailPaginationFromRoute(route.query, response.definition.defaultPageSize ?? 10);
}

function buildFilterPayload() {
  return mergeRouteScopeFilter(sanitizeFilterDraftGroup(filterDraft));
}

function mergeRouteScopeFilter(filterGroup: ReturnType<typeof sanitizeFilterDraftGroup>) {
  const provider = dataScopeConfig.value?.provider;
  if (!provider) {
    return filterGroup;
  }
  const scopeValue = String(route.query[provider.queryKey] ?? '').trim();
  if (!scopeValue) {
    return filterGroup;
  }
  const conditions = [
    ...(filterGroup?.conditions ?? []).filter((condition) => condition.fieldKey !== provider.queryKey),
    {
      fieldKey: provider.queryKey,
      operator: 'eq' as const,
      value: scopeValue,
      secondaryValue: '',
    },
  ];
  return {
    logic: filterGroup?.logic ?? 'AND' as const,
    conditions,
  };
}

function stripRouteScopeFilter(filterGroup: ReturnType<typeof sanitizeFilterDraftGroup>) {
  const provider = dataScopeConfig.value?.provider;
  if (!filterGroup || !provider) {
    return filterGroup;
  }
  return {
    ...filterGroup,
    conditions: filterGroup.conditions.filter((condition) => condition.fieldKey !== provider.queryKey),
  };
}

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  openRuleExplanation,
  handleRuleExplanationVisibleChange,
  resetRuleExplanation,
} = useRuleExplanationPanel({
  load: () =>
    api.getStatisticBoardRuleExplanation(props.boardKey, {
      filterGroup: buildFilterPayload(),
    }),
  fallback: (reason) => createFallbackRuleExplanation(props.boardKey, reason),
  warn: (message) => ElMessage.warning(message),
  unsupportedWarning: '当前统计表暂不支持规则说明',
});
const {
  ruleExplanationSteps,
  ruleExplanationMetrics,
  ruleExclusionSteps,
  ruleFirstInputCount,
  ruleFinalOutputCount,
  ruleFinalRetainedRate,
  qaFriendlyRuleSummary,
} = useStatisticBoardRuleExplanationState(ruleExplanation);

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getStatisticBoardRealtimeStatus(props.boardKey, {
    filterGroup: buildFilterPayload(),
  }),
  emptyText: '暂无同步记录',
});

const {
  detailLoading,
  detailVisible,
  detail,
  detailPagination,
  detailQuickFilterValues,
  detailQuickFilterInputDrafts,
  detailCellValue,
  loadDetail,
  openDetail: openStatisticDetail,
  handleDetailSortChange,
  handleDetailCurrentChange,
  handleDetailSizeChange,
  handleDetailQuickFilterInputUpdate,
  handleDetailQuickFilterChange,
  resetDetailQuickFilters,
  handleDetailVisibleChange,
  syncFromRoute: syncDetailFromRoute,
  syncPaginationFromRoute: syncDetailPaginationFromRoute,
} = useStatisticBoardDetail({
  boardKey: () => props.boardKey,
  getFilterGroup: buildFilterPayload,
  loadDetails: (boardKey, params) => api.getStatisticBoardDetails(boardKey, params),
  notifyError: (message) => ElMessage.error(message),
  replaceRouteQuery,
});

const {
  autoRefreshBoard,
  refreshBoard,
} = useStatisticBoardRefreshController({
  loading,
  detailVisible,
  loadBoard,
  loadDetail,
  requestRealtimeRefresh: () => api.refreshStatisticBoardRealtime(props.boardKey),
  loadRealtimeStatus,
  notifySuccess: (message) => ElMessage.success(message),
});

const {
  handleSettingsCommand,
  saveViewPrefs,
  restoreDefaultView,
} = useStatisticBoardSettingsActions({
  board,
  draftVisibleColumnKeys,
  openSettings,
  openSavedViews: () => {
    pageSettingsVisible.value = true;
    loadSavedViews();
  },
  closeSettings,
  clearCurrentSort,
  syncDraftFromVisible,
  saveVisibleColumnPrefs,
  restoreDefaultViewPrefs,
});

const {
  savedViews,
  loadSavedViews,
  saveCurrentView,
  applySavedView,
  deleteSavedView,
} = usePageSavedViews({
  scopeKey: () => pageScopeKey.value,
  captureViewPrefs: captureStatisticBoardViewPrefs,
  applyViewPrefs: applyStatisticBoardViewPrefs,
  afterApply: () => {
    pageSettingsVisible.value = false;
  },
});

const {
  openDetail,
  cellForColumn,
  columnMinWidth,
  columnResizable,
} = useStatisticBoardTableAdapters({
  board,
  boardViewPrefs,
  openStatisticDetail,
});

const PRESENTATION_QUERY_KEYS = new Set([
  'sortBy',
  'sortOrder',
  'tablePage',
  'tablePageSize',
  'detailPage',
  'detailPageSize',
  'detailSortBy',
  'detailSortOrder',
  'detailVisible',
  'detailRowKey',
  'detailColumnKey',
]);

function sortIconForDirection(direction: SortDirection) {
  if (direction === 'asc') {
    return ArrowUp;
  }
  if (direction === 'desc') {
    return ArrowDown;
  }
  return Sort;
}

function captureStatisticBoardViewPrefs() {
  return {
    ...boardViewPrefs.value,
  };
}

async function applyStatisticBoardViewPrefs(viewPrefs: unknown) {
  if (!viewPrefs || typeof viewPrefs !== 'object') {
    return;
  }
  boardViewPrefs.value = {
    ...boardViewPrefs.value,
    ...(viewPrefs as Partial<StatisticBoardViewPrefs>),
  };
  persistViewPrefs();
  syncDraftFromVisible();
}

function toRecordQuickFilterField(field: StatisticFilterField): RecordTableFilterField {
  if (field.type === 'select' || isPersonQuickFilterField(field)) {
    return {
      key: field.key,
      label: field.label,
      type: 'select',
      placeholder: `全部${field.label}`,
      width: field.width ?? quickFilterWidth(field.key, field.label),
      options: field.options ?? [],
    };
  }
  return {
    key: field.key,
    label: field.label,
    type: 'input',
    placeholder: field.key === 'keyword' ? '输入任意关键字搜索' : `输入${field.label}`,
    width: field.key === 'keyword' ? 260 : field.width ?? quickFilterWidth(field.key, field.label),
  };
}

function isPersonQuickFilterField(field: StatisticFilterField) {
  const normalizedKey = field.key.replace(/[-_]/g, '').toLowerCase();
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
    || /创建人|提交人|处理人|负责人|责任人|合并人|走查人|被走查人|评审人|评审专家|作者|审核人|指派人|用户/.test(field.label);
}

function quickFilterWidth(key: string, label: string) {
  if (key === 'keyword') {
    return 260;
  }
  if (key === 'title') {
    return 220;
  }
  if (key === 'bugStatus' || key === 'reasonCategory' || key === 'illegalReason') {
    return 220;
  }
  return Math.max(144, Math.min(200, label.length * 24 + 64));
}

function quickFilterValue(fieldKey: string) {
  const condition = filterDraft.conditions.find((item) => item.fieldKey === fieldKey && item.valueType !== 'LABEL_GROUP');
  return condition?.value ?? '';
}

function quickFilterOperator(field: StatisticFilterField): StatisticFilterOperator {
  if (field.type === 'select' && field.operators.includes('eq')) {
    return 'eq';
  }
  if (field.operators.includes('contains')) {
    return 'contains';
  }
  return field.operators[0] ?? 'eq';
}

function updateQuickFilterCondition(payload: { key: string; value: string | string[] | null }) {
  const field = activeFilterFields.value.find((item) => item.key === payload.key);
  if (!field) {
    return;
  }
  const normalizedValue = Array.isArray(payload.value)
    ? payload.value.filter(Boolean).join(',')
    : String(payload.value ?? '').trim();
  filterDraft.conditions.splice(
    0,
    filterDraft.conditions.length,
    ...filterDraft.conditions.filter((condition) => condition.fieldKey !== payload.key),
  );
  if (!normalizedValue) {
    return;
  }
  const draft = createFilterConditionDraft(field);
  draft.operator = quickFilterOperator(field);
  draft.value = normalizedValue;
  draft.secondaryValue = '';
  filterDraft.conditions.push(draft);
}

function updateQuickFilterInput(payload: { key: string; value: string }) {
  updateQuickFilterCondition(payload);
}

async function applyFiltersToRoute() {
  await applyFilterDraftToRoute(filterDraft);
}

async function handleExtraAction(actionKey: string) {
  if (actionKey === 'export-customer-issues') {
    await exportCustomerIssues();
    return;
  }
  if (actionKey === 'export-system-test-issues') {
    await exportSystemTestIssues();
    return;
  }
  if (actionKey === 'export-system-test-horizontal-comparison') {
    await exportSystemTestHorizontalComparison();
  }
}

async function exportCustomerIssues() {
  customerIssueExportLoading.value = true;
  try {
    const workbook = await api.exportCustomerIssueRecords({
      topic: 'cc-product',
      filterGroup: buildFilterPayload(),
    });
    downloadBlob(workbook, customerIssueSummaryIssueExportFilename());
    ElMessage.success('议题数据导出成功');
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    customerIssueExportLoading.value = false;
  }
}

function customerIssueSummaryIssueExportFilename() {
  const milestone = String(route.query.milestoneTitle ?? '').trim();
  return milestone ? `${milestone}-客户问题全量议题数据.xlsx` : '客户问题全量议题数据.xlsx';
}

async function exportSystemTestIssues() {
  issueExportLoading.value = true;
  try {
    const file = await api.exportSystemTestDefectSummaryIssues({
      filterGroup: buildFilterPayload(),
    });
    downloadBlob(file.blob, file.filename || '议题数据.xlsx');
    ElMessage.success('议题数据导出成功');
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    issueExportLoading.value = false;
  }
}

async function exportSystemTestHorizontalComparison() {
  horizontalComparisonExportLoading.value = true;
  try {
    const file = await api.exportSystemTestHorizontalComparison({
      filterGroup: buildFilterPayload(),
    });
    downloadBlob(file.blob, file.filename || '系统测试数据分析表.xlsx');
    ElMessage.success('横向对比导出成功');
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    horizontalComparisonExportLoading.value = false;
  }
}

watch(
  [sortedRows, tablePageSize],
  () => {
    clampPageWithinBounds(totalTableRows.value);
  },
  { immediate: true },
);

watch(
  () => [route.query, routeScopeReady.value] as const,
  async (nextQuery, previousQuery) => {
    if (!routeScopeReady.value) {
      return;
    }
    const nextRouteQuery = nextQuery[0];
    const previousRouteQuery = previousQuery?.[0];
    if (previousRouteQuery && hasOnlyPresentationQueryChanges(nextRouteQuery, previousRouteQuery)) {
      syncTablePaginationFromRoute();
      syncDetailFromRoute(route.query, board.value?.rows ?? [], board.value?.definition.defaultPageSize ?? 10);
      return;
    }
    resetRuleExplanation();
    await refreshStatisticBoardRouteState({
      setLoading: (nextLoading) => {
        loading.value = nextLoading;
      },
      syncTablePaginationFromRoute,
      loadBoard,
      loadRealtimeStatus,
      syncDetailFromRoute: () =>
        syncDetailFromRoute(route.query, board.value?.rows ?? [], board.value?.definition.defaultPageSize ?? 10),
    });
  },
  { immediate: true, deep: true },
);

function hasOnlyPresentationQueryChanges(nextQuery: LocationQuery, previousQuery: LocationQuery) {
  const allKeys = new Set([...Object.keys(nextQuery), ...Object.keys(previousQuery)]);
  let changed = false;
  for (const key of allKeys) {
    if (queryValueSignature(nextQuery[key]) === queryValueSignature(previousQuery[key])) {
      continue;
    }
    changed = true;
    if (!PRESENTATION_QUERY_KEYS.has(key)) {
      return false;
    }
  }
  return changed;
}

function queryValueSignature(value: LocationQuery[string]) {
  return Array.isArray(value) ? value.join('\u0000') : String(value ?? '');
}

onMounted(() => {
  void autoRefreshPageData();
});

async function autoRefreshPageData() {
  if (!autoRefreshOnEnter.value || !canRefreshRealtime.value) {
    return;
  }
  await waitForInitialBoardLoad();
  if (!canRefreshRealtime.value) {
    return;
  }
  const markerKey = autoRefreshMarkerKey();
  if (window.sessionStorage.getItem(markerKey) === 'true') {
    return;
  }
  await autoRefreshBoard();
  window.sessionStorage.setItem(markerKey, 'true');
}

async function waitForInitialBoardLoad() {
  if (!loading.value) {
    return;
  }
  await new Promise<void>((resolve) => {
    const stop = watch(
      loading,
      (nextLoading) => {
        if (!nextLoading) {
          stop();
          resolve();
        }
      },
      { flush: 'post' },
    );
  });
}

function autoRefreshMarkerKey() {
  const scopeParts = ['testingPhase', 'projectName', 'milestoneTitle', 'sourceInstance', 'filterGroup']
    .map((key) => `${key}=${String(route.query[key] ?? '')}`)
    .join('&');
  return `platform:auto-refreshed:${props.boardKey}:${scopeParts}`;
}
</script>

<template>
  <div class="stat-board" :class="props.uiHooks.rootClass">
      <el-card shadow="never" class="stat-board-card" :class="props.uiHooks.cardClass" v-loading="loading">
      <div class="stat-board-query-shell">
        <StatisticBoardToolbar
          :filter-draft="filterDraft"
          :active-filter-fields="activeFilterFields"
          :board-title="toolbarBoardTitle"
          :last-synced-text="lastSyncedText"
          :rule-explanation-loading="ruleExplanationLoading"
          :realtime-status="syncStatus"
          :can-refresh-realtime="canRefreshRealtime"
          :auto-refresh-on-enter="autoRefreshOnEnter"
          :export-label="primaryExportLabel"
          :show-export="showPrimaryExport"
          :quick-filter-fields="quickFilterFields"
          :quick-filter-values="quickFilterValues"
          :quick-filter-input-drafts="quickFilterInputDrafts"
          :extra-actions="extraToolbarActions"
          :ui-hooks="props.uiHooks"
          @apply-filters="applyFiltersToRoute"
          @reset-filters="resetFilters"
          @quick-filter-change="updateQuickFilterCondition"
          @quick-filter-input-update="updateQuickFilterInput"
          @refresh-board="refreshBoard"
          @open-rule-explanation="openRuleExplanation"
          @export-board="exportBoard"
          @extra-action="handleExtraAction"
          @settings-command="handleSettingsCommand"
          @toggle-auto-refresh="setAutoRefreshOnEnter"
        >
          <template v-if="currentDataScopeProvider" #scope>
            <DataScopeBar
              :provider="currentDataScopeProvider"
              :options="currentDataScopeOptions"
              :model-value="currentDataScopeValue"
              :summary="currentDataScopeSummary"
              :loading="currentDataScopeLoading"
              :show-label="false"
              :show-summary="false"
              class="stat-board-scope-bar"
              @change="dataScope.setValue"
            />
          </template>
        </StatisticBoardToolbar>
      </div>

      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="stat-board-alert"
      />

      <BaseStatisticTable
        :board="board"
        :ui-hooks="props.uiHooks"
        :table-render-key="tableRenderKey"
        :paginated-rows="paginatedRows"
        :sorted-rows-length="sortedRows.length"
        :total-table-rows="totalTableRows"
        :row-header-label="rowHeaderLabel"
        :ordered-column-groups="orderedColumnGroups"
        :current-sort-summary="currentSortColumn ? currentSortSummary : ''"
        :first-column-width="firstColumnWidth"
        :first-column-min-width="firstColumnMinWidth"
        :page-size-options="pageSizeOptions"
        :table-current-page="tableCurrentPage"
        :table-page-size="tablePageSize"
        :settings-visible="settingsVisible"
        :width-strategy="boardViewPrefs.widthStrategy"
        :current-visible-column-count="currentVisibleColumnCount"
        :all-columns-selected="allColumnsSelected"
        :partially-selected-columns="partiallySelectedColumns"
        :draft-visible-column-keys-count="draftVisibleColumnKeys.length"
        :all-column-keys-count="allColumnKeys.length"
        :expanded-view-setting-groups="expandedViewSettingGroups"
        :on-expanded-view-setting-groups-change="handleExpandedViewSettingGroupsChange"
        :group-check-all-states="groupCheckAllStates"
        :group-indeterminate-states="groupIndeterminateStates"
        :sort-direction-for-column="sortDirectionForColumn"
        :sort-state-label="sortStateLabel"
        :sort-icon-for-direction="sortIconForDirection"
        :toggle-column-sort="toggleColumnSort"
        :cell-for-column="cellForColumn"
        :open-detail="openDetail"
        :column-min-width="columnMinWidth"
        :column-resizable="columnResizable"
        :is-group-dragging="isGroupDragging"
        :on-group-drag-start="onGroupDragStart"
        :on-group-drop="onGroupDrop"
        :is-column-dragging="isColumnDragging"
        :on-column-drag-start="onColumnDragStart"
        :on-column-drop="onColumnDrop"
        :clear-drag-state="clearDragState"
        :handle-table-current-change="handleTableCurrentChange"
        :handle-table-size-change="handleTableSizeChange"
        :on-settings-visible-change="(visible) => (visible ? openSettings() : closeSettings())"
        :on-width-strategy-change="updateWidthStrategy"
        :on-save-view-prefs="saveViewPrefs"
        :on-restore-default-view="restoreDefaultView"
        :toggle-all-columns="toggleAllColumns"
        :toggle-group-columns="toggleGroupColumns"
        :is-column-selected="isColumnSelected"
        :toggle-column-selection="toggleColumnSelection"
      />
      </el-card>

    <StatisticBoardRuleExplanationDrawer
      :model-value="ruleExplanationVisible"
      :loading="ruleExplanationLoading"
      :explanation="ruleExplanation"
      :steps="ruleExplanationSteps"
      :metrics="ruleExplanationMetrics"
      :exclusion-steps="ruleExclusionSteps"
      :first-input-count="ruleFirstInputCount"
      :final-output-count="ruleFinalOutputCount"
      :final-retained-rate="ruleFinalRetainedRate"
      :qa-friendly-summary="qaFriendlyRuleSummary"
      @update:model-value="handleRuleExplanationVisibleChange"
    />

    <StatisticBoardDetailDialog
      :model-value="detailVisible"
      :loading="detailLoading"
      :detail="detail"
      :pagination="detailPagination"
      :quick-filter-values="detailQuickFilterValues"
      :quick-filter-input-drafts="detailQuickFilterInputDrafts"
      :detail-table-class="props.uiHooks.detailTableClass"
      :detail-cell-value="detailCellValue"
      :on-sort-change="handleDetailSortChange"
      :on-current-change="handleDetailCurrentChange"
      :on-size-change="handleDetailSizeChange"
      :on-quick-filter-input-update="handleDetailQuickFilterInputUpdate"
      :on-quick-filter-change="handleDetailQuickFilterChange"
      :on-reset-quick-filters="resetDetailQuickFilters"
      @update:model-value="handleDetailVisibleChange"
    />

    <PageSettingsDialog
      v-model="pageSettingsVisible"
      title="页面设置"
      :auto-refresh-enabled="autoRefreshOnEnter"
      :saved-views="savedViews"
      @toggle-auto-refresh="setAutoRefreshOnEnter"
      @save-view="saveCurrentView"
      @apply-view="applySavedView"
      @delete-view="deleteSavedView"
    />

  </div>
</template>
