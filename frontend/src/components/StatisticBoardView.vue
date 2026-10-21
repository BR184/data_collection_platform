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
import StatisticBoardMemberFilters from './StatisticBoardMemberFilters.vue';
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
import type { RecordTableFilterField, RecordTableFilterValue } from '../types/record-table';
import type { StatisticBoardMemberControlSpec } from '../feature-manifest/statistic-board-controls';
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
  memberQuickFilters,
  memberQuickValues,
  memberCandidatesEmpty,
  memberSummaryChips,
  clearMemberPatch,
  requestParams: controlRequestParams,
  fixedColumnKeys,
  controlOptionsStatus,
  controlOptionsMessage,
  memberSelectionError,
  setDimension: setControlDimension,
  setMember: setControlMember,
} = useStatisticBoardControlParams({
  pageKey: () => route.meta.pageKey as PageKey | undefined,
  routeQuery: () => route.query,
  replaceRouteQuery,
  acceptedFrame: () => acceptedFrame.value,

});
const {
  boardViewPrefs,
  applyStoredViewPrefs,
  applySavedViewPrefs,
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
  acceptedFrame, readMetadata, readAccepted, clearPrivateData,
} = useStatisticBoardData({
  boardKey: () => props.boardKey,
  getFilterGroup: buildFilterPayload,
  getControlParams: () => controlRequestParams.value,
  getRouteQuery: () => route.query,
  getReadScope: () => Object.fromEntries(
    ['sourceInstance', 'source', 'projectId', 'testingPhase', 'milestoneTitle', 'milestoneBusinessKey', 'repositoryName']
      .flatMap(key => typeof route.query[key] === 'string' && route.query[key] ? [[key, String(route.query[key])]] : [])),
  loadBoardData: (boardKey, request, read) => api.getStatisticBoard(boardKey, { ...request, read }),
  loadControlOptions: (boardKey, request, read) => controlSpec.value
    ? api.getStatisticBoardControlOptions(boardKey, { ...request, read }) : Promise.resolve(null),
  loadOpenedDetail: async (nextBoard, query, request, read) => {
    if (String(query.detailVisible ?? '') !== '1') { return null; }
    const row = nextBoard.rows.find(item => item.rowKey === String(query.detailRowKey ?? ''));
    const cell = row?.cells.find(item => item.columnKey === String(query.detailColumnKey ?? ''));
    if (!row || !cell?.drilldown) { return null; }
    const filters = { ...cell.detailParams, ...detailQuickFilterValues };
    if (query.detailCollection) { filters.population = String(query.detailCollection); }
    const response = await api.getStatisticBoardDetails(nextBoard.definition.boardKey, {
      rowKey: row.rowKey, columnKey: cell.columnKey,
      page: Number(query.detailPage ?? 1), size: Number(query.detailPageSize ?? nextBoard.definition.defaultPageSize ?? 10),
      sortField: String(query.detailSortBy ?? ''), sortOrder: String(query.detailSortOrder ?? 'descending'),
      filters, filterGroup: request.filterGroup, read,
    });
    return { rowKey: row.rowKey, columnKey: cell.columnKey, response };
  },
  exportBoardFile: (boardKey, request, read) => api.exportStatisticBoardFile(boardKey, { ...request, read }),
  loadOpenedRule: (boardKey, request, read) => ruleExplanationVisible.value
    ? api.getStatisticBoardRuleExplanation(boardKey, { ...request, read }) : Promise.resolve(null),
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
  visibleColumnKeys,
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

// 成员控制项并入共用"常用筛选"行（仅配置了控制参数的页面）。它们仍是控制参数而非业务条件，
// 因此不进 quickFilterConflict（条件接管只作用于 filterGroup 字段），也不进工具栏的字段快速筛选集合，
// 只在该行内与字段类快速筛选相邻渲染，并共用同一摘要与清除。
const memberControlKeys = computed(() => (controlSpec.value?.members ?? []).map((member) => member.key));
// 整批画面准备失败且没有可继续服务的已接受画面时，才是"候选加载失败"，与"加载中"必须分开。
const memberCandidatesFailed = computed(() =>
  Boolean(controlSpec.value) && !acceptedFrame.value && Boolean(errorMessage.value));
const memberCandidateMessage = computed(() =>
  memberCandidatesFailed.value ? errorMessage.value : controlOptionsMessage.value);
const memberCandidatesEmptyNotice = computed(() =>
  memberCandidatesEmpty.value ? '当前范围没有可选的成员' : '');

function isMemberControlKey(key: string): key is StatisticBoardMemberControlSpec['key'] {
  return memberControlKeys.value.includes(key as StatisticBoardMemberControlSpec['key']);
}

/** 成员变更直接写控制参数并即时生效，不经过条件草稿，也不触发"查询"提交。 */
async function handleMemberQuickFilterChange(payload: { key: string; value: RecordTableFilterValue }) {
  if (!isMemberControlKey(payload.key)) {
    return;
  }
  const raw = Array.isArray(payload.value) ? payload.value[0] ?? '' : payload.value;
  await setControlMember(payload.key, String(raw ?? ''));
}

function retryMemberCandidates() {
  void loadBoard();
}
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
  visibleColumnKeys,
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
  const routeFilterGroup = buildFilterGroupFromRouteQuery(acceptedFrame.value?.query ?? route.query);
  const appliedFilterGroup = stripRouteScopeFilter(response.appliedFilterGroup ?? null);
  const nextDraft = normalizeFilterDraftGroup(
    routeFilterGroup.conditions.length ? routeFilterGroup : appliedFilterGroup,
    response.definition.filters,
  );
  replaceFilterDraftGroup(filterDraft, nextDraft);
  applyStoredViewPrefs(response.definition);
  syncDraftFromVisible();
  const frame = acceptedFrame.value;
  syncDetailPaginationFromRoute(frame?.query ?? route.query, response.definition.defaultPageSize ?? 10);
  if (frame?.openedDetail) { acceptPreparedDetail(response, frame.openedDetail, frame.query); }
  else if (detailVisible.value) { invalidateDetailForRouteChange(); }
  resetRuleExplanation();
  ruleExplanation.value = frame?.openedRule ?? null;

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
  load: () => readAccepted((frame, read) => api.getStatisticBoardRuleExplanation(props.boardKey, {
    ...frame.request, read,
  })),
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
  acceptPrepared: acceptPreparedDetail,
} = useStatisticBoardDetail({
  boardKey: () => props.boardKey,
  getFilterGroup: () => acceptedFrame.value?.request.filterGroup ?? null,
  loadDetails: async (boardKey, params, commit) => {
    const wasPreparing = loading.value;
    if (wasPreparing) { invalidateBoardRequest(); }
    try {
      await readAccepted((_frame, read) => api.getStatisticBoardDetails(boardKey, { ...params, read }), commit);
    } finally {
      // 用户在旧完整画面上操作明细时先完成该操作，随后按最新操作重新准备整批画面。
      if (wasPreparing) { void loadBoard(false); }
    }
  },
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
  applySavedViewPrefs(viewPrefs);
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
  // 成员选择已并入该行，重置必须一起清除；行维度与范围属"分析设置"，不参与筛选重置。
  await resetRouteFilters(clearMemberPatch.value);
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
    await readAccepted((frame, read) => api.exportCustomerIssueDefectSummaryIssues({ ...frame.request, read }),
      file => downloadBlob(file.blob, file.filename || '客户问题全量议题数据.xlsx'));
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
    await readAccepted((frame, read) => api.exportSystemTestDefectSummaryIssues({ ...frame.request, read }),
      file => downloadBlob(file.blob, file.filename || '议题数据.xlsx'));
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
    await readAccepted((frame, read) => api.exportSystemTestHorizontalComparison({ ...frame.request, read }),
      file => downloadBlob(file.blob, file.filename || '系统测试数据分析表.xlsx'));
    ElMessage.success('横向对比导出成功');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '导出失败'));
  } finally {
    horizontalComparisonExportLoading.value = false;
  }
}

