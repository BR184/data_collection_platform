<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  ElButton,
  ElDescriptions,
  ElDescriptionsItem,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElMessage,
  ElMessageBox,
  ElTag,
} from 'element-plus';
import { ArrowDown, Document, Download, EditPen, InfoFilled, Plus, Refresh } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import ReviewProblemItemFormDialog from './review-data/ReviewProblemItemFormDialog.vue';
import ReviewRecordFormDialog from './review-data/ReviewRecordFormDialog.vue';
import { reviewDataRuleExplanationContent } from './review-data/review-data-rule-explanation';
import { downloadCsv, useReviewDataExport } from './review-data/useReviewDataExport';
import { useReviewDataDetail } from './review-data/useReviewDataDetail';
import { useReviewProblemItemDialog } from './review-data/useReviewProblemItemDialog';
import { useReviewProblemItems } from './review-data/useReviewProblemItems';
import { useReviewRecordDialog } from './review-data/useReviewRecordDialog';
import { api } from '../api';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
  ReviewDataSummaryResponse,
  StatisticFilterField,
  StatisticFilterGroup,
} from '../types/api';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { useRouteTableState } from '../composables/useRouteTableState';
import { mergeRouteQuery } from '../components/statistic-board-route-query';
import {
  buildReviewDataExportCsv,
  buildReviewDataSummaryCards,
  buildReviewDataTableRows,
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

const rows = ref<ReviewDataRecordRowResponse[]>([]);
const total = ref(0);
const summary = ref<ReviewDataSummaryResponse | null>(null);
const filterOptions = ref<ReviewDataFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  reviewOwners: [],
  reviewTypes: [],
  reviewExperts: [],
  problemStatuses: [],
  reviewCategories: [],
  problemCategories: [],
});

const ruleExplanationVisible = ref(false);

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

const reviewFilterFields = computed<StatisticFilterField[]>(() => [
  {
    key: 'title',
    label: '标题',
    type: 'text',
    operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
  },
  {
    key: 'projectName',
    label: '项目',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.projectNames,
  },
  {
    key: 'moduleName',
    label: '模块',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.moduleNames,
  },
  {
    key: 'reviewOwner',
    label: '负责人',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.reviewOwners,
  },
  {
    key: 'reviewType',
    label: '评审类型',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.reviewTypes,
  },
  {
    key: 'reviewExpert',
    label: '评审专家',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.reviewExperts,
  },
  {
    key: 'problemStatus',
    label: '问题状态',
    type: 'select',
    operators: ['eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: filterOptions.value.problemStatuses,
  },
  {
    key: 'reviewScalePages',
    label: '页数',
    type: 'number',
    operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
  },
  {
    key: 'problemCount',
    label: '问题合计',
    type: 'number',
    operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
  },
  {
    key: 'problemDensity',
    label: '缺陷密度',
    type: 'number',
    operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
  },
  {
    key: 'reviewDate',
    label: '评审日期',
    type: 'datetime',
    operators: ['day', 'before', 'after', 'between'],
  },
]);

const {
  filterDraft,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
} = useConditionFilterGroupState(reviewFilterFields);

const appliedFilterGroup = ref<StatisticFilterGroup | null>(null);
const summaryCards = computed(() => buildReviewDataSummaryCards(summary.value));
const tableRows = computed(() => buildReviewDataTableRows(rows.value));

bindLoader(async () => {
  try {
    await loadFilterOptions();
    syncFilterDraftFromRoute();
    await loadRows();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评审数据加载失败');
  }
});

async function loadFilterOptions() {
  filterOptions.value = await api.getReviewDataFilterOptions();
}

async function loadRows() {
  const response = await api.getReviewDataRecords(buildReviewDataRecordQueryParams({
    page: page.value,
    size: pageSize.value,
  }));
  rows.value = response.records;
  total.value = response.total;
  summary.value = response.summary;
}

function buildReviewDataRecordQueryParams(overrides: { page?: number; size?: number } = {}) {
  return {
    keyword: keyword.value.trim(),
    filterGroup: appliedFilterGroup.value,
    page: overrides.page ?? page.value,
    size: overrides.size ?? pageSize.value,
    sortBy: sortBy.value,
    sortOrder: (sortOrder.value as 'asc' | 'desc' | null) ?? 'desc',
  };
}

function syncFilterDraftFromRoute() {
  initializeFromQuery(route.query);
  appliedFilterGroup.value = buildFilterPayload();
}

async function handleRefresh() {
  try {
    await refreshReviewRecords();
    ElMessage.success('已刷新评审数据');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评审数据刷新失败');
  }
}

async function refreshReviewRecords() {
  await Promise.all([loadFilterOptions(), loadRows()]);
}

async function handleReset() {
  resetDraft();
  appliedFilterGroup.value = null;
  await patchQuery({
    keyword: '',
    ...buildResetQueryPatch(route.query),
    title: '',
    projectName: '',
    moduleName: '',
    reviewOwner: '',
    reviewType: '',
    problemStatus: '',
    reviewExpert: '',
    sortBy: 'updatedAt',
    sortOrder: 'desc',
    page: 1,
  });
}

async function handleQuery(nextKeyword = keyword.value) {
  appliedFilterGroup.value = buildFilterPayload();
  const patch = {
    keyword: nextKeyword.trim(),
    ...buildApplyQueryPatch(route.query),
    page: 1,
  };
  const nextQuery = mergeRouteQuery(route.query, patch);
  const currentQuery = mergeRouteQuery(route.query, {});
  const queryChanged = JSON.stringify(nextQuery) !== JSON.stringify(currentQuery);
  await patchQuery(patch);
  if (!queryChanged) {
    await loadRows();
  }
}

function handleKeywordSearch(nextKeyword: string) {
  debouncedPatchQuery({
    keyword: nextKeyword.trim(),
    page: 1,
  });
}

async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
  await patchQuery({
    sortBy: payload.prop || 'updatedAt',
    sortOrder: payload.order === 'ascending' ? 'asc' : payload.order === 'descending' ? 'desc' : 'desc',
    page: 1,
  });
}

