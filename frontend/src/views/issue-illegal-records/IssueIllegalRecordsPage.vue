<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from '../../element-plus-services';
import { InfoFilled, Refresh } from '@element-plus/icons-vue';
import BaseRecordTable from '../../components/base/BaseRecordTable.vue';
import PageStateShell from '../../components/base/PageStateShell.vue';
import StatisticFilterBuilder from '../../components/StatisticFilterBuilder.vue';
import { useConditionFilterGroupState } from '../../composables/useConditionFilterGroupState';
import { useRecordPageController } from '../../composables/useRecordPageController';
import { useDataScope } from '../../composables/useDataScope';
import { useRouteTableState } from '../../composables/useRouteTableState';
import { useRuleExplanationPanel } from '../../composables/useRuleExplanationPanel';
import type { StatisticBoardRuleExplanationResponse, StatisticFilterField } from '../../types/api';
import type { IssueIllegalRecordRow, IssueIllegalRecordsPageConfig } from './issue-illegal-records-types';

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
const ruleFinalCount = computed(() => ruleSteps.value.at(-1)?.outputCount ?? 0);

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
  const response = await props.loadRecords({
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
    page: page.value,
    size: pageSize.value,
    sortBy: sortBy.value || props.defaultSortBy || 'updatedAt',
    sortOrder: (sortOrder.value || props.defaultSortOrder || 'desc') as 'asc' | 'desc',
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

        <template #toolbar-prefix>
          <div class="issue-illegal-toolbar-meta">
            <div class="issue-illegal-toolbar-title">{{ title }}</div>
            <div class="issue-illegal-toolbar-desc">{{ description }}</div>
          </div>
        </template>

        <template #primary-actions>
          <div class="issue-illegal-toolbar-actions customer-illegal-toolbar-actions">
            <el-tag effect="plain" type="warning">{{ totalTagText(total) }}</el-tag>
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

      <el-drawer v-model="ruleExplanationVisible" size="42%" destroy-on-close class="issue-illegal-drawer customer-illegal-drawer">
        <template #header>
          <div class="issue-illegal-detail-header customer-illegal-detail-header">
            <div>
              <div class="issue-illegal-detail-kicker customer-illegal-detail-kicker">规则说明</div>
              <div class="issue-illegal-detail-title customer-illegal-detail-title">
                {{ ruleExplanation?.title || ruleTitle }}
              </div>
            </div>
          </div>
        </template>

        <div v-loading="ruleExplanationLoading" class="issue-illegal-rule-panel customer-illegal-rule-panel">
          <el-empty v-if="!ruleExplanation?.supported" :description="ruleExplanation?.unsupportedReason || '当前暂不支持规则说明。'" />
          <template v-else>
            <section class="issue-illegal-rule-summary customer-illegal-rule-summary">
              <strong>{{ totalTagText(ruleFinalCount) }}</strong>
              <span>{{ ruleExplanation.summary }}</span>
            </section>
            <section class="issue-illegal-detail-section customer-illegal-detail-section">
              <div class="issue-illegal-detail-section-title customer-illegal-detail-section-title">统计范围</div>
              <div class="issue-illegal-reason-card customer-illegal-reason-card">{{ ruleExplanation.scopeDescription }}</div>
            </section>
            <section class="issue-illegal-detail-section customer-illegal-detail-section">
              <div class="issue-illegal-detail-section-title customer-illegal-detail-section-title">处理步骤</div>
              <article v-for="step in ruleSteps" :key="step.key" class="issue-illegal-step-card customer-illegal-step-card">
                <div class="issue-illegal-step-title customer-illegal-step-title">{{ step.title }}</div>
                <div class="issue-illegal-step-desc customer-illegal-step-desc">{{ step.description }}</div>
                <div class="issue-illegal-step-count customer-illegal-step-count">
                  输入 {{ step.inputCount }} 条，输出 {{ step.outputCount }} 条
                </div>
              </article>
            </section>
          </template>
        </div>
      </el-drawer>
    </section>
  </PageStateShell>
</template>

<style scoped>
.issue-illegal-page {
  display: grid;
  gap: 12px;
}

.issue-illegal-toolbar-meta {
  display: grid;
  gap: 2px;
}

.issue-illegal-toolbar-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.92);
}

.issue-illegal-toolbar-desc {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.45);
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

.issue-illegal-detail-header,
.issue-illegal-rule-panel {
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

.issue-illegal-reason-card,
.issue-illegal-step-card,
.issue-illegal-rule-summary {
  padding: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.issue-illegal-rule-summary {
  display: grid;
  gap: 6px;
  color: rgba(15, 23, 42, 0.72);
}

.issue-illegal-step-title {
  font-weight: 700;
  color: rgba(15, 23, 42, 0.86);
}

.issue-illegal-step-desc,
.issue-illegal-step-count {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.58);
}
</style>
