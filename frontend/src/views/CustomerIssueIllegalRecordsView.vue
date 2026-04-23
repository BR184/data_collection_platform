<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { InfoFilled, Refresh } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import { api } from '../api';
import type {
  CustomerIssueIllegalRecordFilterOptionsResponse,
  CustomerIssueIllegalRecordRowResponse,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
} from '../types/api';
import { buildCustomerIssueIllegalConditionFields } from './customer-issues/customer-issue-condition-fields';
import { useRuleExplanationPanel } from '../composables/useRuleExplanationPanel';
import { useRouteTableState } from '../composables/useRouteTableState';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import type { RecordTableColumn } from '../types/record-table';

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
    sortBy: 'updatedAt',
    sortOrder: 'desc',
  },
});

const rows = ref<CustomerIssueIllegalRecordRowResponse[]>([]);
const total = ref(0);
const pageInitialized = ref(false);
const filterOptionsLoaded = ref(false);
const advancedVisible = ref(false);
const detailVisible = ref(false);
const selectedRow = ref<CustomerIssueIllegalRecordRowResponse | null>(null);
const projectId = computed(() => String(route.query.projectId ?? ''));
const pageReady = computed(() => pageInitialized.value && filterOptionsLoaded.value);

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  resetRuleExplanation,
  openRuleExplanation,
} = useRuleExplanationPanel({
  load: () => api.getCustomerIssueIllegalRecordRuleExplanation(projectId.value || undefined),
  fallback: (reason) => createFallbackRuleExplanation(reason),
});

const filterOptions = ref<CustomerIssueIllegalRecordFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  illegalReasons: [],
  severityLevels: [],
  priorityLevels: [],
  issueStates: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
});

const filterValues = computed<Record<string, unknown>>(() => {
  const createdStart = String(route.query.createdAtStart ?? '');
  const createdEnd = String(route.query.createdAtEnd ?? '');
  const updatedStart = String(route.query.updatedAtStart ?? '');
  const updatedEnd = String(route.query.updatedAtEnd ?? '');
  return {
    keyword: String(route.query.keyword ?? ''),
    illegalReason: String(route.query.illegalReason ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    updatedAtRange: updatedStart && updatedEnd ? [updatedStart, updatedEnd] : [],
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    severityLevel: String(route.query.severityLevel ?? ''),
    priorityLevel: String(route.query.priorityLevel ?? ''),
    issueState: String(route.query.issueState ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    createdAtRange: createdStart && createdEnd ? [createdStart, createdEnd] : [],
  };
});

function allOption(label: string) {
  return { label, value: '' };
}

const primaryFilters = computed<RecordTableFilterField[]>(() => [
  {
    key: 'illegalReason',
    label: '非法原因',
    type: 'select',
    width: 180,
    options: [allOption('全部非法原因'), ...filterOptions.value.illegalReasons],
  },
  {
    key: 'moduleName',
    label: '模块',
    type: 'select',
    width: 180,
    options: [allOption('全部模块'), ...filterOptions.value.moduleNames],
  },
  {
    key: 'updatedAtRange',
    label: '更新时间',
    type: 'daterange',
    width: 280,
    startPlaceholder: '开始日期',
    endPlaceholder: '结束日期',
  },
  {
    key: 'keyword',
    label: '关键字',
    type: 'input',
    width: 260,
    placeholder: '搜索编号、标题、模块、非法原因',
  },
]);

const advancedFilters = computed<RecordTableFilterField[]>(() => [
  { key: 'issueIid', label: '议题编号', type: 'input', placeholder: '输入议题编号' },
  { key: 'title', label: '标题', type: 'input', placeholder: '输入标题关键字' },
  {
    key: 'projectName',
    label: '项目',
    type: 'select',
    options: [allOption('全部项目'), ...filterOptions.value.projectNames],
  },
  {
    key: 'severityLevel',
    label: '严重程度',
    type: 'select',
    options: [allOption('全部严重程度'), ...filterOptions.value.severityLevels],
  },
  {
    key: 'priorityLevel',
    label: '优先级',
    type: 'select',
    options: [allOption('全部优先级'), ...filterOptions.value.priorityLevels],
  },
  {
    key: 'issueState',
    label: '状态',
    type: 'select',
    options: [allOption('全部状态'), ...filterOptions.value.issueStates],
  },
  {
    key: 'bugStatus',
    label: '缺陷状态',
    type: 'select',
    options: [allOption('全部缺陷状态'), ...filterOptions.value.bugStatuses],
  },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: [allOption('全部分类'), ...filterOptions.value.categories],
  },
  {
    key: 'milestoneTitle',
    label: '里程碑',
    type: 'select',
    options: [allOption('全部里程碑'), ...filterOptions.value.milestoneTitles],
  },
  {
    key: 'createdAtRange',
    label: '创建时间',
    type: 'daterange',
    width: 280,
    startPlaceholder: '开始日期',
    endPlaceholder: '结束日期',
  },
]);

const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  const values = filterValues.value;
  const tags: RecordTableActiveFilterTag[] = [];
  appendTag(tags, 'illegalReason', '非法原因', values.illegalReason);
  appendTag(tags, 'moduleName', '模块', values.moduleName);
  appendRangeTag(tags, 'updatedAtRange', '更新时间', values.updatedAtRange);
  appendTag(tags, 'keyword', '关键字', values.keyword);
  appendTag(tags, 'issueIid', '议题编号', values.issueIid);
  appendTag(tags, 'title', '标题', values.title);
  appendTag(tags, 'projectName', '项目', values.projectName);
  appendTag(tags, 'severityLevel', '严重程度', values.severityLevel);
  appendTag(tags, 'priorityLevel', '优先级', values.priorityLevel);
  appendTag(tags, 'issueState', '状态', values.issueState);
  appendTag(tags, 'bugStatus', '缺陷状态', values.bugStatus);
  appendTag(tags, 'category', '分类', values.category);
  appendTag(tags, 'milestoneTitle', '里程碑', values.milestoneTitle);
  appendRangeTag(tags, 'createdAtRange', '创建时间', values.createdAtRange);
  return tags;
});

