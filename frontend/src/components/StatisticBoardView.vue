<script setup lang="ts">
import { computed, reactive, watch } from 'vue';
import { ArrowDown, ArrowUp, Download, InfoFilled, RefreshRight, Search, Sort } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { useRoute, useRouter } from 'vue-router';
import BaseStatisticTable from './base/BaseStatisticTable.vue';
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import { api } from '../api';
import {
  flattenStatisticColumnLeaves,
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
import {
  type SortDirection,
  sortRowsFromSource,
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
  computeFirstColumnMinWidth,
  computeFirstColumnWidth,
  resolveOrderedColumnGroups,
} from './statistic-board-column-layout';
import { useStatisticBoardColumnDrag } from './useStatisticBoardColumnDrag';
import {
  buildRuleExplanationOverview,
  createFallbackRuleExplanation,
  metricFormulaSummary,
  ruleStepRemovedCount,
  ruleStepRetainedRate,
  ruleStepSummary,
} from './statistic-board-rule-explanation';

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

const activeFilterFields = computed(() => board.value?.definition.filters ?? []);

const orderedColumnGroups = computed(() => {
  if (!board.value) {
    return [];
  }
  return resolveOrderedColumnGroups(board.value.definition.columnGroups, boardViewPrefs.value);
});

const sortedRows = computed(() => {
  const rows = board.value?.rows ?? [];
  const columns = board.value?.definition.columnGroups ? flattenStatisticColumnLeaves(board.value.definition.columnGroups) : [];
  return sortRowsFromSource(rows, columns, boardViewPrefs.value);
});
const totalTableRows = computed(() => sortedRows.value.length);
const paginatedRows = computed(() => {
  const start = (tableCurrentPage.value - 1) * tablePageSize.value;
  return sortedRows.value.slice(start, start + tablePageSize.value);
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
const tableRenderKey = computed(
  () =>
    [
      props.boardKey,
      boardViewPrefs.value.widthStrategy,
      boardViewPrefs.value.groupOrder.join('|'),
      Object.entries(boardViewPrefs.value.childGroupOrderByParent)
        .map(([groupKey, childKeys]) => `${groupKey}:${childKeys.join(',')}`)
        .join('|'),
      Object.entries(boardViewPrefs.value.columnOrderByGroup)
        .map(([groupKey, columnKeys]) => `${groupKey}:${columnKeys.join(',')}`)
        .join('|'),
      boardViewPrefs.value.visibleColumnKeys.join('|'),
    ].join('::'),
);

const rowHeaderLabel = computed(() => board.value?.definition.rowHeaderLabel || '统计对象');
const ruleExplanationSteps = computed(() => ruleExplanation.value?.flowSteps ?? []);
const ruleExplanationMetrics = computed(() => ruleExplanation.value?.metricDefinitions ?? []);
const ruleExclusionSteps = computed(() => ruleExplanationSteps.value.slice(1));
const ruleExplanationOverview = computed(() => buildRuleExplanationOverview(ruleExplanation.value));
const ruleFirstInputCount = computed(() => ruleExplanationOverview.value.firstInputCount);
const ruleFinalOutputCount = computed(() => ruleExplanationOverview.value.finalOutputCount);
const ruleFinalRetainedRate = computed(() => ruleExplanationOverview.value.finalRetainedRate);
const qaFriendlyRuleSummary = computed(() => ruleExplanationOverview.value.summary);

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

const firstColumnWidth = computed(() => computeFirstColumnWidth(board.value?.rows ?? [], boardViewPrefs.value.widthStrategy));

const firstColumnMinWidth = computed(() => computeFirstColumnMinWidth(rowHeaderLabel.value, boardViewPrefs.value.widthStrategy));

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

    <el-drawer
      :model-value="ruleExplanationVisible"
      :title="ruleExplanation?.title || '规则说明'"
      size="44%"
      append-to-body
      @update:model-value="handleRuleExplanationVisibleChange"
    >
      <div v-loading="ruleExplanationLoading" class="rule-explanation-panel">
        <el-empty
          v-if="!ruleExplanation?.supported"
          :description="ruleExplanation?.unsupportedReason || '当前统计表暂不支持规则说明。'"
        />

        <template v-else>
          <div class="rule-explanation-section">
            <div class="rule-explanation-section-title">先看结论</div>
            <div class="rule-explanation-summary-card">
              <div class="rule-explanation-summary-main">{{ qaFriendlyRuleSummary }}</div>
              <div v-if="ruleExplanation?.summary" class="rule-explanation-summary-sub">
                {{ ruleExplanation.summary }}
              </div>
            </div>
            <div class="rule-explanation-overview-grid">
              <article class="rule-overview-card">
                <span class="rule-overview-label">原始数据</span>
                <strong class="rule-overview-value">{{ ruleFirstInputCount }}</strong>
              </article>
              <article class="rule-overview-card">
                <span class="rule-overview-label">最终保留</span>
                <strong class="rule-overview-value">{{ ruleFinalOutputCount }}</strong>
              </article>
              <article class="rule-overview-card">
                <span class="rule-overview-label">最终保留比例</span>
                <strong class="rule-overview-value">{{ ruleFinalRetainedRate }}</strong>
              </article>
            </div>
          </div>

          <el-descriptions border :column="1" class="rule-explanation-meta">
            <el-descriptions-item label="当前使用规则版本">{{ ruleExplanation?.version || '-' }}</el-descriptions-item>
            <el-descriptions-item label="这次统计包含哪些数据">{{ ruleExplanation?.scopeDescription || '-' }}</el-descriptions-item>
          </el-descriptions>

          <div class="rule-explanation-section">
            <div class="rule-explanation-section-title">哪些会被排除</div>
            <div class="rule-rule-card-grid">
              <article
                v-for="(step, index) in ruleExclusionSteps"
                :key="step.key"
                class="rule-rule-card"
              >
                <div class="rule-rule-card-title">规则 {{ index + 1 }}：{{ step.title }}</div>
                <div class="rule-rule-card-description">{{ step.description }}</div>
                <div class="rule-rule-card-summary">{{ ruleStepSummary(step, index + 1) }}</div>
                <div class="rule-rule-card-stats">
                  <span class="rule-rule-card-stat">排除 {{ ruleStepRemovedCount(step) }} 条</span>
                  <span class="rule-rule-card-stat">剩余 {{ step.outputCount }} 条</span>
                  <span class="rule-rule-card-stat">保留 {{ ruleStepRetainedRate(step) }}</span>
                </div>
              </article>
            </div>
          </div>

          <div class="rule-explanation-section">
            <div class="rule-explanation-section-title">数据是怎么一步步变少的</div>
            <div class="rule-process-chain">
              <article
                v-for="(step, index) in ruleExplanationSteps"
                :key="`${step.key}-process`"
                class="rule-process-card"
              >
                <div class="rule-process-step">第 {{ index + 1 }} 步</div>
                <div class="rule-process-title">{{ step.title }}</div>
                <div class="rule-process-value">{{ step.outputCount }} 条</div>
                <div class="rule-process-note">
                  {{ index === 0 ? '这是最开始纳入统计的原始数据。' : `这一轮处理后还剩 ${step.outputCount} 条。` }}
                </div>
              </article>
            </div>
          </div>

          <div class="rule-explanation-section">
            <div class="rule-explanation-section-title">最后这些数字怎么算</div>
            <div class="rule-formula-card-grid">
              <article
                v-for="metric in ruleExplanationMetrics"
                :key="metric.key"
                class="rule-formula-card"
              >
                <div class="rule-formula-card-title">{{ metric.label }}</div>
                <div class="rule-formula-card-definition">{{ metricFormulaSummary(metric) }}</div>
                <div class="rule-formula-card-formula">{{ metric.formula }}</div>
                <div v-if="metric.note" class="rule-formula-card-note">{{ metric.note }}</div>
              </article>
            </div>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-dialog
      :model-value="detailVisible"
      :title="detail?.title || '明细数据'"
      class="stat-detail-dialog"
      width="72%"
      top="8vh"
      align-center
      @update:model-value="handleDetailVisibleChange"
      destroy-on-close
      append-to-body
    >
      <div class="stat-detail-shell" v-loading="detailLoading">
        <el-table
          v-if="detail"
          :data="detail.records"
          border
          stripe
          class="stat-detail-table"
          :class="props.uiHooks.detailTableClass"
          @sort-change="handleDetailSortChange"
        >
          <el-table-column
            v-for="column in detail.columns"
            :key="column.key"
            :prop="column.key"
            :label="column.label"
            :width="column.width || undefined"
            :min-width="column.minWidth || 140"
            :sortable="column.sortable ? 'custom' : false"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span class="detail-cell-text">{{ detailCellValue(row, column) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="detail-pagination">
          <el-pagination
            v-if="detail"
            :current-page="detailPagination.page"
            :page-size="detailPagination.size"
            background
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50, 100]"
            :total="detail.total"
            @current-change="handleDetailCurrentChange"
            @size-change="handleDetailSizeChange"
          />
        </div>
      </div>
    </el-dialog>

  </div>
</template>

<style scoped>
.rule-explanation-panel {
  display: grid;
  gap: 16px;
}

.rule-explanation-section {
  display: grid;
  gap: 12px;
}

.rule-explanation-section-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.rule-explanation-summary-card {
  display: grid;
  gap: 8px;
  padding: 16px 18px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.95) 0%, rgba(248, 250, 252, 0.98) 100%);
  border: 1px solid rgba(59, 130, 246, 0.14);
}

.rule-explanation-summary-main {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.9);
}

.rule-explanation-summary-sub {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.66);
}

.rule-explanation-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.rule-overview-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-overview-label {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.52);
}

.rule-overview-value {
  font-size: 22px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.92);
}

.rule-explanation-meta {
  background: rgba(255, 255, 255, 0.82);
  border-radius: 18px;
}

.rule-rule-card-grid,
.rule-formula-card-grid {
  display: grid;
  gap: 12px;
}

.rule-rule-card,
.rule-formula-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-rule-card-title,
.rule-formula-card-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.rule-rule-card-description,
.rule-formula-card-definition,
.rule-formula-card-note {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.rule-rule-card-summary,
.rule-formula-card-formula {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.96);
  border: 1px solid rgba(15, 23, 42, 0.06);
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.8);
}

.rule-rule-card-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-rule-card-stat {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(241, 245, 249, 0.95);
  font-size: 12px;
  color: rgba(15, 23, 42, 0.72);
}

.rule-process-chain {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.rule-process-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-process-step {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.46);
}

.rule-process-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.88);
}

.rule-process-value {
  font-size: 26px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.94);
}

.rule-process-note {
  font-size: 12px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.62);
}

@media (max-width: 960px) {
  .rule-explanation-overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
