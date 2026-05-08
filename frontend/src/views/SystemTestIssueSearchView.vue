<script setup lang="ts">
import { computed, ref } from 'vue';
// 系统测试议题查询页承担 issue_fact 的明细检索入口，路由参数就是可分享的查询状态。
// 组件内部只处理页面交互，阶段、模块和非法规则的口径由共享条件字段提供。
import { ElMessage } from '../element-plus-services';
import { Download } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import { api } from '../api';
import { buildIssueIidCellValue } from '../utils/issue-record-links';
import { downloadCsv, formatExportFileDate } from '../utils/csv-download';
import type {
  SystemTestIssueSearchFilterOptionsResponse,
  SystemTestIssueSearchRowResponse,
} from '../types/api';
import { useRouteTableState } from '../composables/useRouteTableState';
import type {
  RecordTableActiveFilterTag,
  RecordTableColumn,
  RecordTableFilterField,
  RecordTableTagValue,
} from '../types/record-table';
import { SYSTEM_TEST_PHASE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';
import { useDataScope } from '../composables/useDataScope';

const { route, page, pageSize, sortBy, sortOrder, patchQuery, bindLoader, isTableLoading } =
  useRouteTableState({
    defaults: {
      page: 1,
      pageSize: 20,
      sortBy: 'updatedAt',
      sortOrder: 'desc',
    },
  });

const advancedVisible = ref(false);
const rows = ref<SystemTestIssueSearchRowResponse[]>([]);
const total = ref(0);
const exportLoading = ref(false);
const filterOptions = ref<SystemTestIssueSearchFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  testingPhases: [],
  authorNames: [],
  assigneeNames: [],
  issueStates: [],
  severityLevels: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
});

