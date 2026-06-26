<script setup lang="ts">
import { computed, ref } from 'vue';
// 系统测试议题查询页承担 issue_fact 的明细检索入口，路由参数就是可分享的查询状态。
// 组件内部只处理页面交互，阶段、模块和非法规则的口径由共享条件字段提供。
import { ElMessage } from '../element-plus-services';
import { Download, RefreshRight } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import PageSettingsButton from '../components/PageSettingsButton.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import { buildIssueSeverityTag, displayIssueSeverity } from '../utils/issue-severity-display';
import { downloadCsv, formatExportFileDate } from '../utils/csv-download';
import type {
  StatisticFilterField,
  SystemTestIssueSearchFilterOptionsResponse,
  SystemTestIssueSearchRowResponse,
} from '../types/api';
import { useRouteTableState } from '../composables/useRouteTableState';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { ISSUE_RECORD_QUERY_KEYS } from '../composables/record-route-query-keys';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import type {
  RecordTableActiveFilterTag,
  RecordTableColumn,
  RecordTableFilterField,
} from '../types/record-table';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { buildSystemTestIssueSearchConditionFields } from './system-test/system-test-condition-fields';

const PAGE_SCOPE_KEY = 'record-page:system-test-issue-search';
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(PAGE_SCOPE_KEY);
const { route, page, pageSize, sortBy, sortOrder, patchQuery, bindLoader, isTableLoading } =
  useRouteTableState({
    defaults: {
      page: 1,
      pageSize: 20,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
    },
    watchedQueryKeys: ISSUE_RECORD_QUERY_KEYS,
    autoRefreshOnEnter: readAutoRefreshOnEnter,
  });

const rows = ref<SystemTestIssueSearchRowResponse[]>([]);
const total = ref(0);
const exportLoading = ref(false);
const realtimeRefreshLoading = ref(false);
const canRefreshLatestData = computed(() => authState.currentUser.role === 'ADMIN');
const filterOptions = ref<SystemTestIssueSearchFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  functionNames: [],
  testingPhases: [],
  authorNames: [],
  assigneeNames: [],
  issueStates: [],
  severityLevels: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
});
const testingPhaseDefaultReady = computed(() =>
  parseMultiQueryValue(route.query.testingPhase).length > 0
    || Boolean(filterOptions.value.testingPhases.find((option) => option.value)),
);
const conditionFilterFields = computed<StatisticFilterField[]>(() =>
  buildSystemTestIssueSearchConditionFields(filterOptions.value),
);
const {
  filterDraft,
  activeFilterTags: conditionActiveFilterTags,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  buildConditionApplyQueryPatch,
  buildConditionResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getSystemTestIssueSearchRealtimeStatus(),
  emptyText: '-',
});

