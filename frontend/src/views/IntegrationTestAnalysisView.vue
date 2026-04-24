<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import { api } from '../api';
import type {
  IntegrationTestDetailResponse,
  IntegrationTestPhaseOptionResponse,
  IntegrationTestProjectOptionResponse,
  IntegrationTestSummaryResponse,
} from '../types/api';
import type { RecordTableColumn } from '../types/record-table';

const route = useRoute();
const router = useRouter();

const initialized = ref(false);
const toolbarLoading = ref(false);
const summaryLoading = ref(false);
const rebuildLoading = ref(false);
const detailLoading = ref(false);

const projectOptions = ref<IntegrationTestProjectOptionResponse[]>([]);
const phaseOptions = ref<IntegrationTestPhaseOptionResponse[]>([]);
const summary = ref<IntegrationTestSummaryResponse>({
  projectId: null,
  testingPhase: null,
  moduleCount: 0,
  totalIssueCount: 0,
  factRefreshedAt: null,
  rows: [],
});
const detail = ref<IntegrationTestDetailResponse>({
  records: [],
  total: 0,
  page: 1,
  size: 20,
  sortField: 'noteUpdatedAt',
  sortOrder: 'desc',
});

const projectId = computed<number | null>(() => {
  const raw = route.query.projectId;
  if (raw == null || raw === '') {
    return null;
  }
  const value = Number(raw);
  return Number.isFinite(value) ? value : null;
});
const testingPhase = computed(() => String(route.query.testingPhase ?? ''));
const detailVisible = computed(() => String(route.query.detailVisible ?? '') === 'true');
const detailModule = computed(() => String(route.query.detailModule ?? ''));
const detailPage = computed(() => normalizePositiveNumber(route.query.detailPage, 1));
const detailPageSize = computed(() => normalizePositiveNumber(route.query.detailPageSize, 20));
const detailSortBy = computed(() => String(route.query.detailSortBy ?? 'noteUpdatedAt'));
const detailSortOrder = computed<'asc' | 'desc'>(() =>
  String(route.query.detailSortOrder ?? 'desc') === 'asc' ? 'asc' : 'desc',
);

const pageReady = computed(() => initialized.value);
const summaryRows = computed(() => summary.value.rows ?? []);
const selectedProjectLabel = computed(() => {
  if (projectId.value == null) {
    return '全部项目';
  }
  const match = projectOptions.value.find((item) => item.projectId === projectId.value);
  return match?.projectName ? `${match.projectName} (${match.projectId})` : String(projectId.value);
});
const selectedPhaseLabel = computed(() => testingPhase.value || '未选择测试阶段');
const detailTitle = computed(() => (detailModule.value ? `${detailModule.value} 明细` : '模块明细'));
const phaseSelectOptions = computed(() =>
  phaseOptions.value.map((item) => ({
    label:
      projectId.value == null && item.projectName
        ? `${item.projectName} / ${item.testingPhase}`
        : item.testingPhase,
    value: item.testingPhase,
  })),
);

const detailColumns = computed<RecordTableColumn[]>(() => [
  { key: 'issuableReference', label: '议题编号', sortable: true, width: 110, fixed: 'left' },
  { key: 'title', label: '标题', sortable: true, minWidth: 260 },
  { key: 'functionName', label: '功能', sortable: true, minWidth: 160 },
  { key: 'functionLabels', label: '功能标签', type: 'tags', minWidth: 160 },
  { key: 'executor', label: '执行人', sortable: true, width: 120 },
  { key: 'executeCase', label: '执行用例总数', sortable: true, width: 120 },
  { key: 'passCase', label: '通过用例数', sortable: true, width: 120 },
  { key: 'notPassCase', label: '初始未通过', sortable: true, width: 120 },
  { key: 'notPassCaseNow', label: '本次未通过', sortable: true, width: 120 },
  { key: 'problemCase', label: '问题用例数', sortable: true, width: 120 },
  { key: 'exceptionCount', label: '例外问题数', sortable: true, width: 120 },
  { key: 'passRate', label: '通过率', sortable: true, width: 110 },
  { key: 'legal', label: '合法性', type: 'tags', sortable: true, width: 120 },
  { key: 'validationReason', label: '校验说明', minWidth: 220 },
  { key: 'noteUpdatedAt', label: '备注更新时间', sortable: true, minWidth: 170 },
]);