async function handlePageChange(nextPage: number) {
  await patchQuery({ page: nextPage });
}

async function handleSizeChange(nextSize: number) {
  await patchQuery({ pageSize: nextSize, page: 1 });
}

function recordFromTableRow(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataRecordRowResponse | undefined;
  return typeof raw?.id === 'number' ? raw : null;
}

async function toggleProblemPanelByRow(row: Record<string, unknown>) {
  const raw = recordFromTableRow(row);
  if (!raw) {
    return;
  }
  await toggleProblemPanel(raw.id);
}

function isProblemExpandedByRow(row: Record<string, unknown>) {
  const raw = recordFromTableRow(row);
  return raw ? isProblemExpanded(raw.id) : false;
}

async function handleCreateProblemItemByRow(row: Record<string, unknown>) {
  const raw = recordFromTableRow(row);
  if (!raw) {
    return;
  }
  await handleCreateProblemItem(raw.id);
}

async function handleOpenDetail(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataRecordRowResponse | undefined;
  if (!raw) {
    return;
  }
  await openDetail(raw.id);
}

async function handleEditRecord(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataRecordRowResponse | undefined;
  if (!raw) {
    return;
  }
  await openEditRecord(raw.id);
}

function handleCreateRecord() {
  openCreateRecord();
}

