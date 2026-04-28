<script setup lang="ts">
import { computed } from 'vue';
import {
  ElButton,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElMessage,
  ElMessageBox,
  ElTag,
} from 'element-plus';
import { ArrowDown, Download, EditPen, InfoFilled, Plus, Refresh } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import ReviewDataDetailDrawer from './review-data/ReviewDataDetailDrawer.vue';
import ReviewDataRuleExplanationDrawer from './review-data/ReviewDataRuleExplanationDrawer.vue';
import ReviewProblemItemFormDialog from './review-data/ReviewProblemItemFormDialog.vue';
import ReviewRecordFormDialog from './review-data/ReviewRecordFormDialog.vue';
import { reviewDataRuleExplanationContent } from './review-data/review-data-rule-explanation';
import { downloadCsv, useReviewDataExport } from './review-data/useReviewDataExport';
import { useReviewDataDetail } from './review-data/useReviewDataDetail';
import { useReviewDataPageActions } from './review-data/useReviewDataPageActions';
import { useReviewDataRecords } from './review-data/useReviewDataRecords';
import { useReviewDataRouteController } from './review-data/useReviewDataRouteController';
import { useReviewProblemItemDialog } from './review-data/useReviewProblemItemDialog';
import { useReviewProblemItems } from './review-data/useReviewProblemItems';
import { useReviewRecordDialog } from './review-data/useReviewRecordDialog';
import { api } from '../api';
import type {
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
} from '../types/api';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { useRouteTableState } from '../composables/useRouteTableState';
import {
  buildReviewDataExportCsv,
  buildReviewDataFilterFields,
  reviewDataColumns,
  reviewProblemItemColumns,
} from './review-data-management';

const { route, page, pageSize, sortBy, sortOrder, keyword, patchQuery, debouncedPatchQuery, bindLoader, isTableLoading } = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
  },
});

const {
  rows,
  total,
  filterOptions,
  summaryCards,
  tableRows,
  loadFilterOptions,
  loadRows: loadReviewRows,
  refresh: refreshReviewDataRecords,
} = useReviewDataRecords({
  fetchFilterOptions: () => api.getReviewDataFilterOptions(),
  fetchRecords: (params) => api.getReviewDataRecords(params),
});

const {
  expandedRowKeys,
  problemLoadingMap,
  loadProblemItems,
  handleExpandChange,
  isProblemExpanded,
  toggleProblemPanel,
  problemItemsFor,
} = useReviewProblemItems((recordId) => api.getReviewDataProblemItems(recordId));

const {
  detailVisible,
  detailData,
  openDetail,
  refreshDetailIfOpen,
} = useReviewDataDetail({
  loadRecordDetail: (recordId) => api.getReviewDataRecordDetail(recordId),
  notifyError: (message) => ElMessage.error(message),
});

