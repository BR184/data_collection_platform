<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
// 统一统计板组件负责把查询条件、摘要卡片、图表和明细下钻串成同一套交互。
// 各业务看板只传入 boardKey 和配置，避免每个页面重复实现刷新、排序和规则说明。
import { ArrowDown, ArrowUp, Sort } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import BaseStatisticTable from './base/BaseStatisticTable.vue';
import StatisticBoardDetailDialog from './StatisticBoardDetailDialog.vue';
import StatisticBoardRuleExplanationDrawer from './StatisticBoardRuleExplanationDrawer.vue';
import StatisticBoardToolbar from './StatisticBoardToolbar.vue';
import { api } from '../api';
import {
  type StatisticBoardResponse,
} from '../types/api';
import type { StatisticBoardUiHooks } from './statistic-board-ui';
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
import { useStatisticBoardTableAdapters } from '../composables/useStatisticBoardTableAdapters';
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
  exportBoardCsv: (boardKey, request) => api.exportStatisticBoard(boardKey, request),
  onBoardLoaded: handleBoardLoaded,
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
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
  const nextDraft = normalizeFilterDraftGroup(routeFilterGroup, response.definition.filters);
  replaceFilterDraftGroup(filterDraft, nextDraft);
  applyStoredViewPrefs(response.definition);
  syncDraftFromVisible();
  syncDetailPaginationFromRoute(route.query, response.definition.defaultPageSize ?? 10);
}

function buildFilterPayload() {
  return sanitizeFilterDraftGroup(filterDraft);
}

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  loadRuleExplanation,
  openRuleExplanation,
  handleRuleExplanationVisibleChange,
} = useRuleExplanationPanel({
  load: () =>
    api.getStatisticBoardRuleExplanation(props.boardKey, {
      filterGroup: buildFilterPayload(),
    }),
  fallback: (reason) => createFallbackRuleExplanation(props.boardKey, reason),
  warn: (message) => ElMessage.warning(message),
  openFallbackReason: '规则说明暂未加载完成，请稍后再试。',
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
  refreshBoard,
} = useStatisticBoardRefreshController({
  loading,
  detailVisible,
  loadBoard,
  loadRuleExplanation,
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
  closeSettings,
  clearCurrentSort,
  syncDraftFromVisible,
  saveVisibleColumnPrefs,
  restoreDefaultViewPrefs,
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

async function applyFiltersToRoute() {
  await applyFilterDraftToRoute(filterDraft);
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
    await refreshStatisticBoardRouteState({
      setLoading: (nextLoading) => {
        loading.value = nextLoading;
      },
      syncTablePaginationFromRoute,
      loadBoard,
      loadRealtimeStatus,
      loadRuleExplanation,
      syncDetailFromRoute: () =>
        syncDetailFromRoute(route.query, board.value?.rows ?? [], board.value?.definition.defaultPageSize ?? 10),
    });
  },
  { immediate: true, deep: true },
);
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
          :ui-hooks="props.uiHooks"
          @apply-filters="applyFiltersToRoute"
          @reset-filters="resetFilters"
          @refresh-board="refreshBoard"
          @open-rule-explanation="openRuleExplanation"
          @export-board="exportBoard"
          @settings-command="handleSettingsCommand"
        />
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

  </div>
</template>
