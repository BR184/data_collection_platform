<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
// 统一统计板组件负责把查询条件、摘要卡片、图表和明细下钻串成同一套交互。
// 各业务看板只传入 boardKey 和配置，避免每个页面重复实现刷新、排序和规则说明。
import { ArrowDown, ArrowUp, Download, Sort } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import BaseStatisticTable from './base/BaseStatisticTable.vue';
import StatisticBoardDetailDialog from './StatisticBoardDetailDialog.vue';
import StatisticBoardRuleExplanationDrawer from './StatisticBoardRuleExplanationDrawer.vue';
import StatisticBoardToolbar from './StatisticBoardToolbar.vue';
import StatisticBoardFreshnessBanner from './StatisticBoardFreshnessBanner.vue';
import StatisticBoardControlBar from './StatisticBoardControlBar.vue';
import DataScopeBar from './data-scope/DataScopeBar.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
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
import { useRealtimeRefreshLifecycle } from '../composables/useRealtimeRefreshLifecycle';
import { useStatisticBoardSettingsActions } from '../composables/useStatisticBoardSettingsActions';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { useStatisticBoardTableAdapters } from '../composables/useStatisticBoardTableAdapters';
import { useDataScope } from '../composables/useDataScope';
import { useStatisticBoardDataScope } from '../composables/statistic-board-data-scopes';
import { useStatisticBoardControlParams } from '../composables/useStatisticBoardControlParams';
import type { PageKey } from '../feature-manifest/types';
import PageSettingsDialog from './PageSettingsDialog.vue';
import { usePageSavedViews } from '../composables/usePageSavedViews';
import { downloadBlob } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';
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
import { resolveStatisticBoardRouteScopeState } from './statistic-board-route-scope';
import type { StatisticBoardViewPrefs } from './statistic-board-view-prefs';
import type { RecordTableFilterField } from '../types/record-table';
import {
  filterFieldsByBlockedDimensions,
  stripLowerPriorityQuickConditions,
  useQuickFilterConflictState,
} from './filter-priority';

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
const canRefreshRealtime = computed(() => hasPermission(authState.currentUser, 'business_data.refresh'));
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
const routeScopeState = computed(() =>
  resolveStatisticBoardRouteScopeState(
    dataScopeConfig.value?.provider,
    dataScopeConfig.value?.options.value ?? [],
    Boolean(dataScopeConfig.value?.loaded.value),
    dataScopeConfig.value?.provider
      ? route.query[dataScopeConfig.value.provider.queryKey]
      : undefined,
  ),
);
const routeScopeReady = computed(() => routeScopeState.value.ready);
const scopeCatalogMissing = computed(() => routeScopeState.value.catalogMissing);

