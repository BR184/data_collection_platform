<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { ArrowDown, ArrowUp, Download, InfoFilled, RefreshRight, Search, Sort } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import BaseStatisticTable from './base/BaseStatisticTable.vue';
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import StatisticBoardDetailDialog from './StatisticBoardDetailDialog.vue';
import StatisticBoardRuleExplanationDrawer from './StatisticBoardRuleExplanationDrawer.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import { api } from '../api';
import {
  type StatisticBoardResponse,
  type StatisticCellData,
  type StatisticRowData,
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
import {
  columnMinWidth as resolveColumnMinWidth,
  columnResizable as resolveColumnResizable,
} from './statistic-board-column-layout';
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

function handleSettingsCommand(command: string) {
  if (command === 'open-settings') {
    openSettings();
    return;
  }
  if (command === 'clear-sort') {
    clearCurrentSort();
    return;
  }
  if (command === 'restore-default-view') {
    restoreDefaultView();
  }
}

function saveViewPrefs() {
  if (!board.value) {
    return;
  }
  saveVisibleColumnPrefs(draftVisibleColumnKeys.value, closeSettings);
}

function restoreDefaultView() {
  if (!board.value) {
    return;
  }
  restoreDefaultViewPrefs(board.value.definition, syncDraftFromVisible, closeSettings);
}

async function openDetail(row: StatisticRowData, cell: StatisticCellData) {
  await openStatisticDetail(row, cell, board.value?.definition.defaultPageSize ?? 10);
}

function cellForColumn(row: StatisticRowData, columnKey: string) {
  return row.cells.find((item) => item.columnKey === columnKey);
}

function columnMinWidth(column: Parameters<typeof resolveColumnMinWidth>[0]) {
  return resolveColumnMinWidth(column, boardViewPrefs.value.widthStrategy, board.value?.rows ?? []);
}

function columnResizable(column: Parameters<typeof resolveColumnResizable>[0]) {
  return resolveColumnResizable(column);
}

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

async function refreshBoard() {
  loading.value = true;
  try {
    await loadBoard();
    await loadRuleExplanation();
    if (detailVisible.value) {
      await loadDetail();
    }
  } finally {
    loading.value = false;
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
        <div class="stat-board-toolbar" :class="props.uiHooks.toolbarClass">
          <div class="stat-board-toolbar-main" :class="props.uiHooks.toolbarMainClass">
            <StatisticFilterBuilder :model-value="filterDraft" :fields="activeFilterFields" />
          </div>

          <div class="stat-board-toolbar-actions" :class="props.uiHooks.toolbarActionsClass">
            <span v-if="board?.definition.title" class="stat-board-meta-text">{{ board.definition.title }}</span>
            <SyncMetaBadge :value="lastSyncedText" />
            <el-button type="primary" :icon="Search" @click="applyFiltersToRoute">查询</el-button>
            <el-button @click="resetFilters">重置</el-button>
            <el-button :icon="RefreshRight" @click="refreshBoard">刷新</el-button>
            <el-button
              plain
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
            >
              规则说明
            </el-button>
            <el-button plain :icon="Download" @click="exportBoard">导出</el-button>
            <el-dropdown trigger="click" @command="handleSettingsCommand">
              <el-button class="view-settings-trigger">
                <span class="hamburger-icon" aria-hidden="true">
                  <span></span>
                  <span></span>
                  <span></span>
                </span>
                <span>设置</span>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="open-settings">列显示设置</el-dropdown-item>
                  <el-dropdown-item command="clear-sort">恢复默认排序</el-dropdown-item>
                  <el-dropdown-item command="restore-default-view">恢复默认视图</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
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
