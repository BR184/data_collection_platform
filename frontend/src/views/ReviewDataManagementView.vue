<script setup lang="ts">
import { computed, ref } from 'vue';
// 评审数据页是记录、问题项、详情抽屉和导出的组合入口。
// 复杂状态拆到 review-data composable 中，本页只编排跨区块刷新和用户动作。
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { ArrowDown, Download, Document, InfoFilled, Plus, Refresh, Upload } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import PageSettingsButton from '../components/PageSettingsButton.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import ReviewDataLegacyExcelImportDialog from './review-data/ReviewDataLegacyExcelImportDialog.vue';
import ReviewDataDetailDrawer from './review-data/ReviewDataDetailDrawer.vue';
import ReviewProblemPanel from './review-data/ReviewProblemPanel.vue';
import ReviewDataRowActions from './review-data/ReviewDataRowActions.vue';
import ReviewDataRuleExplanationDrawer from './review-data/ReviewDataRuleExplanationDrawer.vue';
import ReviewProblemItemFormDialog from './review-data/ReviewProblemItemFormDialog.vue';
import ReviewRecordFormDialog from './review-data/ReviewRecordFormDialog.vue';
import { reviewDataRuleExplanationContent } from './review-data/review-data-rule-explanation';
import { useReviewDataExport, formatExportFileDate } from './review-data/useReviewDataExport';
import { useReviewDataDetail } from './review-data/useReviewDataDetail';
import { useReviewDataPageActions } from './review-data/useReviewDataPageActions';
import { useReviewDataRecords } from './review-data/useReviewDataRecords';
import { useReviewDataRouteController } from './review-data/useReviewDataRouteController';
import { useReviewProblemItemDialog } from './review-data/useReviewProblemItemDialog';
import { useReviewProblemItems } from './review-data/useReviewProblemItems';
import { useReviewRecordDialog } from './review-data/useReviewRecordDialog';
import { api } from '../api';
import { downloadBlob } from '../utils/csv-download';
import type { ReviewDataRecordRowResponse } from '../types/api';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { REVIEW_DATA_RECORD_QUERY_KEYS } from '../composables/record-route-query-keys';
import { useRouteTableState } from '../composables/useRouteTableState';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import {
  buildReviewDataFilterFields,
  reviewDataColumns,
  reviewProblemItemColumns,
} from './review-data-management';

const PAGE_SCOPE_KEY = 'record-page:review-data-management';
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(PAGE_SCOPE_KEY);
const { route, page, pageSize, sortBy, sortOrder, keyword, patchQuery, bindLoader, isTableLoading } = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
  },
  watchedQueryKeys: REVIEW_DATA_RECORD_QUERY_KEYS,
  autoRefreshOnEnter: readAutoRefreshOnEnter,
});

const {
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
  problemItemsMap,
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

async function refreshAfterProblemItemMutation(recordId: number) {
  await Promise.all([loadRows(), loadProblemItems(recordId)]);
  await refreshDetailIfOpen(recordId);
}

async function refreshAfterRecordUpdate(recordId: number) {
  const hasCachedProblemItems = Object.prototype.hasOwnProperty.call(problemItemsMap.value, recordId);
  const refreshProblemItems = hasCachedProblemItems || isProblemExpanded(recordId)
    ? loadProblemItems(recordId)
    : Promise.resolve();
  await Promise.all([refreshProblemItems, refreshDetailIfOpen(recordId)]);
}

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
  afterUpdateRecord: (recordId) => refreshAfterRecordUpdate(recordId),
  afterCreateRecord: async (detail) => {
    const recordId = detail.record.id;
    await toggleProblemPanel(recordId);
    await handleCreateProblemItem(recordId);
  },
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

const {
  recordExportLoading,
  problemExportLoading,
  templateDownloadLoading,
  exportReviewRecords: handleExportReviewRecords,
  exportProblemDetails: handleExportProblemDetails,
  downloadTemplate: handleDownloadTemplate,
} = useReviewDataExport({
  exportReviewRecords: () => api.exportReviewDataRecordsWorkbook(buildReviewDataRecordQueryParams()),
  exportProblemDetails: () => api.exportReviewDataProblemDetailsWorkbook(buildReviewDataRecordQueryParams()),
  downloadTemplate: () => api.downloadReviewDataTemplateWorkbook(),
  downloadWorkbook: downloadBlob,
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const columns = reviewDataColumns();
const problemColumns = reviewProblemItemColumns();
const legacyImportVisible = ref(false);
const reviewDataSourceInstance = computed(() => String(route.query.sourceInstance ?? ''));

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
  getSourceInstance: () => reviewDataSourceInstance.value,
  patchQuery,
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
    ElMessage.error(error instanceof Error ? error.message : '评审数据加载失败');
  }
});

async function loadRows() {
  await loadReviewRows(buildReviewDataRecordQueryParams({
    page: page.value,
    size: pageSize.value,
  }));
}

async function handleClearFilter(key: string) {
  void key;
}

async function handleConditionFilterApply() {
  await handleQuery(keyword.value);
}