async function handleDeleteRecord(row: Record<string, unknown>) {
  const raw = row.__raw as ReviewDataRecordRowResponse | undefined;
  if (!raw) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除评审“${raw.title}”吗？`, '删除评审', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    await api.deleteReviewDataRecord(raw.id);
    ElMessage.success('评审记录已删除');
    await refreshReviewRecords();
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error instanceof Error ? error.message : '评审记录删除失败');
    }
  }
}

async function refreshAfterProblemItemMutation(recordId: number) {
  await Promise.all([loadRows(), loadProblemItems(recordId)]);
  await refreshDetailIfOpen(recordId);
}

async function handleDeleteProblemItem(recordId: number, itemId: number) {
  try {
    await ElMessageBox.confirm('确认删除这条评审问题吗？', '删除评审问题', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    await api.deleteReviewDataProblemItem(recordId, itemId);
    ElMessage.success('评审问题已删除');
    await refreshAfterProblemItemMutation(recordId);
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error instanceof Error ? error.message : '评审问题删除失败');
    }
  }
}

function displayText(value: unknown) {
  const text = String(value ?? '').trim();
  return text || '-';
}

function formatDate(value?: string | null) {
  return value ? value.slice(0, 10) : '-';
}

function openRuleExplanation() {
  ruleExplanationVisible.value = true;
}
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
          帮助指南
        </el-button>
        <el-button type="primary" :icon="Plus" @click="handleCreateRecord">新增评审</el-button>
        <el-button plain :icon="Download" :loading="exportLoading" @click="handleExportExcel">导出Excel</el-button>
        <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
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
              <span>评审问题清单</span>
              <el-tag size="small" effect="plain">共 {{ (row.__raw as ReviewDataRecordRowResponse).problemCount }} 条</el-tag>
            </div>
            <el-button type="primary" text :icon="Plus" @click="handleCreateProblemItem((row.__raw as ReviewDataRecordRowResponse).id)">
              新增问题
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

            <el-table-column label="操作" width="136" fixed="right" align="center">
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
                    编辑
                  </el-button>
                  <el-button
                    class="problem-action-delete"
                    type="danger"
                    text
                    size="small"
                    @click="handleDeleteProblemItem((row.__raw as ReviewDataRecordRowResponse).id, (problemRow.__raw as ReviewDataProblemItemResponse).id)"
                  >
                    删除
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
            {{ isProblemExpandedByRow(row) ? '收起' : '清单' }}
          </el-button>
          <el-button class="record-actions-link" type="primary" plain size="small" @click="handleOpenDetail(row)">查看</el-button>
          <el-dropdown>
            <span class="record-actions-more">
              更多
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="handleEditRecord(row)">编辑评审</el-dropdown-item>
                <el-dropdown-item @click="handleCreateProblemItemByRow(row)">新增问题</el-dropdown-item>
                <el-dropdown-item divided @click="handleDeleteRecord(row)">删除评审</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </template>
    </BaseRecordTable>

    <el-drawer
      v-model="detailVisible"
      title="评审详情"
      size="560px"
      append-to-body
      class="review-data-drawer"
    >
      <template v-if="detailData">
        <section class="detail-section">
          <header class="detail-section-head">
            <el-icon><Document /></el-icon>
            <span>评审基础信息</span>
          </header>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="项目名称">{{ displayText(detailData.record.projectName) }}</el-descriptions-item>
            <el-descriptions-item label="标题">{{ displayText(detailData.record.title) }}</el-descriptions-item>
            <el-descriptions-item label="模块">{{ displayText(detailData.record.moduleName) }}</el-descriptions-item>
            <el-descriptions-item label="评审类型">{{ displayText(detailData.record.reviewType) }}</el-descriptions-item>
            <el-descriptions-item label="评审日期">{{ formatDate(detailData.record.reviewDate) }}</el-descriptions-item>
            <el-descriptions-item label="评审负责人">{{ displayText(detailData.record.reviewOwner) }}</el-descriptions-item>
            <el-descriptions-item label="评审专家">{{ detailData.reviewExperts.join('、') || '-' }}</el-descriptions-item>
            <el-descriptions-item label="评审规模">{{ detailData.record.reviewScalePages }} 页</el-descriptions-item>
            <el-descriptions-item label="评审工作产品">{{ displayText(detailData.record.reviewProduct) }}</el-descriptions-item>
            <el-descriptions-item label="作者">{{ displayText(detailData.record.authorName) }}</el-descriptions-item>
            <el-descriptions-item label="评审版本">{{ displayText(detailData.record.reviewVersion) }}</el-descriptions-item>
          </el-descriptions>
        </section>

        <section class="detail-section">
          <header class="detail-section-head">
            <span>问题概览</span>
          </header>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="问题合计">{{ detailData.record.problemCount }}</el-descriptions-item>
            <el-descriptions-item label="缺陷密度">{{ detailData.record.problemDensity.toFixed(2) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ displayText(detailData.record.updatedAt?.replace('T', ' ').slice(0, 19)) }}</el-descriptions-item>
            <el-descriptions-item label="当前状态">{{ detailData.record.deleted ? '已删除' : '有效' }}</el-descriptions-item>
          </el-descriptions>
        </section>
      </template>
    </el-drawer>

    <el-drawer
      v-model="ruleExplanationVisible"
      :title="reviewDataRuleExplanationContent.title"
      size="620px"
      append-to-body
      class="review-data-drawer review-rule-drawer"
    >
      <section class="detail-section">
        <header class="detail-section-head">
          <el-icon><InfoFilled /></el-icon>
          <span>先看说明</span>
        </header>
        <div class="rule-summary-card">
          <div class="rule-summary-main">{{ reviewDataRuleExplanationContent.summary }}</div>
          <div class="rule-summary-sub">{{ reviewDataRuleExplanationContent.scopeDescription }}</div>
          <div class="rule-summary-meta">
            <el-tag size="small" effect="plain" type="success">版本 {{ reviewDataRuleExplanationContent.version }}</el-tag>
            <el-tag size="small" effect="plain" type="info">面向录入与查看</el-tag>
          </div>
        </div>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>填写指南</span>
        </header>
        <div class="rule-card-grid">
          <article
            v-for="field in reviewDataRuleExplanationContent.fieldDefinitions"
            :key="field.key"
            class="rule-card"
          >
            <div class="rule-card-head">
              <strong class="rule-card-title">{{ field.label }}</strong>
              <el-tag size="small" effect="plain" type="info">关键字段</el-tag>
            </div>
            <div class="rule-card-definition">{{ field.description }}</div>
            <div class="rule-card-line">
              <span class="rule-card-label">建议</span>
              <span>{{ field.guidance }}</span>
            </div>
            <div v-if="field.note" class="rule-card-note">{{ field.note }}</div>
          </article>
        </div>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>数据是怎么算的</span>
        </header>
        <div class="rule-card-grid">
          <article
            v-for="metric in reviewDataRuleExplanationContent.metricDefinitions"
            :key="metric.key"
            class="rule-card"
          >
            <div class="rule-card-head">
              <strong class="rule-card-title">{{ metric.label }}</strong>
            </div>
            <div class="rule-card-definition">{{ metric.definition }}</div>
            <div class="rule-card-formula">{{ metric.formula }}</div>
            <div v-if="metric.note" class="rule-card-note">{{ metric.note }}</div>
          </article>
        </div>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>常见问题</span>
        </header>
        <div class="rule-question-list">
          <article
            v-for="question in reviewDataRuleExplanationContent.commonQuestions"
            :key="question.key"
            class="rule-question-card"
          >
            <div class="rule-question-title">{{ question.title }}</div>
            <div class="rule-question-description">{{ question.description }}</div>
          </article>
        </div>
      </section>
    </el-drawer>

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

.detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}

.detail-section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.75);
}

.rule-summary-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(59, 130, 246, 0.14);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.96), rgba(248, 250, 252, 0.98));
}

.rule-summary-main {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.88);
}

.rule-summary-sub {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.rule-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-card-grid,
.rule-question-list {
  display: grid;
  gap: 12px;
}

.rule-card,
.rule-question-card {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  background: #fff;
}

.rule-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rule-card-title,
.rule-question-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.5;
  color: rgba(15, 23, 42, 0.88);
}

.rule-card-definition,
.rule-question-description {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.72);
}

.rule-card-line {
  display: grid;
  gap: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.72);
}

.rule-card-label {
  font-size: 12px;
  font-weight: 700;
  color: #2563eb;
}

.rule-card-formula {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.04);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.82);
}

.rule-card-note {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.1);
  font-size: 12px;
  line-height: 1.7;
  color: rgba(146, 64, 14, 0.92);
}

:deep(.review-data-drawer .el-drawer__header) {
  margin-bottom: 10px;
}

:deep(.review-data-drawer .el-descriptions__label) {
  width: 116px;
}

:deep(.problem-subtable .el-table__header th.el-table__cell) {
  background: #f8fafc;
}
</style>