const conditionFilterFields = computed<StatisticFilterField[]>(() =>
  buildCustomerIssueIllegalConditionFields(filterOptions.value),
);

const {
  filterDraft,
  activeFilterTags: conditionActiveFilterTags,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);

const columns = computed<RecordTableColumn[]>(() => [
  { key: 'issueIid', label: '议题编号', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'illegalReason', label: '非法原因', type: 'tag', sortable: true, minWidth: 150 },
  { key: 'projectName', label: '所属项目', sortable: true, minWidth: 150 },
  { key: 'moduleNames', label: '模块', sortable: true, minWidth: 160 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'priorityLevel', label: '优先级', type: 'tag', sortable: true, width: 100 },
  { key: 'issueState', label: '状态', type: 'tag', sortable: true, width: 100 },
  { key: 'milestoneTitle', label: '里程碑', sortable: true, minWidth: 160 },
  { key: 'authorName', label: '创建人', sortable: true, minWidth: 120 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
]);

const tableRows = computed<Record<string, unknown>[]>(() =>
  rows.value.map((row) => ({
    __raw: row,
    issueIid: row.issueIid,
    title: row.title,
    illegalReason: [{ label: row.illegalReason || '未说明', type: 'warning' as const }],
    projectName: row.projectName || '-',
    moduleNames: row.moduleNames || '-',
    severityLevel: [{ label: row.severityLevel || '-', type: 'danger' as const }],
    priorityLevel: [{ label: row.priorityLevel || '-', type: 'primary' as const }],
    issueState: [{ label: normalizeIssueState(row.issueState), type: row.closedAt ? 'info' : 'success' }],
    milestoneTitle: row.milestoneTitle || '-',
    authorName: row.authorName || '-',
    updatedAt: formatDateTime(row.updatedAt),
  })),
);

const ruleSteps = computed(() => ruleExplanation.value?.flowSteps ?? []);
const ruleFinalCount = computed(() => ruleSteps.value.at(-1)?.outputCount ?? 0);

function appendTag(tags: RecordTableActiveFilterTag[], key: string, label: string, value: unknown) {
  if (value) {
    tags.push({ key, label, value: String(value) });
  }
}

function appendRangeTag(tags: RecordTableActiveFilterTag[], key: string, label: string, value: unknown) {
  if (Array.isArray(value) && value.length === 2) {
    tags.push({ key, label, value: `${value[0]} ~ ${value[1]}` });
  }
}

function normalizeIssueState(value: string) {
  return value === 'closed' ? '已关闭' : value === 'opened' ? '未关闭' : value || '-';
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function createFallbackRuleExplanation(reason: string): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: 'customer-issue-illegal-records',
    supported: false,
    title: '客户问题缺陷非法数据规则说明',
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

async function loadFilterOptions() {
  filterOptions.value = await api.getCustomerIssueIllegalRecordFilterOptions(projectId.value || undefined);
}

async function loadTableData() {
  const response = await api.getCustomerIssueIllegalRecords({
    projectId: route.query.projectId as string | undefined,
    keyword: String(route.query.keyword ?? ''),
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    illegalReason: String(route.query.illegalReason ?? ''),
    severityLevel: String(route.query.severityLevel ?? ''),
    priorityLevel: String(route.query.priorityLevel ?? ''),
    issueState: String(route.query.issueState ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    createdAtStart: String(route.query.createdAtStart ?? ''),
    createdAtEnd: String(route.query.createdAtEnd ?? ''),
    updatedAtStart: String(route.query.updatedAtStart ?? ''),
    updatedAtEnd: String(route.query.updatedAtEnd ?? ''),
    filterGroup: buildFilterPayload(),
    page: page.value,
    size: pageSize.value,
    sortBy: sortBy.value || 'updatedAt',
    sortOrder: (sortOrder.value || 'desc') as 'asc' | 'desc',
  });
  rows.value = response.records;
  total.value = response.total;
}

bindLoader(async () => {
  try {
    initializeFromQuery(route.query);
    await loadTableData();
    pageInitialized.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '客户问题非法数据加载失败');
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
      ElMessage.error(error instanceof Error ? error.message : '客户问题非法数据筛选项加载失败');
      filterOptionsLoaded.value = true;
    }
  },
  { immediate: true },
);

async function handleFilterChange(payload: { key: string; value: string | string[] | null }) {
  if (payload.key === 'updatedAtRange') {
    const [start, end] = Array.isArray(payload.value) ? payload.value : [];
    await patchQuery({ page: 1, updatedAtStart: start || null, updatedAtEnd: end || null });
    return;
  }
  if (payload.key === 'createdAtRange') {
    const [start, end] = Array.isArray(payload.value) ? payload.value : [];
    await patchQuery({ page: 1, createdAtStart: start || null, createdAtEnd: end || null });
    return;
  }
  await patchQuery({ page: 1, [payload.key]: Array.isArray(payload.value) ? payload.value[0] ?? null : payload.value });
}

async function handleReset() {
  resetDraft();
  await patchQuery({
    ...buildResetQueryPatch(route.query),
    page: 1,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
    keyword: null,
    issueIid: null,
    title: null,
    projectName: null,
    moduleName: null,
    illegalReason: null,
    severityLevel: null,
    priorityLevel: null,
    issueState: null,
    bugStatus: null,
    category: null,
    milestoneTitle: null,
    createdAtStart: null,
    createdAtEnd: null,
    updatedAtStart: null,
    updatedAtEnd: null,
  });
}

async function handleQuery() {
  await patchQuery({
    ...buildApplyQueryPatch(route.query),
    page: 1,
    issueIid: null,
    title: null,
    projectName: null,
    moduleName: null,
    illegalReason: null,
    severityLevel: null,
    priorityLevel: null,
    issueState: null,
    bugStatus: null,
    category: null,
    milestoneTitle: null,
    createdAtStart: null,
    createdAtEnd: null,
    updatedAtStart: null,
    updatedAtEnd: null,
  });
}

async function handleKeywordSearch(nextKeyword: string) {
  await patchQuery({ page: 1, keyword: nextKeyword || null });
}

async function handleRefresh() {
  await loadTableData();
}

async function handleSizeChange(nextSize: number) {
  await patchQuery({ pageSize: nextSize, page: 1 });
}

async function handleCurrentChange(nextPage: number) {
  await patchQuery({ page: nextPage });
}

async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
  await patchQuery({
    sortBy: payload.prop || 'updatedAt',
    sortOrder: payload.order === 'ascending' ? 'asc' : 'desc',
    page: 1,
  });
}

async function handleClearFilter(key: string) {
  if (key === 'filterGroup') {
    resetDraft();
    await patchQuery({ ...buildResetQueryPatch(route.query), page: 1 });
    return;
  }
  if (key === 'updatedAtRange') {
    await patchQuery({ page: 1, updatedAtStart: null, updatedAtEnd: null });
    return;
  }
  if (key === 'createdAtRange') {
    await patchQuery({ page: 1, createdAtStart: null, createdAtEnd: null });
    return;
  }
  await patchQuery({ page: 1, [key]: null });
}

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as CustomerIssueIllegalRecordRowResponse) ?? null;
  detailVisible.value = true;
}