const filterValues = computed<Record<string, unknown>>(() => {
  const createdAtStart = String(route.query.createdAtStart ?? '');
  const createdAtEnd = String(route.query.createdAtEnd ?? '');
  const updatedAtStart = String(route.query.updatedAtStart ?? '');
  const updatedAtEnd = String(route.query.updatedAtEnd ?? '');
  return {
    testingPhase: parseMultiQueryValue(route.query.testingPhase),
    moduleName: String(route.query.moduleName ?? ''),
    functionName: String(route.query.functionName ?? ''),
    updatedAtRange: updatedAtStart && updatedAtEnd ? [updatedAtStart, updatedAtEnd] : [],
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    authorName: String(route.query.authorName ?? ''),
    assigneeName: String(route.query.assigneeName ?? ''),
    issueState: String(route.query.issueState ?? ''),
    severityLevel: String(route.query.severityLevel ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    createdAtRange: createdAtStart && createdAtEnd ? [createdAtStart, createdAtEnd] : [],
  };
});

const primaryFilters = computed<RecordTableFilterField[]>(() => [
  { key: 'issueIid', label: '议题编号', type: 'input', placeholder: '输入议题编号' },
  { key: 'title', label: '标题', type: 'input', placeholder: '输入标题关键字' },
  {
    key: 'projectName',
    label: '项目名称',
    type: 'select',
    options: [{ label: '全部项目', value: '' }, ...filterOptions.value.projectNames],
  },
  {
    key: 'authorName',
    label: '创建人',
    type: 'select',
    options: [{ label: '全部创建人', value: '' }, ...filterOptions.value.authorNames],
  },
  {
    key: 'assigneeName',
    label: '处理人',
    type: 'select',
    options: [{ label: '全部处理人', value: '' }, ...filterOptions.value.assigneeNames],
  },
  {
    key: 'issueState',
    label: '状态',
    type: 'select',
    options: [{ label: '全部状态', value: '' }, ...filterOptions.value.issueStates],
  },
  {
    key: 'severityLevel',
    label: '严重程度',
    type: 'select',
    options: [{ label: '全部严重程度', value: '' }, ...filterOptions.value.severityLevels],
  },
  {
    key: 'bugStatus',
    label: '缺陷状态',
    type: 'select',
    options: [{ label: '全部缺陷状态', value: '' }, ...filterOptions.value.bugStatuses],
  },
  {
    key: 'category',
    label: '缺陷分类',
    type: 'select',
    options: [{ label: '全部缺陷分类', value: '' }, ...filterOptions.value.categories],
  },
  {
    key: 'milestoneTitle',
    label: '里程碑',
    type: 'select',
    options: [{ label: '全部里程碑', value: '' }, ...filterOptions.value.milestoneTitles],
  },
  {
    key: 'createdAtRange',
    label: '创建时间',
    type: 'daterange',
    width: 280,
    startPlaceholder: '开始日期',
    endPlaceholder: '结束日期',
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
    key: 'testingPhase',
    label: '测试阶段',
    type: 'select',
    defaultStrategy: 'first-available',
    clearable: false,
    width: 280,
    options: filterOptions.value.testingPhases,
  },
  {
    key: 'moduleName',
    label: '模块关键词',
    type: 'select',
    width: 180,
    options: [{ label: '全部模块关键词', value: '' }, ...filterOptions.value.moduleNames],
  },
  {
    key: 'functionName',
    label: '功能名',
    type: 'select',
    width: 180,
    options: [{ label: '全部功能', value: '' }, ...filterOptions.value.functionNames],
  },
]);

const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  const values = filterValues.value;
  const tags: RecordTableActiveFilterTag[] = [];
  if (Array.isArray(values.updatedAtRange) && values.updatedAtRange.length === 2) {
    tags.push({
      key: 'updatedAtRange',
      label: '更新时间',
      value: `${values.updatedAtRange[0]} ~ ${values.updatedAtRange[1]}`,
    });
  }
  if (values.moduleName) tags.push({ key: 'moduleName', label: '模块关键词', value: String(values.moduleName) });
  if (values.functionName) tags.push({ key: 'functionName', label: '功能名', value: String(values.functionName) });
  if (values.issueIid) tags.push({ key: 'issueIid', label: '议题编号', value: String(values.issueIid) });
  if (values.title) tags.push({ key: 'title', label: '标题', value: String(values.title) });
  if (values.projectName) tags.push({ key: 'projectName', label: '项目名称', value: String(values.projectName) });
  if (Array.isArray(values.testingPhase) && values.testingPhase.length) {
    tags.push({ key: 'testingPhase', label: '测试阶段', value: values.testingPhase.join('、') });
  }
  if (values.authorName) tags.push({ key: 'authorName', label: '创建人', value: String(values.authorName) });
  if (values.assigneeName) tags.push({ key: 'assigneeName', label: '处理人', value: String(values.assigneeName) });
  if (values.issueState) tags.push({ key: 'issueState', label: '状态', value: String(values.issueState) });
  if (values.severityLevel) {
    tags.push({ key: 'severityLevel', label: '严重程度', value: displayIssueSeverity(String(values.severityLevel)) });
  }
  if (values.bugStatus) tags.push({ key: 'bugStatus', label: '缺陷状态', value: String(values.bugStatus) });
  if (values.category) tags.push({ key: 'category', label: '缺陷分类', value: String(values.category) });
  if (values.milestoneTitle) tags.push({ key: 'milestoneTitle', label: '里程碑', value: String(values.milestoneTitle) });
  if (Array.isArray(values.createdAtRange) && values.createdAtRange.length === 2) {
    tags.push({
      key: 'createdAtRange',
      label: '议题提交时间',
      value: `${values.createdAtRange[0]} ~ ${values.createdAtRange[1]}`,
    });
  }
  return [...conditionActiveFilterTags.value, ...tags];
});

const columns = computed<RecordTableColumn[]>(() => [
  { key: 'sourceInstance', label: '数据源', width: 100 },
  { key: 'projectId', label: '项目ID', sortable: true, width: 100 },
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'projectName', label: '项目名称', sortable: true, minWidth: 140 },
  { key: 'moduleNames', label: '模块', type: 'tags', minWidth: 180 },
  { key: 'functionName', label: '功能名', sortable: true, minWidth: 140 },
  { key: 'testingPhase', label: '测试阶段', sortable: true, minWidth: 180 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'bugStatus', label: '缺陷状态', sortable: true, minWidth: 140 },
  { key: 'issueState', label: '议题状态', sortable: true, width: 110 },
  { key: 'assigneeName', label: '处理人', sortable: true, minWidth: 120 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
]);

