<script setup lang="ts">
import { computed, ref, watch } from 'vue';
// 非法记录通用页承接系统测试和客户问题两类场景，差异通过配置和接口适配传入。
// 页面内部统一处理关键词、条件筛选、规则说明、分页和导出，避免两个业务域重复实现。
import { ElMessage } from '../../element-plus-services';
import { Download, InfoFilled, Refresh } from '@element-plus/icons-vue';
import BaseRecordTable from '../../components/base/BaseRecordTable.vue';
import PageStateShell from '../../components/base/PageStateShell.vue';
import RuleExplanationDrawer from '../../components/RuleExplanationDrawer.vue';
import StatisticFilterBuilder from '../../components/StatisticFilterBuilder.vue';
import { useConditionFilterGroupState } from '../../composables/useConditionFilterGroupState';
import { useRecordPageController } from '../../composables/useRecordPageController';
import { useDataScope } from '../../composables/useDataScope';
import { useRouteTableState } from '../../composables/useRouteTableState';
import { useRuleExplanationPanel } from '../../composables/useRuleExplanationPanel';
import type { StatisticBoardRuleExplanationResponse, StatisticFilterField } from '../../types/api';
import type { IssueIllegalRecordRow, IssueIllegalRecordsPageConfig } from './issue-illegal-records-types';
import { downloadCsv, formatExportFileDate } from '../../utils/csv-download';

const props = defineProps<IssueIllegalRecordsPageConfig>();

const {
  route,
  page,
  pageSize,
  sortBy,
  sortOrder,
  patchQuery,
  bindLoader,
  isTableLoading,
} = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: props.defaultSortBy ?? 'updatedAt',
    sortOrder: props.defaultSortOrder ?? 'desc',
  },
});

const rows = ref<IssueIllegalRecordRow[]>([]);
const total = ref(0);
const pageInitialized = ref(false);
const filterOptionsLoaded = ref(false);
const detailVisible = ref(false);
const selectedRow = ref<IssueIllegalRecordRow | null>(null);
const exportLoading = ref(false);
const projectId = computed(() => String(route.query.projectId ?? ''));
const pageReady = computed(() => pageInitialized.value && filterOptionsLoaded.value);
const filterOptions = ref({ ...props.initialFilterOptions });

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  resetRuleExplanation,
  openRuleExplanation,
} = useRuleExplanationPanel({
  load: () => props.loadRuleExplanation(projectId.value || undefined),
  fallback: (reason) => createFallbackRuleExplanation(reason),
});

const conditionFilterFields = computed<StatisticFilterField[]>(() => {
  const hiddenKeys = props.scopeProvider ? new Set([props.scopeProvider.queryKey]) : new Set<string>();
  return props.buildConditionFields(filterOptions.value).filter((field) => !hiddenKeys.has(field.key));
});

const scopeOptions = computed(() => props.buildScopeOptions?.(filterOptions.value) ?? []);
useDataScope({
  provider: props.scopeProvider,
  options: scopeOptions,
  mountToShell: true,
});

const {
  filterDraft,
  activeFilterTags: conditionActiveFilterTags,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);

const {
  handleReset,
  handleQuery,
  handleKeywordSearch,
  handleRefresh,
  handleSizeChange,
  handleCurrentChange,
  handleSortChange,
  handleClearFilter,
} = useRecordPageController({
  getRouteQuery: () => route.query,
  patchQuery,
  loadTableData,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  defaultSortBy: props.defaultSortBy ?? 'updatedAt',
  defaultSortOrder: props.defaultSortOrder ?? 'desc',
  resetClearKeys: props.resetClearKeys,
  queryClearKeys: props.queryClearKeys,
  rangeKeys: {
    updatedAtRange: { startKey: 'updatedAtStart', endKey: 'updatedAtEnd' },
    createdAtRange: { startKey: 'createdAtStart', endKey: 'createdAtEnd' },
  },
});

const tableRows = computed<Record<string, unknown>[]>(() => rows.value.map((row) => props.mapRow(row)));
const ruleSteps = computed(() => ruleExplanation.value?.flowSteps ?? []);
const ruleFirstCount = computed(() => ruleSteps.value[0]?.inputCount ?? 0);
const ruleFinalCount = computed(() => ruleSteps.value.at(-1)?.outputCount ?? 0);
const ruleRetainedRate = computed(() =>
  ruleFirstCount.value ? `${((ruleFinalCount.value / ruleFirstCount.value) * 100).toFixed(1)}%` : '0%',
);
const ruleOverviewCards = computed(() => [
  { label: '原始数据', value: ruleFirstCount.value },
  { label: '最终筛出', value: ruleFinalCount.value },
  { label: '筛出比例', value: ruleRetainedRate.value },
]);

function createFallbackRuleExplanation(reason: string): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: props.workspaceKey,
    supported: false,
    title: props.ruleTitle,
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