const detailRows = computed<Record<string, unknown>[]>(() =>
  detail.value.records.map((row) => ({
    issuableReference: row.issuableReference || `#${row.issueIid}`,
    title: row.title || '-',
    functionName: row.functionName || '-',
    functionLabels: buildFunctionLabelTags(row.functionLabels),
    executor: row.executor || '-',
    executeCase: row.executeCase ?? 0,
    passCase: row.passCase ?? 0,
    notPassCase: row.notPassCase ?? 0,
    notPassCaseNow: row.notPassCaseNow ?? 0,
    problemCase: row.problemCase ?? 0,
    exceptionCount: row.exceptionCount ?? 0,
    passRate: formatPercent(row.passRate),
    legal: buildValidationTags(row),
    validationReason: formatValidationReason(row),
    noteUpdatedAt: formatDateTime(row.noteUpdatedAt ?? row.updatedAtSource),
  })),
);

let syncing = false;

watch(
  () => [projectId.value, testingPhase.value],
  async () => {
    await syncPageData();
  },
  { immediate: true },
);

watch(
  () => [
    detailVisible.value,
    detailModule.value,
    detailPage.value,
    detailPageSize.value,
    detailSortBy.value,
    detailSortOrder.value,
    projectId.value,
    testingPhase.value,
  ],
  async () => {
    if (!detailVisible.value || !detailModule.value || !testingPhase.value) {
      return;
    }
    await loadDetail();
  },
);

async function syncPageData() {
  if (syncing) {
    return;
  }
  syncing = true;
  toolbarLoading.value = true;
  try {
    projectOptions.value = await api.getIntegrationTestProjectOptions();
    phaseOptions.value = await api.getIntegrationTestPhaseOptions(projectId.value);
    const expectedPhase = resolveExpectedPhase();
    if (expectedPhase !== testingPhase.value) {
      await replaceQuery({
        testingPhase: expectedPhase || undefined,
        detailVisible: undefined,
        detailModule: undefined,
        detailPage: undefined,
        detailPageSize: undefined,
        detailSortBy: undefined,
        detailSortOrder: undefined,
      });
      return;
    }
    await loadSummary();
    initialized.value = true;
  } catch (error) {
    initialized.value = true;
    ElMessage.error((error as Error).message);
  } finally {
    toolbarLoading.value = false;
    syncing = false;
  }
}

async function loadSummary() {
  summaryLoading.value = true;
  try {
    summary.value = await api.getIntegrationTestSummary({
      projectId: projectId.value,
      testingPhase: testingPhase.value || undefined,
    });
  } finally {
    summaryLoading.value = false;
  }
}

async function loadDetail() {
  detailLoading.value = true;
  try {
    detail.value = await api.getIntegrationTestDetails({
      projectId: projectId.value,
      testingPhase: testingPhase.value,
      moduleName: detailModule.value,
      page: detailPage.value,
      size: detailPageSize.value,
      sortBy: detailSortBy.value,
      sortOrder: detailSortOrder.value,
    });
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    detailLoading.value = false;
  }
}

function resolveExpectedPhase() {
  if (!phaseOptions.value.length) {
    return '';
  }
  return phaseOptions.value.some((item) => item.testingPhase === testingPhase.value)
    ? testingPhase.value
    : phaseOptions.value[0]?.testingPhase ?? '';
}

async function replaceQuery(patch: Record<string, string | number | undefined>) {
  const nextQuery: Record<string, string> = {};
  for (const [key, value] of Object.entries({ ...route.query, ...patch })) {
    if (value == null || value === '') {
      continue;
    }
    nextQuery[key] = String(value);
  }
  await router.replace({ query: nextQuery });
}

async function handleProjectChange(value: string | number) {
  await replaceQuery({
    projectId: value ? String(value) : undefined,
    testingPhase: undefined,
    detailVisible: undefined,
    detailModule: undefined,
    detailPage: undefined,
    detailPageSize: undefined,
    detailSortBy: undefined,
    detailSortOrder: undefined,
  });
}