const tableRows = computed<Record<string, unknown>[]>(() =>
  rows.value.map((row) => ({
    __raw: row,
    identityKey: `${row.sourceInstance || 'default'}:${row.projectId}:${row.issueIid}`,
    issueId: row.issueId,
    issueIid: buildIssueIidCellValue(row.issueIid, row.issueLink),
    sourceInstance: row.sourceInstance || 'default',
    projectId: row.projectId,
    title: row.title || '-',
    projectName: row.projectName || '-',
    moduleNames: splitDisplayList(row.moduleNames).map((label) => ({ label, type: 'info' as const })),
    functionName: row.functionName || '-',
    testingPhase: row.testingPhase || '-',
    severityLevel: row.severityLevel ? [buildIssueSeverityTag(row.severityLevel)] : [],
    bugStatus: row.bugStatus || '-',
    issueState: row.issueState || '-',
    assigneeName: row.assigneeName || '-',
    updatedAt: formatDateTime(row.updatedAt),
  })),
);

bindLoader(async () => {
  try {
    await Promise.all([loadFilterOptions(), loadSyncStatus()]);
    if (!parseMultiQueryValue(route.query.testingPhase).length) {
      const fallback = filterOptions.value.testingPhases.find((option) => option.value)?.value ?? '';
      if (fallback) {
        await patchQuery({ page: 1, testingPhase: fallback });
        return;
      }
    }
    if (!testingPhaseDefaultReady.value) {
      return;
    }
    initializeFromQuery(route.query);
    await loadTableData();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '议题查询数据加载失败');
    rows.value = [];
    total.value = 0;
  }
});

async function loadFilterOptions() {
  filterOptions.value = await api.getSystemTestIssueSearchFilterOptions(
    route.query.projectId as string | undefined,
    String(route.query.sourceInstance ?? '') || undefined,
  );
}