function normalizeIssueState(value: string) {
  return value === 'closed' ? '已关闭' : value === 'opened' ? '未关闭' : value || '-';
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

async function loadFilterOptions() {
  filterOptions.value = await props.loadFilterOptions(projectId.value || undefined);
}

async function loadTableData() {
  const response = await props.loadRecords(buildCurrentQueryParams(true));
  rows.value = response.records;
  total.value = response.total;
}

function buildCurrentQueryParams(includePagination: boolean) {
  return {
    projectId: route.query.projectId as string | undefined,
    keyword: String(route.query.keyword ?? ''),
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    testingPhase: String(route.query.testingPhase ?? ''),
    illegalReason: String(route.query.illegalReason ?? ''),
    severityLevel: String(route.query.severityLevel ?? ''),
    priorityLevel: String(route.query.priorityLevel ?? ''),
    issueState: String(route.query.issueState ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    authorName: String(route.query.authorName ?? ''),
    assigneeName: String(route.query.assigneeName ?? ''),
    createdAtStart: String(route.query.createdAtStart ?? ''),
    createdAtEnd: String(route.query.createdAtEnd ?? ''),
    updatedAtStart: String(route.query.updatedAtStart ?? ''),
    updatedAtEnd: String(route.query.updatedAtEnd ?? ''),
    filterGroup: buildFilterPayload(),
    ...(includePagination ? { page: page.value, size: pageSize.value } : {}),
    sortBy: sortBy.value || props.defaultSortBy || 'updatedAt',
    sortOrder: (sortOrder.value || props.defaultSortOrder || 'desc') as 'asc' | 'desc',
  };
}

async function handleExport() {
  if (!props.exportRecords) {
    return;
  }
  exportLoading.value = true;
  try {
    const csv = await props.exportRecords(buildCurrentQueryParams(false));
    const filenamePrefix = props.exportFilenamePrefix || props.title;
    downloadCsv(csv, `${filenamePrefix}_${formatExportFileDate(new Date())}.csv`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败');
  } finally {
    exportLoading.value = false;
  }
}

bindLoader(async () => {
  try {
    initializeFromQuery(route.query);
    await loadTableData();
    pageInitialized.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${props.title}加载失败`);
    rows.value = [];
    total.value = 0;
    pageInitialized.value = true;
  }
});

watch(
  projectId,
  async () => {
    resetRuleExplanation();
    try {
      await loadFilterOptions();
      filterOptionsLoaded.value = true;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : `${props.title}筛选项加载失败`);
      filterOptionsLoaded.value = true;
    }
  },
  { immediate: true },
);

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as IssueIllegalRecordRow) ?? null;
  detailVisible.value = true;
}
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <template #skeleton>
      <section class="issue-illegal-page">
        <el-card shadow="never" class="panel-card page-skeleton-card">
          <el-skeleton animated>
            <template #template>
              <div class="page-skeleton-stack">
                <el-skeleton-item variant="h3" style="width: 28%" />
                <el-skeleton-item variant="text" style="width: 52%" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 56px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 420px" />
              </div>
            </template>
          </el-skeleton>
        </el-card>
      </section>
    </template>

    <section class="issue-illegal-page">
      <BaseRecordTable
        :columns="columns"
        :rows="tableRows"
        :loading="isTableLoading"
        :keyword-auto-search="true"
        :page="page"
        :page-size="pageSize"
        :total="total"
        :active-filter-tags="conditionActiveFilterTags"
        :keyword="String(route.query.keyword ?? '')"
        search-placeholder="输入关键字快速搜索"
        :show-search="true"
        :show-refresh="false"
        :empty-description="emptyDescription"
        @reset="handleReset"
        @search="handleKeywordSearch"
        @query="handleQuery"
        @clear-filter="handleClearFilter"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        @sort-change="handleSortChange"
      >
        <template #filter-builder>
          <StatisticFilterBuilder
            :model-value="filterDraft"
            :fields="conditionFilterFields"
            add-button-text="添加条件"
          />
        </template>

        <template #primary-actions>
          <div class="issue-illegal-toolbar-actions customer-illegal-toolbar-actions">
            <el-tag effect="plain" type="warning">{{ totalTagText(total) }}</el-tag>
            <el-button
              plain
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
            >
              规则说明
            </el-button>
            <el-button plain :icon="Refresh" @click="handleRefresh">刷新</el-button>
            <el-button
              v-if="exportRecords"
              plain
              :icon="Download"
              :loading="exportLoading"
              @click="handleExport"
            >
              导出
            </el-button>
          </div>
        </template>

        <template #row-actions="{ row }">
          <el-button class="issue-illegal-detail-trigger customer-illegal-detail-trigger" link @click="openDetailDrawer(row)">
            查看详情
          </el-button>
        </template>
      </BaseRecordTable>

      <el-drawer v-model="detailVisible" size="540px" destroy-on-close class="issue-illegal-drawer customer-illegal-drawer">
        <template #header>
          <div v-if="selectedRow" class="issue-illegal-detail-header customer-illegal-detail-header">
            <div>
              <div class="issue-illegal-detail-kicker customer-illegal-detail-kicker">{{ detailKicker }}</div>
              <div class="issue-illegal-detail-title customer-illegal-detail-title">
                #{{ selectedRow.issueIid }} {{ selectedRow.title }}
              </div>
              <div class="issue-illegal-detail-meta customer-illegal-detail-meta">
                <span>{{ selectedRow.projectName || '-' }}</span>
                <span>{{ selectedRow.testingPhase || selectedRow.milestoneTitle || '-' }}</span>
                <span>{{ selectedRow.illegalReason || '未说明原因' }}</span>
              </div>
            </div>
          </div>
        </template>

        <template v-if="selectedRow">
          <section class="issue-illegal-detail-section customer-illegal-detail-section">
            <div class="issue-illegal-detail-section-title customer-illegal-detail-section-title">基础信息</div>
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="议题编号">#{{ selectedRow.issueIid }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{ normalizeIssueState(selectedRow.issueState) }}</el-descriptions-item>
              <el-descriptions-item label="项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="selectedRow.testingPhase" label="测试阶段">
                {{ selectedRow.testingPhase }}
              </el-descriptions-item>
              <el-descriptions-item label="里程碑">{{ selectedRow.milestoneTitle || '-' }}</el-descriptions-item>
              <el-descriptions-item label="模块">{{ selectedRow.moduleNames || '-' }}</el-descriptions-item>
              <el-descriptions-item label="创建人">{{ selectedRow.authorName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="处理人">{{ selectedRow.assigneeName || '-' }}</el-descriptions-item>
              <el-descriptions-item label="严重程度">{{ selectedRow.severityLevel || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="selectedRow.priorityLevel" label="优先级">
                {{ selectedRow.priorityLevel }}
              </el-descriptions-item>
              <el-descriptions-item label="缺陷状态">{{ selectedRow.bugStatus || '-' }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ selectedRow.category || '-' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ formatDateTime(selectedRow.createdAt) }}</el-descriptions-item>
              <el-descriptions-item label="更新时间">{{ formatDateTime(selectedRow.updatedAt) }}</el-descriptions-item>
            </el-descriptions>
          </section>

          <section class="issue-illegal-detail-section customer-illegal-detail-section">
            <div class="issue-illegal-detail-section-title customer-illegal-detail-section-title">非法判定</div>
            <div class="issue-illegal-reason-card customer-illegal-reason-card">
              {{ selectedRow.illegalReason || '未说明原因' }}
            </div>
          </section>

          <section class="issue-illegal-detail-section customer-illegal-detail-section">
            <div class="issue-illegal-detail-section-title customer-illegal-detail-section-title">标签</div>
            <div class="issue-illegal-tags customer-illegal-tags">
              <el-tag v-for="label in selectedRow.labels" :key="label" effect="plain" size="small">{{ label }}</el-tag>
              <span v-if="!selectedRow.labels.length">-</span>
            </div>
          </section>
        </template>
      </el-drawer>

      <RuleExplanationDrawer
        v-model="ruleExplanationVisible"
        :loading="ruleExplanationLoading"
        :title="ruleExplanation?.title || ruleTitle"
        :supported="Boolean(ruleExplanation?.supported)"
        :unsupported-reason="ruleExplanation?.unsupportedReason || '当前暂不支持规则说明。'"
        :summary-main="totalTagText(ruleFinalCount)"
        :summary="ruleExplanation?.summary"
        :overview-cards="ruleOverviewCards"
        :info-items="[
          { label: '当前使用规则版本', value: ruleExplanation?.version },
          { label: '统计范围', value: ruleExplanation?.scopeDescription },
        ]"
        :exclusion-steps="ruleSteps"
        :process-steps="ruleSteps"
        :metrics="ruleExplanation?.metricDefinitions ?? []"
        exclusion-title="处理步骤"
        process-title="数据是怎么一步步变化的"
        metrics-title="指标定义"
      />
    </section>
  </PageStateShell>
</template>

<style scoped>
.issue-illegal-page {
  display: grid;
  gap: 12px;
}

.issue-illegal-toolbar-actions,
.issue-illegal-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.issue-illegal-detail-trigger {
  padding-inline: 0;
  font-weight: 500;
  color: rgba(37, 99, 235, 0.88);
}

.issue-illegal-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.issue-illegal-drawer :deep(.el-drawer__body) {
  background: #fafafa;
}

.issue-illegal-detail-header {
  display: grid;
  gap: 12px;
}

.issue-illegal-detail-kicker {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.48);
}

.issue-illegal-detail-title {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.92);
}

.issue-illegal-detail-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.54);
}

.issue-illegal-detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}

.issue-illegal-detail-section-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.78);
}

.issue-illegal-reason-card {
  padding: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
}
</style>
