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
import { downloadBlob, downloadCsv, formatExportFileDate } from '../utils/csv-download';
import {
  type SortDirection,
} from './statistic-board-sorting';
import {
  createEmptyFilterGroup,
  replaceFilterDraftGroup,
  resetFilterDraftGroup,
  normalizeFilterDraftGroup,
  sanitizeFilterDraftGroup,
  type StatisticFilterDraftGroup,
} from './statistic-board-filters';
import {
  buildFilterGroupFromRouteQuery,
} from './statistic-board-route-query';
import { useStatisticBoardColumnDrag } from './useStatisticBoardColumnDrag';
import { createFallbackRuleExplanation } from './statistic-board-rule-explanation';
import type { StatisticBoardViewPrefs } from './statistic-board-view-prefs';

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
      loading: issueExportLoading.value,
      plain: true,
    },
    {
      key: 'export-system-test-horizontal-comparison',
      label: '横向对比导出',
      icon: Download,
      loading: horizontalComparisonExportLoading.value,
      plain: true,
    },
  ];
});

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
  loadStatus: () => api.getStatisticBoardRealtimeStatus(props.boardKey),
  emptyText: '暂无同步记录',
});

const {
  detailLoading,
  detailVisible,
  detail,
  detailPagination,
  detailCellValue,
  loadDetail,
  openDetail: openStatisticDetail,
  handleDetailSortChange,
  handleDetailCurrentChange,
  handleDetailSizeChange,
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
    const csv = await api.exportCustomerIssueRecords({
      topic: 'cc-product',
      filterGroup: buildFilterPayload(),
    });
    downloadCsv(csv, `客户问题全量议题数据_${formatExportFileDate(new Date())}.csv`);
    ElMessage.success('议题数据导出成功');
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    customerIssueExportLoading.value = false;
  }
}

async function exportSystemTestIssues() {
  issueExportLoading.value = true;
  try {
    const csv = await api.exportSystemTestIssueSearchRecords({
      filterGroup: buildFilterPayload(),
    });
    downloadCsv(csv, `系统测试议题数据_${formatExportFileDate(new Date())}.csv`);
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
    downloadBlob(file.blob, file.filename || `系统测试横向对比_${formatExportFileDate(new Date())}.xlsx`);
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
  () => route.query,
  async () => {
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

onMounted(() => {
  void autoRefreshPageData();
});

async function autoRefreshPageData() {
  if (!autoRefreshOnEnter.value || loading.value) {
    return;
  }
  const markerKey = autoRefreshMarkerKey();
  if (window.sessionStorage.getItem(markerKey) === 'true') {
    return;
  }
  window.sessionStorage.setItem(markerKey, 'true');
  await autoRefreshBoard();
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
          :board-title="board?.definition.title"
          :last-synced-text="lastSyncedText"
          :rule-explanation-loading="ruleExplanationLoading"
          :realtime-status="syncStatus"
          :can-refresh-realtime="canRefreshRealtime"
          :auto-refresh-on-enter="autoRefreshOnEnter"
          :extra-actions="extraToolbarActions"
          :ui-hooks="props.uiHooks"
          @apply-filters="applyFiltersToRoute"
          @reset-filters="resetFilters"
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
      :detail-table-class="props.uiHooks.detailTableClass"
      :detail-cell-value="detailCellValue"
      :on-sort-change="handleDetailSortChange"
      :on-current-change="handleDetailCurrentChange"
      :on-size-change="handleDetailSizeChange"
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