const {
  recordDialogVisible,
  recordDialogSaving,
  recordEditMode,
  recordForm,
  openCreateRecord,
  openEditRecord,
  submitRecord,
} = useReviewRecordDialog({
  loadRecordDetail: (recordId) => api.getReviewDataRecordDetail(recordId),
  createRecord: (payload) => api.createReviewDataRecord(payload),
  updateRecord: (recordId, payload) => api.updateReviewDataRecord(recordId, payload),
  refreshRecords: () => refreshReviewRecords(),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const {
  problemDialogVisible,
  problemDialogSaving,
  problemDialogEditMode,
  currentProblemExpertOptions,
  problemForm,
  openCreateProblemItem: handleCreateProblemItem,
  openEditProblemItem: handleEditProblemItem,
  submitProblemItem,
} = useReviewProblemItemDialog({
  loadRecordDetail: (recordId) => api.getReviewDataRecordDetail(recordId),
  createProblemItem: (recordId, payload) => api.createReviewDataProblemItem(recordId, payload),
  updateProblemItem: (recordId, itemId, payload) => api.updateReviewDataProblemItem(recordId, itemId, payload),
  refreshAfterMutation: (recordId) => refreshAfterProblemItemMutation(recordId),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const { exportLoading, exportExcel: handleExportExcel } = useReviewDataExport({
  fetchRecords: (page, size) => api.getReviewDataRecords(buildReviewDataRecordQueryParams({ page, size })),
  buildCsv: (exportRows) => buildReviewDataExportCsv(exportRows),
  downloadCsv,
  getExpectedTotal: () => Math.max(total.value, rows.value.length),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const columns = reviewDataColumns();
const problemColumns = reviewProblemItemColumns();

const reviewFilterFields = computed(() => buildReviewDataFilterFields(filterOptions.value));

const {
  filterDraft,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
} = useConditionFilterGroupState(reviewFilterFields);

const {
  buildRecordQueryParams: buildReviewDataRecordQueryParams,
  syncFilterDraftFromRoute,
  handleReset,
  handleQuery,
  handleKeywordSearch,
  handleSortChange,
  handlePageChange,
  handleSizeChange,
} = useReviewDataRouteController({
  getRouteQuery: () => route.query,
  getKeyword: () => keyword.value,
  getPage: () => page.value,
  getPageSize: () => pageSize.value,
  getSortBy: () => sortBy.value,
  getSortOrder: () => sortOrder.value as 'asc' | 'desc' | '',
  patchQuery,
  debouncedPatchQuery,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  loadRows: () => loadRows(),
});

bindLoader(async () => {
  try {
    await loadFilterOptions();
    syncFilterDraftFromRoute();
    await loadRows();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '璇勫鏁版嵁鍔犺浇澶辫触');
  }
});

async function loadRows() {
  await loadReviewRows(buildReviewDataRecordQueryParams({
    page: page.value,
    size: pageSize.value,
  }));
}

async function refreshReviewRecords() {
  await refreshReviewDataRecords(buildReviewDataRecordQueryParams({
    page: page.value,
    size: pageSize.value,
  }));
}

const {
  ruleExplanationVisible,
  handleRefresh,
  toggleProblemPanelByRow,
  isProblemExpandedByRow,
  handleCreateProblemItemByRow,
  handleOpenDetail,
  handleEditRecord,
  handleCreateRecord,
  handleDeleteRecord,
  handleDeleteProblemItem,
  openRuleExplanation,
} = useReviewDataPageActions({
  refreshRecords: () => refreshReviewRecords(),
  toggleProblemPanel,
  isProblemExpanded,
  openDetail,
  openCreateRecord,
  openEditRecord,
  openCreateProblemItem: handleCreateProblemItem,
  openEditProblemItem: handleEditProblemItem,
  deleteRecord: (recordId) => api.deleteReviewDataRecord(recordId),
  deleteProblemItem: (recordId, itemId) => api.deleteReviewDataProblemItem(recordId, itemId),
  refreshAfterProblemItemMutation,
  confirm: (message, title) =>
    ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText: '鍒犻櫎',
      cancelButtonText: '鍙栨秷',
    }),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

</script>

<template>
  <section class="review-data-page">
    <section class="review-data-summary">
      <article v-for="card in summaryCards" :key="card.key" class="summary-card">
        <span class="summary-card-label">{{ card.label }}</span>
        <strong class="summary-card-value">{{ card.value }}</strong>
      </article>
    </section>

    <BaseRecordTable
      :columns="columns"
      :rows="tableRows"
      :loading="isTableLoading"
      :keyword="keyword"
      :keyword-auto-search="true"
      :page="page"
      :page-size="pageSize"
      :total="total"
      :expanded-row-keys="expandedRowKeys"
      :expand-column-visible="false"
      :row-actions-width="188"
      :show-refresh="false"
      query-button-text="鏌ヨ"
      empty-description="当前筛选条件下没有可展示的评审记录。"
      @reset="handleReset"
      @search="handleKeywordSearch"
      @query="handleQuery"
      @sort-change="handleSortChange"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
      @expand-change="handleExpandChange"
    >
      <template #filter-builder>
        <StatisticFilterBuilder :model-value="filterDraft" :fields="reviewFilterFields" />
      </template>

      <template #primary-actions>
        <el-button
          class="review-rule-trigger"
          data-testid="review-rule-explanation-trigger"
          plain
          size="small"
          :icon="InfoFilled"
          @click="openRuleExplanation"
        >
          甯姪鎸囧崡
        </el-button>
        <el-button type="primary" :icon="Plus" @click="handleCreateRecord">鏂板璇勫</el-button>
        <el-button plain :icon="Download" :loading="exportLoading" @click="handleExportExcel">瀵煎嚭Excel</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">鍒锋柊</el-button>
      </template>

      <template #cell-title="{ row }">
        <div class="title-cell">
          <span class="title-main">{{ row.title }}</span>
          <span class="title-sub">{{ row.reviewExpertsSummary }}</span>
        </div>
      </template>

      <template #expand="{ row }">
        <div class="problem-panel">
          <div class="problem-panel-head">
            <div class="problem-panel-title">
              <span>璇勫闂娓呭崟</span>
              <el-tag size="small" effect="plain">共 {{ (row.__raw as ReviewDataRecordRowResponse).problemCount }} 条</el-tag>
            </div>
            <el-button type="primary" text :icon="Plus" @click="handleCreateProblemItem((row.__raw as ReviewDataRecordRowResponse).id)">
              鏂板闂
            </el-button>
          </div>

          <el-table
            v-loading="problemLoadingMap[(row.__raw as ReviewDataRecordRowResponse).id]"
            :data="problemItemsFor((row.__raw as ReviewDataRecordRowResponse).id)"
            class="problem-subtable"
            border
            stripe
            empty-text="当前评审下还没有录入问题清单。"
          >
            <el-table-column
              v-for="column in problemColumns"
              :key="column.key"
              :prop="column.key"
              :label="column.label"
              :width="column.width"
              :min-width="column.minWidth"
              :align="column.align ?? 'left'"
              :show-overflow-tooltip="column.showOverflowTooltip ?? true"
            >
              <template #default="{ row: problemRow }">
                <template v-if="column.type === 'tag'">
                  <el-tag
                    v-for="tag in problemRow[column.key] as Array<{ label: string; type?: 'success' | 'warning' | 'info' | 'danger' | 'primary' }>"
                    :key="tag.label"
                    size="small"
                    :type="tag.type ?? 'info'"
                    effect="plain"
                  >
                    {{ tag.label }}
                  </el-tag>
                </template>
                <span v-else>{{ problemRow[column.key] }}</span>
              </template>
            </el-table-column>

            <el-table-column label="鎿嶄綔" width="136" fixed="right" align="center">
              <template #default="{ row: problemRow }">
                <div class="problem-actions">
                  <el-button
                    class="problem-action-edit"
                    type="primary"
                    plain
                    size="small"
                    :icon="EditPen"
                    @click="handleEditProblemItem((row.__raw as ReviewDataRecordRowResponse).id, problemRow.__raw as ReviewDataProblemItemResponse)"
                  >
                    缂栬緫
                  </el-button>
                  <el-button
                    class="problem-action-delete"
                    type="danger"
                    text
                    size="small"
                    @click="handleDeleteProblemItem((row.__raw as ReviewDataRecordRowResponse).id, (problemRow.__raw as ReviewDataProblemItemResponse).id)"
                  >
                    鍒犻櫎
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <template #row-actions="{ row }">
        <div class="record-actions">
          <el-button
            class="record-actions-chip"
            :class="{ active: isProblemExpandedByRow(row) }"
            type="primary"
            plain
            size="small"
            @click="toggleProblemPanelByRow(row)"
          >
            {{ isProblemExpandedByRow(row) ? '鏀惰捣' : '娓呭崟' }}
          </el-button>
          <el-button class="record-actions-link" type="primary" plain size="small" @click="handleOpenDetail(row)">鏌ョ湅</el-button>
          <el-dropdown>
            <span class="record-actions-more">
              鏇村
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleEditRecord(row)">缂栬緫璇勫</el-dropdown-item>
                <el-dropdown-item @click="handleCreateProblemItemByRow(row)">鏂板闂</el-dropdown-item>
                <el-dropdown-item divided @click="handleDeleteRecord(row)">鍒犻櫎璇勫</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
    </BaseRecordTable>

    <ReviewDataDetailDrawer v-model:visible="detailVisible" :detail-data="detailData" />

    <ReviewDataRuleExplanationDrawer
      v-model:visible="ruleExplanationVisible"
      :content="reviewDataRuleExplanationContent"
    />

    <ReviewRecordFormDialog
      v-model:visible="recordDialogVisible"
      :saving="recordDialogSaving"
      :model-value="recordForm"
      :filter-options="filterOptions"
      :tip-text="reviewDataRuleExplanationContent.recordDialogTip"
      :edit-mode="recordEditMode"
      @submit="submitRecord"
    />

    <ReviewProblemItemFormDialog
      v-model:visible="problemDialogVisible"
      :saving="problemDialogSaving"
      :model-value="problemForm"
      :filter-options="filterOptions"
      :expert-options-override="currentProblemExpertOptions"
      :tip-text="reviewDataRuleExplanationContent.problemDialogTip"
      :edit-mode="problemDialogEditMode"
      @submit="submitProblemItem"
    />
  </section>
</template>

<style scoped>
.review-data-page {
  display: grid;
  gap: 8px;
}

.review-data-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px;
}

.summary-card {
  display: grid;
  gap: 6px;
  min-height: 72px;
  padding: 12px 16px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.94));
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.03);
}

.summary-card-label {
  font-size: 12px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.52);
}