const filterDraft = reactive<StatisticFilterDraftGroup>(createEmptyFilterGroup());
const {
  replaceRouteQuery,
  applyFiltersToRoute: applyFilterDraftToRoute,
  resetFilters: resetRouteFilters,
} = useStatisticBoardRouteController({
  getRouteQuery: () => route.query,
  getRoutePath: () => route.path,
  getRouteHash: () => route.hash,
  replaceRoute: (location) => router.replace(location),
  resetFilterDraft: () => resetFilterDraftGroup(filterDraft),
});
// 行维度与成员选择是控制参数，按页面配置启用；未配置的看板不渲染任何控件。
const {
  controlSpec,
  activeDimension,
  memberControls,
  requestParams: controlRequestParams,
  fixedColumnKeys,
  controlOptionsStatus,
  controlOptionsMessage,
  memberSelectionError,
  applyBoardSourceVersion,
  applyResponse: applyControlResponse,
  setDimension: setControlDimension,
  setMember: setControlMember,
} = useStatisticBoardControlParams({
  pageKey: () => route.meta.pageKey as PageKey | undefined,
  routeQuery: () => route.query,
  replaceRouteQuery,
  // 候选与主表消费同一份范围参数（生效范围 + 用户条件），但不带成员选择：
  // 后端在完整基础范围上求解，选择成员后其它成员依然可选。
  loadControlOptions: (request) => api.getStatisticBoardControlOptions(props.boardKey, request),
  controlScopeParams: () => ({ filterGroup: buildFilterPayload() }),
  scopeSignature: () => JSON.stringify(buildFilterPayload() ?? null),
});
const {
  boardViewPrefs,
  applyStoredViewPrefs,
  persistViewPrefs,
  saveVisibleColumnPrefs,
  restoreDefaultViewPrefs,
  clearCurrentSort,
  updateWidthStrategy,
  updateStickyHeaderEnabled,
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
  invalidateBoardRequest,
  exportBoard,
} = useStatisticBoardData({
  boardKey: () => props.boardKey,
  getFilterGroup: buildFilterPayload,
  getControlParams: () => controlRequestParams.value,
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
const standaloneFilterKeys = computed(() =>
  currentDataScopeProvider.value ? [currentDataScopeProvider.value.queryKey] : [],
);
const conditionFilterFields = computed(() =>
  filterFieldsByBlockedDimensions(activeFilterFields.value, standaloneFilterKeys.value),
);
const baseQuickFilterFields = computed<RecordTableFilterField[]>(() => {
  const scopeQueryKey = currentDataScopeProvider.value?.queryKey ?? '';
  return activeFilterFields.value
    .filter((field) => field.key !== scopeQueryKey)
    .filter((field) => quickFilterFieldOrder.includes(field.key))
    .filter((field) => field.type === 'text' || field.type === 'select')
    .sort((left, right) => quickFilterFieldOrder.indexOf(left.key) - quickFilterFieldOrder.indexOf(right.key))
    .map(toRecordQuickFilterField);
});
const quickFilterValues = computed<Record<string, unknown>>(() =>
  Object.fromEntries(baseQuickFilterFields.value.map((field) => [field.key, quickFilterValue(field.key)])),
);
const quickFilterInputDrafts = computed<Record<string, string>>(() =>
  Object.fromEntries(
    baseQuickFilterFields.value
      .filter((field) => field.type === 'input')
      .map((field) => [field.key, String(quickFilterValues.value[field.key] ?? '')]),
  ),
);
const quickFilterConflict = useQuickFilterConflictState({
  quickFilters: baseQuickFilterFields,
  quickValues: quickFilterValues,
  filterDraft,
});
const quickFilterFields = computed(() => quickFilterConflict.visibleQuickFilters.value);
const disabledQuickFilterKeys = computed(() => quickFilterConflict.disabledQuickFilterKeys.value);
const highlightedQuickFilterKeys = computed(() => quickFilterConflict.highlightedQuickFilterKeys.value);
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
  applyControlResponse(response);
  applyBoardSourceVersion(boardSourceVersion(response));
  const routeFilterGroup = buildFilterGroupFromRouteQuery(route.query);
  const appliedFilterGroup = stripRouteScopeFilter(response.appliedFilterGroup ?? null);
  const nextDraft = normalizeFilterDraftGroup(
    routeFilterGroup.conditions.length ? routeFilterGroup : appliedFilterGroup,
    response.definition.filters,
  );
  replaceFilterDraftGroup(filterDraft, nextDraft);
  applyStoredViewPrefs(response.definition);
  syncDraftFromVisible();
  syncDetailPaginationFromRoute(route.query, response.definition.defaultPageSize ?? 10);
}

/** 主表来源代际：下钻契约把它写进每个指标单元格，候选按同一代际判定是否需要重取。 */
function boardSourceVersion(response: StatisticBoardResponse): string {
  for (const row of response.rows ?? []) {
    for (const cell of row.cells ?? []) {
      const version = cell.detailParams?.sourceVersion;
      if (version) {
        return version;
      }
    }
  }
  return '';
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
  loadRuleExplanation,
  openRuleExplanation,
  handleRuleExplanationVisibleChange,
  resetRuleExplanation,
} = useRuleExplanationPanel({
  load: () =>
    api.getStatisticBoardRuleExplanation(props.boardKey, {
      filterGroup: buildFilterPayload(),
      filters: controlRequestParams.value,
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

function ensureRuleExplanationLoaded() {
  if (!ruleExplanation.value && !ruleExplanationLoading.value) {
    void loadRuleExplanation();
  }
}

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus,
  invalidateRealtimeStatusRequest,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getStatisticBoardRealtimeStatus(props.boardKey, {
    filterGroup: buildFilterPayload(),
    filters: controlRequestParams.value,
  }),
  emptyText: '暂无同步记录',
});

const {
  detailLoading,
  detailVisible,
  detail,
  detailPagination,
  detailCollection,
  detailQuickFilterValues,
  detailQuickFilterInputDrafts,
  detailCellValue,
  loadDetail,
  selectDetailCollection,
  openDetail: openStatisticDetail,
  handleDetailSortChange,
  handleDetailCurrentChange,
  handleDetailSizeChange,
  handleDetailQuickFilterInputUpdate,
  handleDetailQuickFilterChange,
  resetDetailQuickFilters,
  handleDetailVisibleChange,
  invalidateForRouteChange: invalidateDetailForRouteChange,
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
  refreshButtonBusy,
  submitRefresh: refreshBoard,
  autoRefresh: autoRefreshBoard,
  invalidateRefreshTracking,
} = useRealtimeRefreshLifecycle({
  submitRefresh: () => api.refreshStatisticBoardRealtime(props.boardKey),
  loadStatus: loadRealtimeStatus,
  loadData: async () => {
    await loadBoard();
    if (detailVisible.value) {
      await loadDetail();
    }
  },
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyError: (message) => ElMessage.error(message),
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
  // 集合切换只影响明细请求，不应触发整表重载。
  'detailCollection',
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
  const condition = filterDraft.conditions.find((item) =>
    item.source === 'QUICK' && item.fieldKey === fieldKey && item.valueType !== 'LABEL_GROUP',
  );
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
    ...filterDraft.conditions.filter((condition) => condition.source !== 'QUICK' || condition.fieldKey !== payload.key),
  );
  if (!normalizedValue) {
    return;
  }
  const draft = createFilterConditionDraft(field);
  draft.operator = quickFilterOperator(field);
  draft.value = normalizedValue;
  draft.secondaryValue = '';
  draft.source = 'QUICK';
  filterDraft.conditions.push(draft);
}

function updateQuickFilterInput(payload: { key: string; value: string }) {
  updateQuickFilterCondition(payload);
}

async function applyFiltersToRoute() {
  quickFilterConflict.applyConflictResolution();
  stripLowerPriorityQuickConditions(filterDraft);
  await applyFilterDraftToRoute(filterDraft);
}

async function resetFilters() {
  quickFilterConflict.resetConflictResolution();
  await resetRouteFilters();
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
    const file = await api.exportCustomerIssueDefectSummaryIssues({
      filterGroup: buildFilterPayload(),
    });
    downloadBlob(file.blob, file.filename || '客户问题全量议题数据.xlsx');
    ElMessage.success('议题数据导出成功');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '导出失败'));
  } finally {
    customerIssueExportLoading.value = false;
  }
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
    ElMessage.error(getErrorMessage(error, '导出失败'));
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
    ElMessage.error(getErrorMessage(error, '导出失败'));
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

let routeRefreshSequence = 0;
let routeRefreshPending = false;
let viewIsActive = true;

watch(
  () => [route.query, routeScopeReady.value] as const,
  async (nextQuery, previousQuery) => {
    const nextRouteQuery = nextQuery[0];
    const previousRouteQuery = previousQuery?.[0];
    if (!routeScopeReady.value) {
      invalidateRouteRefresh();
      invalidateBoardRequest();
      invalidateRealtimeStatusRequest();
      invalidateRefreshTracking();
      invalidateDetailForRouteChange();
      return;
    }
    if (previousRouteQuery && hasOnlyPresentationQueryChanges(nextRouteQuery, previousRouteQuery)) {
      syncTablePaginationFromRoute();
      const detailClosed = routeDetailWasClosed(nextRouteQuery, previousRouteQuery);
      if (detailClosed) {
        invalidateRouteRefresh();
        invalidateRealtimeStatusRequest();
        invalidateRefreshTracking();
      }
      if (!routeRefreshPending || detailClosed) {
        await syncDetailFromRoute(
          route.query,
          board.value?.rows ?? [],
          board.value?.definition.defaultPageSize ?? 10,
        );
      }
      return;
    }
    const requestId = beginRouteRefresh();
    invalidateRealtimeStatusRequest();
    invalidateRefreshTracking();
    invalidateDetailForRouteChange();
    resetRuleExplanation();
    try {
      await refreshStatisticBoardRouteState(
        {
          syncTablePaginationFromRoute,
          loadBoard,
          loadRealtimeStatus,
          syncDetailFromRoute: () =>
            syncDetailFromRoute(
              route.query,
              board.value?.rows ?? [],
              board.value?.definition.defaultPageSize ?? 10,
            ),
        },
        () => isCurrentRouteRefresh(requestId),
      );
    } finally {
      finishRouteRefresh(requestId);
    }
  },
  { immediate: true, deep: true },
);

function beginRouteRefresh() {
  routeRefreshSequence += 1;
  routeRefreshPending = true;
  return routeRefreshSequence;
}

function invalidateRouteRefresh() {
  routeRefreshSequence += 1;
  routeRefreshPending = false;
}

function isCurrentRouteRefresh(requestId: number) {
  return viewIsActive && requestId === routeRefreshSequence && routeScopeReady.value;
}

function finishRouteRefresh(requestId: number) {
  if (requestId === routeRefreshSequence) {
    routeRefreshPending = false;
  }
}

function routeDetailWasClosed(nextQuery: LocationQuery, previousQuery: LocationQuery) {
  return String(previousQuery.detailVisible ?? '') === '1'
    && String(nextQuery.detailVisible ?? '') !== '1';
}

function onDetailVisibilityChange(visible: boolean) {
  if (!visible) {
    invalidateRouteRefresh();
    invalidateRealtimeStatusRequest();
    invalidateRefreshTracking();
  }
  handleDetailVisibleChange(visible);
}

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

onBeforeUnmount(() => {
  viewIsActive = false;
  invalidateRouteRefresh();
  invalidateBoardRequest();
  invalidateRealtimeStatusRequest();
  invalidateRefreshTracking();
  invalidateDetailForRouteChange();
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
        <StatisticBoardFreshnessBanner
          :data-as-of="board?.dataAsOf ?? null"
          :pending-updates="board?.pendingUpdates ?? 0"
        />
        <StatisticBoardToolbar
          :filter-draft="filterDraft"
          :active-filter-fields="conditionFilterFields"
          :board-title="toolbarBoardTitle"
          :last-synced-text="lastSyncedText"
          :data-as-of="board?.dataAsOf ?? null"
          :pending-updates="board?.pendingUpdates ?? 0"
          :rule-explanation-loading="ruleExplanationLoading"
          :rule-explanation-summary="qaFriendlyRuleSummary"
          :realtime-status="syncStatus"
          :can-refresh-realtime="canRefreshRealtime"
          :refresh-button-busy="refreshButtonBusy"
          :auto-refresh-on-enter="autoRefreshOnEnter"
          :export-label="primaryExportLabel"
          :show-export="showPrimaryExport"
          :quick-filter-fields="quickFilterFields"
          :quick-filter-values="quickFilterValues"
          :quick-filter-input-drafts="quickFilterInputDrafts"
          :disabled-quick-filter-keys="disabledQuickFilterKeys"
          :highlighted-quick-filter-keys="highlightedQuickFilterKeys"
          :quick-filter-change-guard="quickFilterConflict.guardQuickFilterChange"
          :extra-actions="extraToolbarActions"
          :ui-hooks="props.uiHooks"
          @draft-change="quickFilterConflict.notifyDetectedConflicts(true)"
          @apply-filters="applyFiltersToRoute"
          @reset-filters="resetFilters"
          @quick-filter-change="updateQuickFilterCondition"
          @quick-filter-input-update="updateQuickFilterInput"
          @refresh-board="refreshBoard"
          @load-rule-explanation="ensureRuleExplanationLoaded"
          @open-rule-explanation="openRuleExplanation"
          @export-board="exportBoard"
          @extra-action="handleExtraAction"
          @settings-command="handleSettingsCommand"
          @toggle-auto-refresh="setAutoRefreshOnEnter"
        >
          <template v-if="currentDataScopeProvider || controlSpec" #scope>
            <span v-if="scopeCatalogMissing" class="stat-board-scope-catalog-state" data-testid="scope-catalog-state">
              未找到已启用的{{ currentDataScopeProvider?.label }}目录，请先在议题范围目录中启用后再查看。
            </span>
            <DataScopeBar
              v-else-if="currentDataScopeProvider"
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
            <StatisticBoardControlBar
              v-if="controlSpec"
              :spec="controlSpec"
              :active-dimension="activeDimension"
              :member-controls="memberControls"
              :options-status="controlOptionsStatus"
              :options-message="controlOptionsMessage"
              :member-selection-error="memberSelectionError"
              :disabled="loading || !routeScopeReady"
              :on-dimension-change="setControlDimension"
              :on-member-change="setControlMember"
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
        :sticky-header-enabled="boardViewPrefs.stickyHeaderEnabled !== false"
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
        :fixed-column-keys="fixedColumnKeys"
        :handle-table-current-change="handleTableCurrentChange"
        :handle-table-size-change="handleTableSizeChange"
        :on-settings-visible-change="(visible) => (visible ? openSettings() : closeSettings())"
        :on-width-strategy-change="updateWidthStrategy"
        :on-sticky-header-enabled-change="updateStickyHeaderEnabled"
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
      :collection="detailCollection"
      :on-collection-change="selectDetailCollection"
      :detail-table-class="props.uiHooks.detailTableClass"
      :detail-cell-value="detailCellValue"
      :on-sort-change="handleDetailSortChange"
      :on-current-change="handleDetailCurrentChange"
      :on-size-change="handleDetailSizeChange"
      :on-quick-filter-input-update="handleDetailQuickFilterInputUpdate"
      :on-quick-filter-change="handleDetailQuickFilterChange"
      :on-reset-quick-filters="resetDetailQuickFilters"
      @update:model-value="onDetailVisibilityChange"
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