async function handleConditionFilterReset() {
  resetDraft();
  await handleQuery(keyword.value);
}

async function refreshReviewRecords() {
  await refreshReviewDataRecords(buildReviewDataRecordQueryParams({
    page: page.value,
    size: pageSize.value,
  }));
}

async function handleLegacyImportSuccess(result: { importedRecords: number; importedProblemItems: number }) {
  ElMessage.success(`已导入 ${result.importedRecords} 条评审记录，生成 ${result.importedProblemItems} 个问题项`);
  await loadFilterOptions();
  await refreshReviewRecords();
}

async function handleExportRecordProblemDetails(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataRecordRowResponse | undefined;
  if (!raw?.id) {
    return;
  }
  try {
    const blob = await api.exportReviewDataRecordProblemDetailsWorkbook(raw.id);
    downloadBlob(blob, `评审问题详情_${raw.id}_${formatExportFileDate(new Date())}.xlsx`);
    ElMessage.success('已导出当前评审的问题详情');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评审问题详情导出失败');
  }
}

function handleExportCommand(command: string) {
  if (command === 'records') {
    void handleExportReviewRecords();
    return;
  }
  void handleExportProblemDetails();
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
      confirmButtonText: '删除',
      cancelButtonText: '取消',
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
      query-button-text="查询"
      empty-description="当前筛选条件下没有可展示的评审记录。"
      @reset="handleReset"
      @search="handleKeywordSearch"
      @query="handleQuery"
      @clear-filter="handleClearFilter"
      @sort-change="handleSortChange"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
      @expand-change="handleExpandChange"
    >
      <template #filter-builder>
        <StatisticFilterBuilder
          :model-value="filterDraft"
          :fields="reviewFilterFields"
          show-apply-actions
          @apply="handleConditionFilterApply"
          @reset="handleConditionFilterReset"
        />
      </template>

      <template #primary-actions>
        <div class="review-data-toolbar-actions">
          <el-tag effect="plain" type="primary">当前 {{ total }} 条</el-tag>
          <el-button
            plain
            :icon="InfoFilled"
            data-testid="review-rule-explanation-trigger"
            @click="openRuleExplanation"
          >
            规则说明
          </el-button>
          <el-button plain :icon="Refresh" @click="handleRefresh">刷新</el-button>
          <el-button
            plain
            :icon="Document"
            :loading="templateDownloadLoading"
            @click="handleDownloadTemplate"
          >
            模板
          </el-button>
          <el-dropdown @command="handleExportCommand">
            <el-button
              plain
              :icon="Download"
              :loading="recordExportLoading || problemExportLoading"
            >
              导出
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="records">导出评审列表</el-dropdown-item>
                <el-dropdown-item command="problems">导出问题列表</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-button plain :icon="Upload" @click="legacyImportVisible = true">
            导入
          </el-button>
          <PageSettingsButton :scope-key="PAGE_SCOPE_KEY" />
          <el-button type="primary" :icon="Plus" @click="handleCreateRecord">新增评审</el-button>
        </div>
      </template>

      <template #cell-title="{ row }">
        <div class="title-cell">
          <span class="title-main">{{ row.title }}</span>
          <span class="title-sub">{{ row.reviewExpertsSummary }}</span>
        </div>
      </template>

      <template #expand="{ row }">
        <ReviewProblemPanel
          :record="row.__raw as ReviewDataRecordRowResponse"
          :loading="problemLoadingMap[(row.__raw as ReviewDataRecordRowResponse).id]"
          :rows="problemItemsFor((row.__raw as ReviewDataRecordRowResponse).id)"
          :columns="problemColumns"
          :on-create-problem-item="handleCreateProblemItem"
          :on-edit-problem-item="handleEditProblemItem"
          :on-delete-problem-item="handleDeleteProblemItem"
        />
      </template>

      <template #row-actions="{ row }">
        <ReviewDataRowActions
          :row="row"
          :expanded="isProblemExpandedByRow(row)"
          :on-toggle-problem-panel="toggleProblemPanelByRow"
          :on-open-detail="handleOpenDetail"
          :on-edit-record="handleEditRecord"
          :on-create-problem-item="handleCreateProblemItemByRow"
          :on-export-problem-details="handleExportRecordProblemDetails"
          :on-delete-record="handleDeleteRecord"
        />
      </template>
    </BaseRecordTable>

    <ReviewDataDetailDrawer v-model:visible="detailVisible" :detail-data="detailData" />

    <ReviewDataRuleExplanationDrawer
      v-model:visible="ruleExplanationVisible"
      :content="reviewDataRuleExplanationContent"
    />

    <ReviewDataLegacyExcelImportDialog
      v-model:visible="legacyImportVisible"
      :filter-options="filterOptions"
      :preview-import="api.previewReviewDataLegacyExcelImport"
      :confirm-import="api.confirmReviewDataLegacyExcelImport"
      @success="handleLegacyImportSuccess"
      @error="ElMessage.error"
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

.review-data-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

</style>