.summary-card-value {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.1;
  color: #0f172a;
}

.title-cell {
  display: grid;
  gap: 4px;
}

.title-main {
  color: rgba(15, 23, 42, 0.88);
  font-weight: 600;
}

.title-sub {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.48);
}

.review-rule-trigger {
  border-color: rgba(59, 130, 246, 0.18);
  background: rgba(59, 130, 246, 0.05);
  color: #2563eb;
}

.review-rule-trigger:hover,
.review-rule-trigger:focus {
  border-color: rgba(37, 99, 235, 0.3);
  background: rgba(37, 99, 235, 0.1);
  color: #1d4ed8;
}

.record-actions {
  display: grid;
  grid-template-columns: 52px 52px 48px;
  align-items: center;
  justify-content: center;
  column-gap: 7px;
}

.record-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.record-actions-chip {
  height: 26px;
  width: 52px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.2);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  justify-content: center;
  line-height: 26px;
}

.record-actions-chip:hover,
.record-actions-chip.active {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}

.record-actions-link {
  height: 26px;
  width: 52px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.14);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.04);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  justify-content: center;
  line-height: 26px;
}

.record-actions-link:hover {
  border-color: rgba(37, 99, 235, 0.28);
  background: rgba(37, 99, 235, 0.1);
  color: #1d4ed8;
}

.record-actions-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  height: 26px;
  width: 48px;
  padding: 0;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12px;
  color: rgba(37, 99, 235, 0.72);
  line-height: 26px;
}

.record-actions-more:hover {
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
}

.problem-panel {
  display: grid;
  gap: 12px;
  padding: 12px 8px 4px 40px;
  background: rgba(248, 250, 252, 0.72);
}

.problem-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.problem-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.82);
}

.problem-subtable {
  border-radius: 12px;
  overflow: hidden;
}

.problem-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
}

.problem-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.problem-action-edit {
  height: 26px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.2);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.problem-action-edit:hover,
.problem-action-edit:focus {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}

.problem-action-delete {
  height: 26px;
  padding: 0 6px;
  color: rgba(220, 38, 38, 0.72);
  font-size: 12px;
  font-weight: 500;
}

.problem-action-delete:hover,
.problem-action-delete:focus {
  background: rgba(220, 38, 38, 0.08);
  color: #dc2626;
}

:deep(.problem-subtable .el-table__header th.el-table__cell) {
  background: #f8fafc;
}
</style>