</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <template #skeleton>
      <section class="customer-illegal-page">
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

    <section class="customer-illegal-page">
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
        empty-description="当前筛选条件下没有客户问题非法数据。"
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
          <div class="customer-illegal-toolbar-actions">
            <el-tag effect="plain" type="warning">当前 {{ total }} 条</el-tag>
            <el-button
              plain
              size="small"
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
            >
              规则说明
            </el-button>
            <el-button plain size="small" :icon="Refresh" @click="handleRefresh">刷新</el-button>
          </div>
        </template>

        <template #row-actions="{ row }">
          <el-button class="customer-illegal-detail-trigger" link @click="openDetailDrawer(row)">查看详情</el-button>
        </template>
      </BaseRecordTable>

    <el-drawer v-model="detailVisible" size="540px" destroy-on-close class="customer-illegal-drawer">
      <template #header>
        <div v-if="selectedRow" class="customer-illegal-detail-header">
          <div>
            <div class="customer-illegal-detail-kicker">客户问题非法数据</div>
            <div class="customer-illegal-detail-title">#{{ selectedRow.issueIid }} {{ selectedRow.title }}</div>
            <div class="customer-illegal-detail-meta">
              <span>{{ selectedRow.projectName || '-' }}</span>
              <span>{{ selectedRow.milestoneTitle || '-' }}</span>
              <span>{{ selectedRow.illegalReason || '未说明原因' }}</span>
            </div>
          </div>
        </div>
      </template>

      <template v-if="selectedRow">
        <section class="customer-illegal-detail-section">
          <div class="customer-illegal-detail-section-title">基础信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="议题编号">#{{ selectedRow.issueIid }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ normalizeIssueState(selectedRow.issueState) }}</el-descriptions-item>
            <el-descriptions-item label="项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="里程碑">{{ selectedRow.milestoneTitle || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模块">{{ selectedRow.moduleNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建人">{{ selectedRow.authorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="严重程度">{{ selectedRow.severityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ selectedRow.priorityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(selectedRow.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(selectedRow.updatedAt) }}</el-descriptions-item>
          </el-descriptions>
        </section>

        <section class="customer-illegal-detail-section">
          <div class="customer-illegal-detail-section-title">非法判定</div>
          <div class="customer-illegal-reason-card">{{ selectedRow.illegalReason || '未说明原因' }}</div>
        </section>

        <section class="customer-illegal-detail-section">
          <div class="customer-illegal-detail-section-title">标签</div>
          <div class="customer-illegal-tags">
            <el-tag v-for="label in selectedRow.labels" :key="label" effect="plain" size="small">{{ label }}</el-tag>
            <span v-if="!selectedRow.labels.length">-</span>
          </div>
        </section>
      </template>
    </el-drawer>

    <el-drawer v-model="ruleExplanationVisible" size="42%" destroy-on-close class="customer-illegal-drawer">
      <template #header>
        <div class="customer-illegal-detail-header">
          <div>
            <div class="customer-illegal-detail-kicker">规则说明</div>
            <div class="customer-illegal-detail-title">{{ ruleExplanation?.title || '客户问题缺陷非法数据规则说明' }}</div>
          </div>
        </div>
      </template>

      <div v-loading="ruleExplanationLoading" class="customer-illegal-rule-panel">
        <el-empty v-if="!ruleExplanation?.supported" :description="ruleExplanation?.unsupportedReason || '当前暂不支持规则说明。'" />
        <template v-else>
          <section class="customer-illegal-rule-summary">
            <strong>当前筛出 {{ ruleFinalCount }} 条客户问题非法数据。</strong>
            <span>{{ ruleExplanation.summary }}</span>
          </section>
          <section class="customer-illegal-detail-section">
            <div class="customer-illegal-detail-section-title">统计范围</div>
            <div class="customer-illegal-reason-card">{{ ruleExplanation.scopeDescription }}</div>
          </section>
          <section class="customer-illegal-detail-section">
            <div class="customer-illegal-detail-section-title">处理步骤</div>
            <article v-for="step in ruleSteps" :key="step.key" class="customer-illegal-step-card">
              <div class="customer-illegal-step-title">{{ step.title }}</div>
              <div class="customer-illegal-step-desc">{{ step.description }}</div>
              <div class="customer-illegal-step-count">输入 {{ step.inputCount }} 条，输出 {{ step.outputCount }} 条</div>
            </article>
          </section>
        </template>
      </div>
    </el-drawer>
    </section>
  </PageStateShell>
</template>

<style scoped>
.customer-illegal-page {
  display: grid;
  gap: 12px;
}

.customer-illegal-toolbar-actions,
.customer-illegal-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.customer-illegal-detail-trigger {
  padding-inline: 0;
  font-weight: 500;
  color: rgba(37, 99, 235, 0.88);
}

.customer-illegal-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.customer-illegal-drawer :deep(.el-drawer__body) {
  background: #fafafa;
}

.customer-illegal-detail-header {
  display: grid;
  gap: 8px;
}

.customer-illegal-detail-kicker {
  font-size: 12px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.42);
}

.customer-illegal-detail-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
  color: rgba(15, 23, 42, 0.94);
}

.customer-illegal-detail-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: rgba(15, 23, 42, 0.56);
  font-size: 13px;
}

.customer-illegal-detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.customer-illegal-detail-section-title {
  font-size: 12px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.customer-illegal-reason-card,
.customer-illegal-step-card,
.customer-illegal-rule-summary {
  display: grid;
  gap: 8px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.76);
  line-height: 1.7;
}

.customer-illegal-rule-panel {
  display: grid;
  gap: 14px;
}

.customer-illegal-step-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.customer-illegal-step-desc,
.customer-illegal-step-count {
  font-size: 13px;
  color: rgba(15, 23, 42, 0.66);
}
</style>