const filterValues = computed<Record<string, unknown>>(() => {
  const createdAtStart = String(route.query.createdAtStart ?? '');
  const createdAtEnd = String(route.query.createdAtEnd ?? '');
  const updatedAtStart = String(route.query.updatedAtStart ?? '');
  const updatedAtEnd = String(route.query.updatedAtEnd ?? '');
  return {
    keyword: String(route.query.keyword ?? ''),
    testingPhase: String(route.query.testingPhase ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
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
  {
    key: 'updatedAtRange',
    label: '更新时间',
    type: 'daterange',
    width: 280,
    startPlaceholder: '开始日期',
    endPlaceholder: '结束日期',
  },
  {
    key: 'moduleName',
    label: '模块名称',
    type: 'select',
    width: 180,
    options: [{ label: '全部模块', value: '' }, ...filterOptions.value.moduleNames],
  },
  {
    key: 'keyword',
    label: '关键字',
    type: 'input',
    width: 260,
    placeholder: '搜索议题编号、标题、模块、作者',
  },
]);

const advancedFilters = computed<RecordTableFilterField[]>(() => [
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
  if (values.moduleName) tags.push({ key: 'moduleName', label: '模块名称', value: String(values.moduleName) });
  if (values.keyword) tags.push({ key: 'keyword', label: '关键字', value: String(values.keyword) });
  if (values.issueIid) tags.push({ key: 'issueIid', label: '议题编号', value: String(values.issueIid) });
  if (values.title) tags.push({ key: 'title', label: '标题', value: String(values.title) });
  if (values.projectName) tags.push({ key: 'projectName', label: '项目名称', value: String(values.projectName) });
  if (values.authorName) tags.push({ key: 'authorName', label: '创建人', value: String(values.authorName) });
  if (values.assigneeName) tags.push({ key: 'assigneeName', label: '处理人', value: String(values.assigneeName) });
  if (values.issueState) tags.push({ key: 'issueState', label: '状态', value: String(values.issueState) });
  if (values.severityLevel) tags.push({ key: 'severityLevel', label: '严重程度', value: String(values.severityLevel) });
  if (values.bugStatus) tags.push({ key: 'bugStatus', label: '缺陷状态', value: String(values.bugStatus) });
  if (values.category) tags.push({ key: 'category', label: '缺陷分类', value: String(values.category) });
  if (values.milestoneTitle) tags.push({ key: 'milestoneTitle', label: '里程碑', value: String(values.milestoneTitle) });
  if (Array.isArray(values.createdAtRange) && values.createdAtRange.length === 2) {
    tags.push({
      key: 'createdAtRange',
      label: '创建时间',
      value: `${values.createdAtRange[0]} ~ ${values.createdAtRange[1]}`,
    });
  }
  return tags;
});

useDataScope({
  provider: SYSTEM_TEST_PHASE_SCOPE_PROVIDER,
  options: computed(() => buildScopeOptions(filterOptions.value.testingPhases, '全部测试阶段')),
  mountToShell: true,
});

const columns = computed<RecordTableColumn[]>(() => [
  { key: 'issueIid', label: '议题编号', type: 'link', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'projectName', label: '项目名称', sortable: true, minWidth: 140 },
  { key: 'moduleNames', label: '模块', type: 'tags', minWidth: 180 },
  { key: 'testingPhase', label: '测试阶段', sortable: true, minWidth: 180 },
  { key: 'severityLevel', label: '严重程度', type: 'tag', sortable: true, width: 120 },
  { key: 'bugStatus', label: '缺陷状态', sortable: true, minWidth: 140 },
  { key: 'issueState', label: '状态', type: 'tag', sortable: true, width: 110 },
  { key: 'assigneeName', label: '处理人', sortable: true, minWidth: 120 },
  { key: 'updatedAt', label: '更新时间', sortable: true, minWidth: 170 },
]);

const tableRows = computed<Record<string, unknown>[]>(() =>
  rows.value.map((row) => ({
    __raw: row,
    issueId: row.issueId,
    issueIid: buildIssueIidCellValue(row.issueIid, row.issueLink),
    title: row.title || '-',
    projectName: row.projectName || '-',
    moduleNames: splitDisplayList(row.moduleNames).map((label) => ({ label, type: 'info' as const })),
    testingPhase: row.testingPhase || '-',
    severityLevel: row.severityLevel ? [buildSeverityTag(row.severityLevel)] : [],
    bugStatus: row.bugStatus || '-',
    issueState: row.issueState ? [buildStateTag(row.issueState)] : [],
    assigneeName: row.assigneeName || '-',
    updatedAt: formatDateTime(row.updatedAt),
  })),
);

bindLoader(async () => {
  try {
    await Promise.all([loadFilterOptions(), loadTableData()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '议题查询数据加载失败');
    rows.value = [];
    total.value = 0;
  }
});

async function loadFilterOptions() {
  filterOptions.value = await api.getSystemTestIssueSearchFilterOptions(
    route.query.projectId as string | undefined,
  );
}

async function loadTableData() {
  const response = await api.getSystemTestIssueSearchRecords(buildCurrentQueryParams(true));
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
    .split('、')
    .map((item) => item.trim())
    .filter(Boolean);
}

function buildSeverityTag(value: string): RecordTableTagValue {
  const normalized = value.toUpperCase();
  if (normalized === 'LEVEL1') {
    return { label: value, type: 'danger' };
  }
  if (normalized === 'LEVEL2') {
    return { label: value, type: 'warning' };
  }
  if (normalized === 'LEVEL3') {
    return { label: value, type: 'primary' };
  }
  return { label: value, type: 'info' };
}

function buildStateTag(value: string): RecordTableTagValue {
  return value.toLowerCase() === 'closed'
    ? { label: '已关闭', type: 'success' }
    : { label: '未关闭', type: 'warning' };
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
    [payload.key]: Array.isArray(payload.value) ? payload.value[0] ?? null : payload.value,
  });
}

async function handleReset() {
  await patchQuery({
    page: 1,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
    keyword: null,
    testingPhase: null,
    moduleName: null,
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
      row-key="issueId"
      :keyword-auto-search="true"
      :primary-filters="primaryFilters"
      :advanced-filters="advancedFilters"
      :filter-values="filterValues"
      :active-filter-tags="activeFilterTags"
      :advanced-visible="advancedVisible"
      :show-search="false"
      empty-description="当前筛选条件下没有查到系统测试议题。"
      @filter-change="handleFilterChange"
      @reset="handleReset"
      @query="handleQuery"
      @clear-filter="handleClearFilter"
      @update:advanced-visible="advancedVisible = $event"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
      @sort-change="handleSortChange"
      @refresh="handleRefresh"
    >
      <template #toolbar-prefix>
        <div class="issue-search-toolbar-meta">
          <div class="issue-search-toolbar-title">议题查询</div>
          <div class="issue-search-toolbar-desc">系统测试范围内的议题筛选、排序与详情展开</div>
        </div>
      </template>

      <template #toolbar-actions>
        <el-button plain :icon="Download" :loading="exportLoading" @click="handleExport">
          导出
        </el-button>
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
            <el-descriptions-item label="状态">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).issueState || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="严重程度">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).severityLevel || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="测试阶段">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).testingPhase || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="缺陷状态">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).bugStatus || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建人">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).authorName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="处理人">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).assigneeName || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="缺陷分类">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).category || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="里程碑">
              {{ (row.__raw as SystemTestIssueSearchRowResponse).milestoneTitle || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).createdAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).updatedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="关闭时间">
              {{ formatDateTime((row.__raw as SystemTestIssueSearchRowResponse).closedAt) }}
            </el-descriptions-item>
            <el-descriptions-item label="模块">
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

.issue-search-toolbar-meta {
  display: grid;
  gap: 2px;
}

.issue-search-toolbar-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.92);
}

.issue-search-toolbar-desc {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.45);
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