watch(() => authState.currentUser?.username, (next, previous) => {
  if (next !== previous) { clearPrivateData(); invalidateDetailForRouteChange(); resetRuleExplanation(); }
});
watch(() => acceptedFrame.value, (frame, previous) => {
  if (!frame && previous) { invalidateDetailForRouteChange(); resetRuleExplanation(); }
});

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
        const wasPreparing = loading.value;
        invalidateBoardRequest();
        invalidateRouteRefresh();
        invalidateRealtimeStatusRequest();
        invalidateRefreshTracking();
        if (wasPreparing) { void loadBoard(false); }
      }
      await syncDetailFromRoute(
        route.query,
        board.value?.rows ?? [],
        board.value?.definition.defaultPageSize ?? 10,
      );
      return;
    }
    const requestId = beginRouteRefresh();
    invalidateRealtimeStatusRequest();
    invalidateRefreshTracking();
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
  return viewIsActive && routeRefreshPending && requestId === routeRefreshSequence && routeScopeReady.value;
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
      <el-card shadow="never" class="stat-board-card" :class="props.uiHooks.cardClass" v-loading="loading && !board">
      <div class="stat-board-query-shell">
        <StatisticBoardFreshnessBanner
          :data-as-of="readMetadata?.dataAsOf ?? board?.dataAsOf ?? null"
          :pending-updates="readMetadata?.pendingUpdates ?? board?.pendingUpdates ?? 0"
          :source-state="readMetadata?.sourceState"
          :source-state-detail="readMetadata?.sourceStateDetail"
          :escalated="readMetadata?.escalated"
        />
        <StatisticBoardToolbar
          :filter-draft="filterDraft"
          :active-filter-fields="conditionFilterFields"
          :board-title="toolbarBoardTitle"
          :last-synced-text="lastSyncedText"
          :data-as-of="readMetadata?.dataAsOf ?? board?.dataAsOf ?? null"
          :pending-updates="readMetadata?.pendingUpdates ?? board?.pendingUpdates ?? 0"
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
          :extra-summary-chips="memberSummaryChips"
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
              :disabled="loading || !routeScopeReady"
              :on-dimension-change="setControlDimension"
            />
          </template>
          <template v-if="controlSpec" #quick-extra>
            <StatisticBoardMemberFilters
              :filters="memberQuickFilters"
              :values="memberQuickValues"
              :status="controlOptionsStatus"
              :failed="memberCandidatesFailed"
              :candidate-message="memberCandidateMessage"
              :empty-notice="memberCandidatesEmptyNotice"
              :selection-error="memberSelectionError"
              :retrying="loading"
              @filter-change="handleMemberQuickFilterChange"
              @retry="retryMemberCandidates"
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
