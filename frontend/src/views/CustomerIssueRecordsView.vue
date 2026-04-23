<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { InfoFilled } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import {
  api,
  type CustomerIssueRecordFilterOptionsResponse,
  type CustomerIssueRecordRowResponse,
  type CustomerIssueRecordTopic,
  type StatisticBoardRuleExplanationResponse,
} from '../api';
import { useRouteTableState } from '../composables/useRouteTableState';
import type { RecordTableActiveFilterTag, RecordTableColumn, RecordTableFilterField } from '../types/record-table';

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

const rows = ref<CustomerIssueRecordRowResponse[]>([]);
const total = ref(0);
const pageInitialized = ref(false);
const filterOptionsLoaded = ref(false);
const advancedVisible = ref(false);
const detailVisible = ref(false);
const ruleExplanationVisible = ref(false);
const ruleExplanationLoading = ref(false);
const selectedRow = ref<CustomerIssueRecordRowResponse | null>(null);
const ruleExplanation = ref<StatisticBoardRuleExplanationResponse | null>(null);

const filterOptions = ref<CustomerIssueRecordFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  reasonCategories: [],
  severityLevels: [],
  priorityLevels: [],
  issueStates: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
});

const topic = computed<CustomerIssueRecordTopic>(() =>
  route.meta.pageKey === 'customer-issues-delay-issues' ? 'delay' : 'cc-product',
);
const projectId = computed(() => String(route.query.projectId ?? ''));
const pageReady = computed(() => pageInitialized.value && filterOptionsLoaded.value);
const isDelayTopic = computed(() => topic.value === 'delay');
const pageTitle = computed(() => (isDelayTopic.value ? '延期问题明细' : 'CC_PRODUCT 议题明细'));
const pageDescription = computed(() =>
  isDelayTopic.value
    ? '客户问题范围内已申请延期、响应延期或解决延期的议题'
    : '客户问题范围内的 CC_PRODUCT 议题清单',
);
const emptyDescription = computed(() =>
  isDelayTopic.value ? '当前筛选条件下没有延期问题。' : '当前筛选条件下没有 CC_PRODUCT 议题。',
);