async function handleRefreshLatestData() {
  realtimeRefreshLoading.value = true;
  try {
    let status = await api.refreshSystemTestIssueSearchRealtime();
    ElMessage.success(status.message || '已开始刷新最新数据');
    for (let attempt = 0; attempt < 8 && status.refreshing; attempt++) {
      await sleep(1000);
      status = (await loadSyncStatus()) ?? status;
    }
    await Promise.all([loadFilterOptions(), loadTableData()]);
    await loadSyncStatus();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新最新数据失败');
  } finally {
    realtimeRefreshLoading.value = false;
  }
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

async function loadTableData() {
  const response = await api.getSystemTestIssueSearchRecords(buildCurrentQueryParams(true));
  rows.value = response.records;
  total.value = response.total;
}

function buildCurrentQueryParams(includePagination: boolean) {
  return {
    projectId: route.query.projectId as string | undefined,
    sourceInstance: String(route.query.sourceInstance ?? ''),
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    functionName: String(route.query.functionName ?? ''),
    testingPhase: serializeMultiQueryValue(route.query.testingPhase),
    authorName: String(route.query.authorName ?? ''),
    assigneeName: String(route.query.assigneeName ?? ''),
    issueState: String(route.query.issueState ?? ''),
    severityLevel: String(route.query.severityLevel ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    createdAtStart: String(route.query.createdAtStart ?? ''),
    createdAtEnd: String(route.query.createdAtEnd ?? ''),
    updatedAtStart: String(route.query.updatedAtStart ?? ''),
    updatedAtEnd: String(route.query.updatedAtEnd ?? ''),
    filterGroup: buildFilterPayload(),
    ...(includePagination ? { page: page.value, size: pageSize.value } : {}),
    sortBy: sortBy.value || 'updatedAt',
    sortOrder: (sortOrder.value || 'desc') as 'asc' | 'desc',
  };
}

async function handleExport() {
  exportLoading.value = true;
  try {
    const csv = await api.exportSystemTestIssueSearchRecords(buildCurrentQueryParams(false));
    downloadCsv(csv, `系统测试问题记录_${formatExportFileDate(new Date())}.csv`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败');
  } finally {
    exportLoading.value = false;
  }
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function splitDisplayList(value: string) {
  return value
    .split(/[、&]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function parseMultiQueryValue(value: unknown) {
  const rawValues = Array.isArray(value) ? value : String(value ?? '').split(',');
  return rawValues.map((item) => String(item).trim()).filter(Boolean);
}

function serializeMultiQueryValue(value: unknown) {
  return parseMultiQueryValue(value).join(',');
}

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
  await patchQuery({
    page: 1,
    [payload.key]: Array.isArray(payload.value) ? serializeMultiQueryValue(payload.value) || null : payload.value,
  });
}

async function handleReset() {
  resetDraft();
  await patchQuery({
    page: 1,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
    ...buildResetQueryPatch(route.query),
    testingPhase: null,
    moduleName: null,
    functionName: null,
    updatedAtStart: null,
    updatedAtEnd: null,
    issueIid: null,
    title: null,
    projectName: null,
    authorName: null,
    assigneeName: null,
    issueState: null,
    severityLevel: null,
    bugStatus: null,
    category: null,
    milestoneTitle: null,
    createdAtStart: null,
    createdAtEnd: null,
  });
}

async function handleQuery() {
  await patchQuery({ page: 1, ...buildApplyQueryPatch(route.query) });
}

async function handleConditionFilterApply() {
  await patchQuery(buildConditionApplyQueryPatch(route.query));
}

async function handleConditionFilterReset() {
  await patchQuery(buildConditionResetQueryPatch(route.query));
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
    await patchQuery({ page: 1, ...buildResetQueryPatch(route.query) });
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
  await patchQuery({
    page: 1,
    [key]: null,
  });
}

async function handleRefresh() {
  try {
    await Promise.all([loadFilterOptions(), loadTableData()]);
    ElMessage.success('已刷新议题查询结果');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '议题查询刷新失败');
  }
}
</script>

<template>
  <section class="system-test-issue-search-page">
    <BaseRecordTable
      :columns="columns"
      :rows="tableRows"
      :loading="isTableLoading"
      :page="page"
      :page-size="pageSize"
      :total="total"
      row-key="identityKey"
      :primary-filters="primaryFilters"
      :filter-values="filterValues"
      :active-filter-tags="activeFilterTags"
      :show-search="false"
      empty-description="当前筛选条件下没有查到系统测试议题。"
      @filter-change="handleFilterChange"
      @reset="handleReset"
      @query="handleQuery"
      @clear-filter="handleClearFilter"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
      @sort-change="handleSortChange"
      @refresh="handleRefresh"
    >
      <template #filter-builder>
        <StatisticFilterBuilder
          :model-value="filterDraft"
          :fields="conditionFilterFields"
          add-button-text="添加条件"
          show-apply-actions
          @apply="handleConditionFilterApply"
          @reset="handleConditionFilterReset"
        />
      </template>

      <template #toolbar-actions>
        <SyncMetaBadge :value="lastSyncedText" />
        <el-button
          v-if="canRefreshLatestData"
          plain
          :icon="RefreshRight"
          :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
          @click="handleRefreshLatestData"
        >
          刷新最新数据
        </el-button>
        <el-button plain :icon="Download" :loading="exportLoading" @click="handleExport">
          导出
        </el-button>
        <PageSettingsButton :scope-key="PAGE_SCOPE_KEY" />
      </template>

      <template #expand="{ row }">
        <div class="issue-detail-panel">
          <el-descriptions :column="2" border size="small" class="issue-detail-descriptions">
            <el-descriptions-item label="议题编号">
              #{{ (row.__raw as SystemTestIssueSearchRowResponse).issueIid }}
            </el-descriptions-item>
            <el-descriptions-item label="项目">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).projectName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="议题状态">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).issueState || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="严重程度">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).severityLevel || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="测试阶段">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).testingPhase || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="测试状态">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).bugStatus || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="议题提交人">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).authorName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="议题处理人">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).assigneeName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="功能名">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).functionName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="缺陷分类">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).category || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="里程碑">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).milestoneTitle || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="议题提交时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).updatedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="关闭时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).closedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="模块名">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).moduleNames || '-' }}
            </el-descriptions-item>
          </el-descriptions>

          <section class="issue-detail-section">
            <div class="issue-detail-section-title">标题</div>
            <div class="issue-detail-content">{{ (row.__raw as SystemTestIssueSearchRowResponse).title || '-' }}</div>
          </section>

          <section class="issue-detail-section">
            <div class="issue-detail-section-title">标签</div>
            <div class="issue-detail-tags">
              <el-tag
                v-for="label in (row.__raw as SystemTestIssueSearchRowResponse).labels"
                :key="label"
                size="small"
                effect="plain"
              >
                {{ label }}
              </el-tag>
              <span
                v-if="!(row.__raw as SystemTestIssueSearchRowResponse).labels.length"
                class="issue-detail-empty"
              >
                -
              </span>
            </div>
          </section>
        </div>
      </template>
    </BaseRecordTable>
  </section>
</template>

<style scoped>
.system-test-issue-search-page {
  display: grid;
  gap: 10px;
}

.issue-detail-panel {
  display: grid;
  gap: 12px;
  padding: 12px 8px 4px 40px;
  background: rgba(248, 250, 252, 0.72);
}

.issue-detail-section {
  display: grid;
  gap: 8px;
}

.issue-detail-section-title {
  font-size: 12px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.issue-detail-content {
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.8);
  line-height: 1.6;
}

.issue-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.issue-detail-empty {
  color: rgba(15, 23, 42, 0.4);
}

:deep(.issue-detail-descriptions .el-descriptions__label) {
  width: 96px;
}
</style>