async function handlePhaseChange(value: string | number) {
  await replaceQuery({
    testingPhase: value ? String(value) : undefined,
    detailVisible: undefined,
    detailModule: undefined,
    detailPage: undefined,
    detailPageSize: undefined,
    detailSortBy: undefined,
    detailSortOrder: undefined,
  });
}

async function handleRefresh() {
  try {
    await loadSummary();
    if (detailVisible.value && detailModule.value) {
      await loadDetail();
    }
    ElMessage.success('集成测试数据已刷新');
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
}

async function handleRebuild() {
  rebuildLoading.value = true;
  try {
    const response = await api.rebuildIntegrationTestFacts(false);
    ElMessage.success(response.message || '集成测试事实已重建');
    await syncPageData();
    if (detailVisible.value && detailModule.value) {
      await loadDetail();
    }
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    rebuildLoading.value = false;
  }
}

async function openDetail(moduleName: string) {
  await replaceQuery({
    detailVisible: 'true',
    detailModule: moduleName,
    detailPage: 1,
    detailPageSize: detailPageSize.value,
    detailSortBy: detailSortBy.value,
    detailSortOrder: detailSortOrder.value,
  });
}

async function closeDetail() {
  await replaceQuery({
    detailVisible: undefined,
    detailModule: undefined,
    detailPage: undefined,
    detailPageSize: undefined,
    detailSortBy: undefined,
    detailSortOrder: undefined,
  });
}

async function handleDetailCurrentChange(page: number) {
  await replaceQuery({ detailPage: page });
}

async function handleDetailSizeChange(size: number) {
  await replaceQuery({ detailPage: 1, detailPageSize: size });
}

async function handleDetailSortChange(payload: {
  prop: string;
  order: 'ascending' | 'descending' | null;
}) {
  await replaceQuery({
    detailPage: 1,
    detailSortBy: payload.prop || 'noteUpdatedAt',
    detailSortOrder: payload.order === 'ascending' ? 'asc' : 'desc',
  });
}

function normalizePositiveNumber(raw: unknown, fallback: number) {
  const value = Number(raw);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function formatPercent(value?: string | number | null) {
  if (value == null || value === '') {
    return '0.00%';
  }
  return `${Number(value).toFixed(2)}%`;
}

function buildValidationTags(row: IntegrationTestDetailResponse['records'][number]) {
  if (row.legal) {
    return [{ label: '合法', type: 'success' as const }];
  }
  if (row.parseStatus === 'PARTIAL') {
    return [{ label: '待补充', type: 'warning' as const }];
  }
  return [{ label: '待确认', type: 'danger' as const }];
}

function formatValidationReason(row: IntegrationTestDetailResponse['records'][number]) {
  if (row.validationReason) {
    return row.validationReason;
  }
  return row.legal ? '校验通过' : '-';
}

function buildFunctionLabelTags(value?: string | null) {
  const labels = String(value ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  if (!labels.length) {
    return [{ label: '-', type: 'info' as const }];
  }
  return labels.map((label) => ({
    label,
    type: 'primary' as const,
  }));
}
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="integration-test-page">
      <el-card shadow="never" class="integration-toolbar-card">
        <div class="integration-toolbar">
          <div class="integration-toolbar__filters">
            <el-select
              :model-value="projectId == null ? '' : String(projectId)"
              placeholder="全部项目"
              clearable
              filterable
              class="integration-select"
              :loading="toolbarLoading"
              @change="handleProjectChange"
            >
              <el-option label="全部项目" value="" />
              <el-option
                v-for="item in projectOptions"
                :key="item.projectId"
                :label="item.projectName ? `${item.projectName} (${item.projectId})` : String(item.projectId)"
                :value="String(item.projectId)"
              />
            </el-select>
            <el-select
              :model-value="testingPhase"
              placeholder="选择测试阶段"
              filterable
              class="integration-select integration-select--phase"
              :loading="toolbarLoading"
              @change="handlePhaseChange"
            >
              <el-option
                v-for="item in phaseSelectOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </div>
          <div class="integration-toolbar__actions">
            <el-button :icon="Refresh" :loading="summaryLoading" @click="handleRefresh">刷新</el-button>
            <el-button :icon="RefreshRight" :loading="rebuildLoading" type="primary" @click="handleRebuild">
              重建事实
            </el-button>
          </div>
        </div>
      </el-card>

      <div class="integration-stats">
        <el-card shadow="never" class="integration-stat-card">
          <span>当前项目</span>
          <strong>{{ selectedProjectLabel }}</strong>
        </el-card>
        <el-card shadow="never" class="integration-stat-card">
          <span>测试阶段</span>
          <strong>{{ selectedPhaseLabel }}</strong>
        </el-card>
        <el-card shadow="never" class="integration-stat-card">
          <span>模块数</span>
          <strong>{{ summary.moduleCount }}</strong>
        </el-card>
        <el-card shadow="never" class="integration-stat-card">
          <span>记录数</span>
          <strong>{{ summary.totalIssueCount }}</strong>
        </el-card>
        <el-card shadow="never" class="integration-stat-card">
          <span>最近重建</span>
          <strong>{{ formatDateTime(summary.factRefreshedAt) }}</strong>
        </el-card>
      </div>

      <el-card shadow="never" class="integration-summary-card">
        <template #header>
          <div class="integration-summary-card__header">
            <div>
              <h3>模块汇总</h3>
              <p>按模块聚合集成测试执行结果，点击模块可查看明细。</p>
            </div>
          </div>
        </template>

        <el-table
          :data="summaryRows"
          stripe
          border
          v-loading="summaryLoading"
          class="integration-summary-table"
          empty-text="当前阶段暂无可展示的集成测试数据"
        >
          <el-table-column prop="moduleName" label="模块" min-width="160" fixed="left" />
          <el-table-column prop="issueCount" label="记录数" width="100" align="center" />
          <el-table-column prop="executeCase" label="执行用例总数" width="130" align="center" />
          <el-table-column prop="passCase" label="通过用例数" width="120" align="center" />
          <el-table-column prop="notPassCase" label="初始未通过" width="120" align="center" />
          <el-table-column prop="notPassCaseNow" label="本次未通过" width="120" align="center" />
          <el-table-column prop="problemCase" label="问题用例数" width="120" align="center" />
          <el-table-column prop="exceptionCount" label="例外问题数" width="120" align="center" />
          <el-table-column label="通过率" width="110" align="center">
            <template #default="{ row }">{{ formatPercent(row.passRate) }}</template>
          </el-table-column>
          <el-table-column prop="illegalCount" label="待确认记录" width="120" align="center" />
          <el-table-column label="操作" width="120" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row.moduleName)">查看明细</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-drawer :model-value="detailVisible" :title="detailTitle" size="72%" @close="closeDetail">
        <BaseRecordTable
          :columns="detailColumns"
          :rows="detailRows"
          :loading="detailLoading"
          :page="detail.page"
          :page-size="detail.size"
          :total="detail.total"
          row-key="issuableReference"
          :show-search="false"
          :show-refresh="false"
          empty-description="当前模块暂无明细记录"
          @current-change="handleDetailCurrentChange"
          @size-change="handleDetailSizeChange"
          @sort-change="handleDetailSortChange"
        />
      </el-drawer>
    </section>
  </PageStateShell>
</template>

<style scoped>
.integration-test-page {
  display: grid;
  gap: 16px;
}

.integration-toolbar-card,
.integration-summary-card,
.integration-stat-card {
  border: 1px solid #e5e7eb;
}

.integration-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.integration-toolbar__filters,
.integration-toolbar__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.integration-select {
  width: 220px;
}

.integration-select--phase {
  width: 280px;
}

.integration-stats {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.integration-stat-card :deep(.el-card__body) {
  display: grid;
  gap: 8px;
}

.integration-stat-card span {
  font-size: 13px;
  color: #6b7280;
}

.integration-stat-card strong {
  color: #111827;
  font-size: 18px;
  line-height: 1.3;
}

.integration-summary-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.integration-summary-card__header h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.integration-summary-card__header p {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 13px;
}

.integration-summary-table {
  width: 100%;
}

@media (max-width: 768px) {
  .integration-select,
  .integration-select--phase {
    width: 100%;
  }
}
</style>