const filterValues = computed<Record<string, unknown>>(() => {
  const createdStart = String(route.query.createdAtStart ?? '');
  const createdEnd = String(route.query.createdAtEnd ?? '');
  const updatedStart = String(route.query.updatedAtStart ?? '');
  const updatedEnd = String(route.query.updatedAtEnd ?? '');
  return {
    keyword: String(route.query.keyword ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    reasonCategory: String(route.query.reasonCategory ?? ''),
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
    key: 'moduleName',
    label: '模块',
    type: 'select',
    width: 180,
    options: [allOption('全部模块'), ...filterOptions.value.moduleNames],
  },
  {
    key: 'reasonCategory',
    label: '缺陷原因',
    type: 'select',
    width: 180,
    options: [allOption('全部缺陷原因'), ...filterOptions.value.reasonCategories],
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
    placeholder: '搜索编号、标题、模块、原因',
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
  appendTag(tags, 'moduleName', '模块', values.moduleName);
  appendTag(tags, 'reasonCategory', '缺陷原因', values.reasonCategory);
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

const columns = computed<RecordTableColumn[]>(() => [
  { key: 'issueIid', label: '议题编号', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'moduleNames', label: '模块', sortable: true, minWidth: 150 },
  { key: 'reasonCategory', label: '缺陷原因', type: 'tag', sortable: true, minWidth: 140 },
  { key: 'delayFlags', label: '延期标记', type: 'tags', minWidth: 180 },
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
    moduleNames: row.moduleNames || '-',
    reasonCategory: [{ label: row.reasonCategory || '未归因', type: row.reasonCategory ? 'primary' as const : 'info' as const }],
    delayFlags: buildDelayFlags(row),
    severityLevel: [{ label: row.severityLevel || '-', type: 'danger' as const }],
    priorityLevel: [{ label: row.priorityLevel || '-', type: 'primary' as const }],
    issueState: [{ label: normalizeIssueState(row.issueState), type: row.closedAt ? 'info' as const : 'success' as const }],
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

function buildDelayFlags(row: CustomerIssueRecordRowResponse) {
  const flags = [];
  if (row.delayIssue) flags.push({ label: '申请延期', type: 'warning' as const });
  if (row.responseDelayed) flags.push({ label: '响应延期', type: 'danger' as const });
  if (row.resolveDelayed) flags.push({ label: '解决延期', type: 'danger' as const });
  return flags;
}

function normalizeIssueState(value: string) {
  return value === 'closed' ? '已关闭' : value === 'opened' ? '未关闭' : value || '-';
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function createFallbackRuleExplanation(reason: string): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: `customer-issue-${topic.value}-records`,
    supported: false,
    title: `${pageTitle.value}规则说明`,
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

async function loadFilterOptions() {
  filterOptions.value = await api.getCustomerIssueRecordFilterOptions(topic.value, route.query.projectId as string | undefined);
}

async function loadRuleExplanation() {
  ruleExplanationLoading.value = true;
  try {
    ruleExplanation.value = await api.getCustomerIssueRecordRuleExplanation(topic.value, projectId.value || undefined);
  } catch {
    ruleExplanation.value = createFallbackRuleExplanation('规则说明加载失败，请稍后重试。');
  } finally {
    ruleExplanationLoading.value = false;
  }
}

async function loadTableData() {
  const response = await api.getCustomerIssueRecords({
    topic: topic.value,
    projectId: route.query.projectId as string | undefined,
    keyword: String(route.query.keyword ?? ''),
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    reasonCategory: String(route.query.reasonCategory ?? ''),
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
    await loadTableData();
    pageInitialized.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${pageTitle.value}加载失败`);
    rows.value = [];
    total.value = 0;
    pageInitialized.value = true;
  }
});

watch(
  [topic, projectId],
  async () => {
    ruleExplanation.value = null;
    try {
      await loadFilterOptions();
      filterOptionsLoaded.value = true;
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : `${pageTitle.value}筛选项加载失败`);
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
  await patchQuery({
    page: 1,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
    keyword: null,
    issueIid: null,
    title: null,
    projectName: null,
    moduleName: null,
    reasonCategory: null,
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
  await patchQuery({ page: 1 });
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
  selectedRow.value = (row.__raw as CustomerIssueRecordRowResponse) ?? null;
  detailVisible.value = true;
}

async function openRuleExplanation() {
  ruleExplanationVisible.value = true;
  if (!ruleExplanation.value && !ruleExplanationLoading.value) {
    await loadRuleExplanation();
  }
}
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <template #skeleton>
      <section class="customer-record-page">
        <el-card shadow="never" class="panel-card page-skeleton-card">
          <el-skeleton animated>
            <template #template>
              <div class="page-skeleton-stack">
                <el-skeleton-item variant="h3" style="width: 30%" />
                <el-skeleton-item variant="text" style="width: 58%" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 56px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 420px" />
              </div>
            </template>
          </el-skeleton>
        </el-card>
      </section>
    </template>

    <section class="customer-record-page">
      <BaseRecordTable
        :columns="columns"
        :rows="tableRows"
        :loading="isTableLoading"
        :loading-delay="180"
        :keyword-auto-search="true"
        :page="page"
        :page-size="pageSize"
        :total="total"
        :primary-filters="primaryFilters"
        :advanced-filters="advancedFilters"
        :filter-values="filterValues"
        :active-filter-tags="activeFilterTags"
        :advanced-visible="advancedVisible"
        :show-search="false"
        :show-refresh="false"
        :empty-description="emptyDescription"
        @filter-change="handleFilterChange"
        @reset="handleReset"
        @query="handleQuery"
        @clear-filter="handleClearFilter"
        @update:advanced-visible="advancedVisible = $event"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
        @sort-change="handleSortChange"
      >
        <template #toolbar-prefix>
          <div class="customer-record-toolbar-meta">
            <div class="customer-record-toolbar-title">{{ pageTitle }}</div>
            <div class="customer-record-toolbar-desc">{{ pageDescription }}</div>
          </div>
        </template>

        <template #toolbar-actions>
          <div class="customer-record-toolbar-actions">
            <el-tag effect="plain" :type="isDelayTopic ? 'warning' : 'primary'">当前 {{ total }} 条</el-tag>
            <el-button
              plain
              size="small"
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
            >
              规则说明
            </el-button>
          </div>
        </template>

        <template #row-actions="{ row }">
          <el-button class="customer-record-detail-trigger" link @click="openDetailDrawer(row)">查看详情</el-button>
        </template>
      </BaseRecordTable>

    <el-drawer v-model="detailVisible" size="560px" destroy-on-close class="customer-record-drawer">
      <template #header>
        <div v-if="selectedRow" class="customer-record-detail-header">
          <div class="customer-record-detail-kicker">{{ pageTitle }}</div>
          <div class="customer-record-detail-title">#{{ selectedRow.issueIid }} {{ selectedRow.title }}</div>
          <div class="customer-record-detail-meta">
            <span>{{ selectedRow.projectName || '-' }}</span>
            <span>{{ selectedRow.milestoneTitle || '-' }}</span>
            <span>{{ selectedRow.reasonCategory || '未归因' }}</span>
          </div>
        </div>
      </template>

      <template v-if="selectedRow">
        <section class="customer-record-detail-section">
          <div class="customer-record-detail-section-title">基础信息</div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="议题编号">#{{ selectedRow.issueIid }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ normalizeIssueState(selectedRow.issueState) }}</el-descriptions-item>
            <el-descriptions-item label="项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="里程碑">{{ selectedRow.milestoneTitle || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模块">{{ selectedRow.moduleNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="缺陷原因">{{ selectedRow.reasonCategory || '未归因' }}</el-descriptions-item>
            <el-descriptions-item label="严重程度">{{ selectedRow.severityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ selectedRow.priorityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建人">{{ selectedRow.authorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="指派人">{{ selectedRow.assigneeName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(selectedRow.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(selectedRow.updatedAt) }}</el-descriptions-item>
          </el-descriptions>
        </section>

        <section class="customer-record-detail-section">
          <div class="customer-record-detail-section-title">延期与异常</div>
          <div class="customer-record-tags">
            <el-tag v-for="flag in buildDelayFlags(selectedRow)" :key="flag.label" :type="flag.type" effect="plain">{{ flag.label }}</el-tag>
            <el-tag v-if="selectedRow.illegal" type="warning" effect="plain">{{ selectedRow.illegalReason || '非法数据' }}</el-tag>
            <span v-if="!buildDelayFlags(selectedRow).length && !selectedRow.illegal">-</span>
          </div>
        </section>

        <section class="customer-record-detail-section">
          <div class="customer-record-detail-section-title">标签</div>
          <div class="customer-record-tags">
            <el-tag v-for="label in selectedRow.labels" :key="label" effect="plain" size="small">{{ label }}</el-tag>
            <span v-if="!selectedRow.labels.length">-</span>
          </div>
        </section>
      </template>
    </el-drawer>

    <el-drawer v-model="ruleExplanationVisible" size="42%" destroy-on-close class="customer-record-drawer">
      <template #header>
        <div class="customer-record-detail-header">
          <div class="customer-record-detail-kicker">规则说明</div>
          <div class="customer-record-detail-title">{{ ruleExplanation?.title || `${pageTitle}规则说明` }}</div>
        </div>
      </template>

      <div v-loading="ruleExplanationLoading" class="customer-record-rule-panel">
        <el-empty v-if="!ruleExplanation?.supported" :description="ruleExplanation?.unsupportedReason || '当前暂不支持规则说明。'" />
        <template v-else>
          <section class="customer-record-rule-summary">
            <strong>当前筛出 {{ ruleFinalCount }} 条记录。</strong>
            <span>{{ ruleExplanation.summary }}</span>
          </section>
          <section class="customer-record-detail-section">
            <div class="customer-record-detail-section-title">统计范围</div>
            <div class="customer-record-card">{{ ruleExplanation.scopeDescription }}</div>
          </section>
          <section class="customer-record-detail-section">
            <div class="customer-record-detail-section-title">处理步骤</div>
            <article v-for="step in ruleSteps" :key="step.key" class="customer-record-step-card">
              <div class="customer-record-step-title">{{ step.title }}</div>
              <div class="customer-record-step-desc">{{ step.description }}</div>
              <div class="customer-record-step-count">输入 {{ step.inputCount }} 条，输出 {{ step.outputCount }} 条</div>
            </article>
          </section>
        </template>
      </div>
    </el-drawer>
    </section>
  </PageStateShell>
</template>

<style scoped>
.customer-record-page {
  display: grid;
  gap: 12px;
}

.customer-record-toolbar-meta {
  display: grid;
  gap: 2px;
}

.customer-record-toolbar-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.92);
}

.customer-record-toolbar-desc {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.48);
}

.customer-record-toolbar-actions,
.customer-record-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.customer-record-detail-trigger {
  padding-inline: 0;
  font-weight: 500;
  color: rgba(37, 99, 235, 0.88);
}

.customer-record-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.customer-record-drawer :deep(.el-drawer__body) {
  background: #fafafa;
}

.customer-record-detail-header {
  display: grid;
  gap: 8px;
}

.customer-record-detail-kicker {
  font-size: 12px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.42);
}

.customer-record-detail-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
  color: rgba(15, 23, 42, 0.94);
}

.customer-record-detail-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  color: rgba(15, 23, 42, 0.56);
  font-size: 13px;
}

.customer-record-detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.customer-record-detail-section-title {
  font-size: 12px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.customer-record-card,
.customer-record-step-card,
.customer-record-rule-summary {
  display: grid;
  gap: 8px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.76);
  line-height: 1.7;
}

.customer-record-rule-panel {
  display: grid;
  gap: 14px;
}

.customer-record-step-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.customer-record-step-desc,
.customer-record-step-count {
  font-size: 13px;
  color: rgba(15, 23, 42, 0.66);
}
</style>
