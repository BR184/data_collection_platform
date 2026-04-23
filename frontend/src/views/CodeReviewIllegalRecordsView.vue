<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { InfoFilled, Setting } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { api } from '../api';
import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordRowResponse,
  RealtimeWorkspaceStatusResponse,
  StatisticFilterField,
  StatisticBoardRuleExplanationResponse,
} from '../types/api';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { useRouteTableState } from '../composables/useRouteTableState';
import type { RecordTableActiveFilterTag, RecordTableColumn } from '../types/record-table';
import type { CodeReviewRuleConfig } from '../types/code-review-rule-config';
import { buildCodeReviewRuleFields } from './code-review-rule-config-schema';
import {
  loadStoredCodeReviewRuleConfig,
  saveStoredCodeReviewRuleConfig,
} from './code-review-rule-config-storage';
import {
  createDefaultCodeReviewRuleConfig,
  hasReadyCodeReviewRuleConfig,
  normalizeCodeReviewRuleConfig,
} from './code-review-rule-config-utils';

const router = useRouter();
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
    sortBy: 'mergedAt',
    sortOrder: 'desc',
  },
});

const rows = ref<CodeReviewIllegalRecordRowResponse[]>([]);
const total = ref(0);
const detailVisible = ref(false);
const ruleExplanationVisible = ref(false);
const ruleExplanationLoading = ref(false);
const selectedRow = ref<CodeReviewIllegalRecordRowResponse | null>(null);
const syncStatus = ref<RealtimeWorkspaceStatusResponse | null>(null);
const ruleExplanation = ref<StatisticBoardRuleExplanationResponse | null>(null);
const appliedRuleConfig = ref<CodeReviewRuleConfig | null>(null);

const filterOptions = ref<CodeReviewIllegalRecordFilterOptionsResponse>({
  requestTypes: [{ label: '合并请求', value: 'merge_request' }],
  repositoryNames: [],
  illegalTypes: [],
  targetBranches: [],
  mergedBys: [],
  moduleNames: [],
  projectNames: [],
});

function selectField(key: string, label: string, options: { label: string; value: string }[], width = 180): StatisticFilterField {
  return {
    key,
    label,
    type: 'select',
    width,
    operators: ['eq', 'ne'],
    options,
  };
}

function textField(key: string, label: string, width = 180): StatisticFilterField {
  return {
    key,
    label,
    type: 'text',
    width,
    operators: ['contains', 'eq', 'ne', 'isEmpty', 'isNotEmpty'],
    options: [],
  };
}

function numberField(key: string, label: string, width = 160): StatisticFilterField {
  return {
    key,
    label,
    type: 'number',
    width,
    operators: ['eq', 'gt', 'gte', 'lt', 'lte', 'between'],
    options: [],
  };
}

function datetimeField(key: string, label: string, width = 220): StatisticFilterField {
  return {
    key,
    label,
    type: 'datetime',
    width,
    operators: ['year', 'month', 'day', 'at', 'before', 'after', 'between'],
    options: [],
  };
}

const conditionFilterFields = computed<StatisticFilterField[]>(() => [
  selectField('repositoryName', '代码库', filterOptions.value.repositoryNames),
  datetimeField('mergedAt', '合并时间'),
  selectField('illegalType', '非法类型', filterOptions.value.illegalTypes),
  textField('keyword', '关键字', 240),
  selectField('requestType', '请求类型', filterOptions.value.requestTypes),
  numberField('mergeRequestIid', '合并请求编号'),
  textField('owner', '标注责任人'),
  selectField('targetBranch', '目标分支', filterOptions.value.targetBranches),
  selectField('mergedBy', '合并人', filterOptions.value.mergedBys),
  selectField('moduleName', '模块名称', filterOptions.value.moduleNames),
  selectField('projectName', '项目名称', filterOptions.value.projectNames),
  numberField('commentRate', '代码注释比例'),
  numberField('defectCount', '缺陷数量'),
  numberField('addedLines', '新增代码行数'),
]);

const {
  filterDraft,
  activeFilterTags: conditionFilterGroupTags,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);

const conditionActiveFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  const tags = [...conditionFilterGroupTags.value];
  if (appliedRuleConfig.value?.enabled) {
    tags.push({ key: 'personalRuleConfig', label: '判定规则', value: '我的规则' });
  }
  return tags;
});

const columns = computed<RecordTableColumn[]>(() => [
  { key: 'mergeRequestIid', label: '合并请求编号', type: 'link', sortable: true, width: 128, fixed: 'left' },
  { key: 'mergeRequestContent', label: '合并请求内容', sortable: true, minWidth: 260 },
  { key: 'owner', label: '标注责任人', sortable: true, minWidth: 140 },
  { key: 'projectName', label: '所属项目', sortable: true, minWidth: 160 },
  { key: 'mergedAt', label: '合并时间', type: 'datetime', sortable: true, minWidth: 180 },
  { key: 'mergedBy', label: '合并人', sortable: true, minWidth: 140 },
  { key: 'moduleName', label: '模块名', sortable: true, minWidth: 140 },
  { key: 'targetBranch', label: '合并目标分支', sortable: true, minWidth: 180 },
  { key: 'illegalTypes', label: '非法类型', type: 'tags', minWidth: 220 },
  { key: 'commentRate', label: '代码注释比例(%)', sortable: true, width: 160, align: 'right' },
  { key: 'defectCount', label: '缺陷数量', type: 'number', sortable: true, width: 120, align: 'right' },
  { key: 'addedLines', label: '新增代码行数(行)', type: 'number', sortable: true, width: 150, align: 'right' },
]);

const tableEmptyDescription = computed(() =>
  appliedRuleConfig.value?.enabled
    ? '当前数据范围和我的规则下没有判定出非法记录。'
    : '当前筛选条件下没有查询到非法记录。',
);

const tableRows = computed<Record<string, unknown>[]>(() =>
  rows.value.map((row) => ({
    __raw: row,
    mergeRequestIid: row.mergeRequestLink
      ? { label: String(row.mergeRequestIid), href: row.mergeRequestLink }
      : String(row.mergeRequestIid),
    mergeRequestContent: row.mergeRequestContent,
    owner: row.owner || '-',
    projectName: row.projectName || '-',
    mergedAt: row.mergedAt ? row.mergedAt.replace('T', ' ').slice(0, 19) : '-',
    mergedBy: row.mergedBy || '-',
    moduleName: row.moduleName || '-',
    targetBranch: row.targetBranch || '-',
    illegalTypes: row.illegalTypes.map((label) => ({ label, type: 'warning' as const })),
    commentRate: formatPercent(row.commentRate),
    defectCount: row.defectCount,
    addedLines: row.addedLines,
  })),
);

function formatDateTime(value?: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-';
}

function formatMetric(value?: number | null, suffix = '') {
  if (value == null) {
    return '-';
  }
  return `${value}${suffix}`;
}

function formatPercent(value?: number | null) {
  if (value == null) {
    return '-';
  }
  return `${value.toFixed(2)}%`;
}

const lastSyncedText = computed(() => formatDateTime(syncStatus.value?.lastSyncedAt));
const ruleExplanationSteps = computed(() => ruleExplanation.value?.flowSteps || []);
const ruleExplanationMetrics = computed(() => ruleExplanation.value?.metricDefinitions || []);
const ruleFirstInputCount = computed(() => ruleExplanationSteps.value[0]?.inputCount || 0);
const illegalTotalStep = computed(() => ruleExplanationSteps.value.find((step) => step.key === 'illegal-total') || null);
const ruleFinalOutputCount = computed(() => {
  if (illegalTotalStep.value) {
    return illegalTotalStep.value.outputCount;
  }
  const steps = ruleExplanationSteps.value;
  return steps.length ? steps[steps.length - 1].outputCount : 0;
});
const ruleFinalRetainedRate = computed(() => {
  if (!ruleFirstInputCount.value) {
    return '0%';
  }
  return `${((ruleFinalOutputCount.value / ruleFirstInputCount.value) * 100).toFixed(1)}%`;
});
const qaFriendlyRuleSummary = computed(() => {
  if (!ruleExplanation.value?.supported) {
    return '';
  }
  if (!ruleExplanationSteps.value.length) {
    return ruleExplanation.value?.summary || '当前页面已经启用规则说明，但暂时没有可展示的统计过程。';
  }
  return `当前结果一共基于 ${ruleFirstInputCount.value} 条合并请求逐步检查，最终筛出 ${ruleFinalOutputCount.value} 条需要关注的记录，占原始数据的 ${ruleFinalRetainedRate.value}。`;
});
const ruleExclusionSteps = computed(() => ruleExplanationSteps.value.slice(1));

function createFallbackRuleExplanation(reason: string): StatisticBoardRuleExplanationResponse {
  return {
    boardKey: 'code-review-illegal-records',
    supported: false,
    title: '代码走查非法记录规则说明',
    version: null,
    scopeDescription: null,
    summary: null,
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: reason,
  };
}

function syncAppliedRuleConfig() {
  const stored = loadStoredCodeReviewRuleConfig();
  if (!stored) {
    appliedRuleConfig.value = null;
    return;
  }
  const normalized = normalizeCodeReviewRuleConfig(stored, buildCodeReviewRuleFields(filterOptions.value));
  appliedRuleConfig.value = hasReadyCodeReviewRuleConfig(normalized, buildCodeReviewRuleFields(filterOptions.value))
    ? normalized
    : null;
}

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as CodeReviewIllegalRecordRowResponse) ?? null;
  detailVisible.value = true;
}

function openRuleConfig() {
  void router.push({
    path: '/code-review/illegal-records/rule-config',
    query: route.query,
  });
}

async function loadFilterOptions() {
  filterOptions.value = await api.getCodeReviewIllegalRecordFilterOptions(route.query.projectId as string | undefined);
  syncAppliedRuleConfig();
}

async function loadSyncStatus() {
  try {
    syncStatus.value = await api.getCodeReviewIllegalRecordRealtimeStatus();
  } catch {
    syncStatus.value = null;
  }
}

async function loadRuleExplanation() {
  ruleExplanationLoading.value = true;
  try {
    ruleExplanation.value = await api.getCodeReviewIllegalRecordRuleExplanation();
  } catch {
    ruleExplanation.value = createFallbackRuleExplanation('规则说明加载失败，请稍后重试。');
  } finally {
    ruleExplanationLoading.value = false;
  }
}

async function loadTableData() {
  const response = await api.getCodeReviewIllegalRecords({
    projectId: route.query.projectId as string | undefined,
    repositoryName: String(route.query.repositoryName ?? ''),
    mergedAtStart: String(route.query.mergedAtStart ?? ''),
    mergedAtEnd: String(route.query.mergedAtEnd ?? ''),
    keyword: String(route.query.keyword ?? ''),
    projectName: String(route.query.projectName ?? ''),
    requestType: String(route.query.requestType ?? ''),
    targetBranch: String(route.query.targetBranch ?? ''),
    mergedBy: String(route.query.mergedBy ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    illegalType: String(route.query.illegalType ?? ''),
    mergeRequestIid: String(route.query.mergeRequestIid ?? ''),
    owner: String(route.query.owner ?? ''),
    filterGroup: buildFilterPayload(),
    page: page.value,
    size: pageSize.value,
    sortBy: sortBy.value || 'mergedAt',
    sortOrder: (sortOrder.value || 'desc') as 'asc' | 'desc',
    ruleConfig: appliedRuleConfig.value?.enabled ? appliedRuleConfig.value : null,
  });
  rows.value = response.records;
  total.value = response.total;
}

bindLoader(async () => {
  try {
    await loadFilterOptions();
    initializeFromQuery(route.query);
    await loadTableData();
    await loadSyncStatus();
    await loadRuleExplanation();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '非法记录数据加载失败');
    rows.value = [];
    total.value = 0;
  }
});

async function handleReset() {
  resetDraft();
  await patchQuery({
    ...buildResetQueryPatch(route.query),
    page: 1,
    sortBy: 'mergedAt',
    sortOrder: 'desc',
    keyword: null,
    repositoryName: null,
    mergedAtStart: null,
    mergedAtEnd: null,
    projectName: null,
    requestType: null,
    targetBranch: null,
    mergedBy: null,
    moduleName: null,
    illegalType: null,
    mergeRequestIid: null,
    owner: null,
  });
}

async function handleQuery() {
  await patchQuery({
    ...buildApplyQueryPatch(route.query),
    page: 1,
    keyword: null,
    repositoryName: null,
    mergedAtStart: null,
    mergedAtEnd: null,
    projectName: null,
    requestType: null,
    targetBranch: null,
    mergedBy: null,
    moduleName: null,
    illegalType: null,
    mergeRequestIid: null,
    owner: null,
  });
}

async function handleSizeChange(nextSize: number) {
  await patchQuery({ pageSize: nextSize, page: 1 });
}

async function handleCurrentChange(nextPage: number) {
  await patchQuery({ page: nextPage });
}

async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
  await patchQuery({
    sortBy: payload.prop || 'mergedAt',
    sortOrder: payload.order === 'ascending' ? 'asc' : 'desc',
    page: 1,
  });
}

async function handleClearFilter(key: string) {
  if (key === 'personalRuleConfig') {
    const stored = loadStoredCodeReviewRuleConfig();
    const cleared = normalizeCodeReviewRuleConfig(
      stored ?? createDefaultCodeReviewRuleConfig(buildCodeReviewRuleFields(filterOptions.value)[0]),
      buildCodeReviewRuleFields(filterOptions.value),
    );
    cleared.enabled = false;
    saveStoredCodeReviewRuleConfig(cleared);
    appliedRuleConfig.value = null;
    if (page.value === 1) {
      await loadTableData();
      return;
    }
    await patchQuery({ page: 1 });
    return;
  }
  if (key === 'mergedAtRange') {
    await patchQuery({ page: 1, mergedAtStart: null, mergedAtEnd: null });
    return;
  }
  await patchQuery({ page: 1, [key]: null });
}

function openRuleExplanation() {
  if (!ruleExplanation.value) {
    ruleExplanation.value = createFallbackRuleExplanation('规则说明暂未加载完成，请稍后再试。');
  }
  if (!ruleExplanation.value.supported) {
    ElMessage.warning(ruleExplanation.value.unsupportedReason || '当前页面暂不支持规则说明');
  }
  ruleExplanationVisible.value = true;
}

function ruleStepRetainedRate(step: { inputCount: number; outputCount: number }) {
  if (!step.inputCount) {
    return '0%';
  }
  return `${((step.outputCount / step.inputCount) * 100).toFixed(1)}%`;
}

function ruleStepSummary(step: { key?: string; inputCount: number; outputCount: number }, index: number) {
  if (step.key === 'illegal-total') {
    return `第 ${index + 1} 步检查后，筛出 ${step.outputCount} 条需要关注的记录，占原始数据的 ${ruleStepRetainedRate(step)}。`;
  }
  return `在已经筛出的非法记录里，有 ${step.outputCount} 条符合第 ${index} 项规则，占全部非法记录的 ${ruleStepRetainedRate(step)}。`;
}

function metricFormulaSummary(metric: { label: string; definition: string; formula: string; note?: string | null }) {
  return `${metric.label}：${metric.definition}`;
}
</script>

<template>
  <section class="record-page-shell">
    <BaseRecordTable
      :columns="columns"
      :rows="tableRows"
      :loading="isTableLoading"
      :page="page"
      :page-size="pageSize"
      :total="total"
      :active-filter-tags="conditionActiveFilterTags"
      :show-search="false"
      :show-refresh="false"
      :empty-description="tableEmptyDescription"
      @reset="handleReset"
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
        <div class="record-page-toolbar-meta">
          <div class="record-page-toolbar-title">非法记录明细</div>
          <div class="record-page-toolbar-desc">代码走查结果明细与异常记录工作区</div>
        </div>
      </template>

      <template #toolbar-actions>
        <div class="record-page-summary">
          <SyncMetaBadge :value="lastSyncedText" />
          <el-tag v-if="appliedRuleConfig?.enabled" effect="plain" type="success" class="record-page-config-tag">
            已按我的规则判定
          </el-tag>
          <el-button
            plain
            size="small"
            :icon="InfoFilled"
            :loading="ruleExplanationLoading"
            @click="openRuleExplanation"
          >
            规则说明
          </el-button>
          <el-button plain size="small" :icon="Setting" @click="openRuleConfig">
            规则配置
          </el-button>
          <span class="record-page-summary-divider" />
          <span class="record-page-summary-label">当前排序</span>
          <el-tag effect="plain" type="info" class="record-page-sort-tag">
            {{ sortBy || 'mergedAt' }} / {{ sortOrder || 'desc' }}
          </el-tag>
        </div>
      </template>

      <template #row-actions="{ row }">
        <el-button class="record-detail-trigger" link @click="openDetailDrawer(row)">查看详情</el-button>
      </template>
    </BaseRecordTable>

    <el-drawer
      v-model="detailVisible"
      size="540px"
      destroy-on-close
      class="record-detail-drawer"
    >
      <template #header>
        <div v-if="selectedRow" class="record-detail-header">
          <div class="record-detail-header-main">
            <div class="record-detail-header-kicker">代码走查详情</div>
            <div class="record-detail-header-title">MR #{{ selectedRow.mergeRequestIid }}</div>
            <div class="record-detail-header-meta">
              <span class="record-detail-meta-item">{{ selectedRow.projectName || '-' }}</span>
              <span class="record-detail-meta-dot" />
              <span class="record-detail-meta-item">{{ selectedRow.targetBranch || '-' }}</span>
              <span class="record-detail-meta-dot" />
              <span class="record-detail-meta-item">{{ selectedRow.mergedBy || '-' }}</span>
            </div>
          </div>
          <div class="record-detail-header-actions">
            <el-link
              v-if="selectedRow.mergeRequestLink"
              :href="selectedRow.mergeRequestLink"
              target="_blank"
              type="primary"
              :underline="false"
              class="record-detail-header-link"
            >
              打开 GitLab
            </el-link>
          </div>
        </div>
      </template>

      <template v-if="selectedRow">
        <section class="record-detail-section">
          <div class="record-detail-section-title">基础信息</div>
          <el-descriptions :column="2" border size="small" class="record-detail-descriptions">
            <el-descriptions-item label="合并请求编号">
              <el-link
                v-if="selectedRow.mergeRequestLink"
                :href="selectedRow.mergeRequestLink"
                target="_blank"
                type="primary"
              >
                {{ selectedRow.mergeRequestIid }}
              </el-link>
              <span v-else>{{ selectedRow.mergeRequestIid }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="请求类型">{{ selectedRow.requestType || '-' }}</el-descriptions-item>
            <el-descriptions-item label="所属项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="代码库">{{ selectedRow.repositoryName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="标注责任人">{{ selectedRow.owner || '-' }}</el-descriptions-item>
            <el-descriptions-item label="合并人">{{ selectedRow.mergedBy || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模块名">{{ selectedRow.moduleName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="合并目标分支">{{ selectedRow.targetBranch || '-' }}</el-descriptions-item>
            <el-descriptions-item label="合并时间">{{ formatDateTime(selectedRow.mergedAt) }}</el-descriptions-item>
            <el-descriptions-item label="项目 ID">{{ selectedRow.projectId ?? '-' }}</el-descriptions-item>
          </el-descriptions>
        </section>

        <section class="record-detail-section">
          <div class="record-detail-section-title">合并请求内容</div>
          <div class="record-detail-content">{{ selectedRow.mergeRequestContent || '-' }}</div>
        </section>

        <section class="record-detail-section">
          <div class="record-detail-section-title">非法判定</div>
          <div class="record-detail-tags">
            <el-tag
              v-for="illegalType in selectedRow.illegalTypes"
              :key="illegalType"
              type="warning"
              effect="plain"
            >
              {{ illegalType }}
            </el-tag>
            <span v-if="!selectedRow.illegalTypes.length" class="record-detail-empty">-</span>
          </div>
        </section>

        <section class="record-detail-section">
          <div class="record-detail-section-title">度量指标</div>
          <div class="record-detail-metrics">
            <article class="record-detail-metric-card">
              <span class="record-detail-metric-label">代码注释比例</span>
              <strong class="record-detail-metric-value">{{ formatPercent(selectedRow.commentRate) }}</strong>
            </article>
            <article class="record-detail-metric-card">
              <span class="record-detail-metric-label">缺陷数量</span>
              <strong class="record-detail-metric-value">{{ formatMetric(selectedRow.defectCount) }}</strong>
            </article>
            <article class="record-detail-metric-card">
              <span class="record-detail-metric-label">新增代码行数</span>
              <strong class="record-detail-metric-value">{{ formatMetric(selectedRow.addedLines, ' 行') }}</strong>
            </article>
          </div>
        </section>
      </template>
    </el-drawer>

    <el-drawer
      v-model="ruleExplanationVisible"
      size="44%"
      destroy-on-close
      class="record-detail-drawer"
    >
      <template #header>
        <div class="record-detail-header">
          <div class="record-detail-header-main">
            <div class="record-detail-header-kicker">规则说明</div>
            <div class="record-detail-header-title">{{ ruleExplanation?.title || '代码走查非法记录规则说明' }}</div>
            <div class="record-detail-header-meta">
              <span class="record-detail-meta-item">版本 {{ ruleExplanation?.version || '-' }}</span>
              <span class="record-detail-meta-dot" />
              <span class="record-detail-meta-item">默认口径</span>
            </div>
          </div>
        </div>
      </template>

      <div v-loading="ruleExplanationLoading" class="record-rule-panel">
        <el-empty
          v-if="!ruleExplanation?.supported"
          :description="ruleExplanation?.unsupportedReason || '当前页面暂不支持规则说明。'"
        />

        <template v-else>
          <section class="record-detail-section">
            <div class="record-detail-section-title">先看结论</div>
            <div class="record-rule-summary-card">
              <div class="record-rule-summary-main">{{ qaFriendlyRuleSummary }}</div>
              <div v-if="ruleExplanation?.summary" class="record-rule-summary-sub">{{ ruleExplanation.summary }}</div>
            </div>
            <div class="record-rule-overview-grid">
              <article class="record-rule-overview-card">
                <span class="record-rule-overview-label">原始数据</span>
                <strong class="record-rule-overview-value">{{ ruleFirstInputCount }}</strong>
              </article>
              <article class="record-rule-overview-card">
                <span class="record-rule-overview-label">最终筛出</span>
                <strong class="record-rule-overview-value">{{ ruleFinalOutputCount }}</strong>
              </article>
              <article class="record-rule-overview-card">
                <span class="record-rule-overview-label">筛出比例</span>
                <strong class="record-rule-overview-value">{{ ruleFinalRetainedRate }}</strong>
              </article>
            </div>
          </section>

          <section class="record-detail-section">
            <div class="record-detail-section-title">这次统计包含哪些数据</div>
            <div class="record-detail-content">{{ ruleExplanation?.scopeDescription || '-' }}</div>
          </section>

          <section class="record-detail-section">
            <div class="record-detail-section-title">当前默认口径下，哪些情况会被筛出来</div>
            <div class="record-rule-card-grid">
              <article
                v-for="(step, index) in ruleExclusionSteps"
                :key="step.key"
                class="record-rule-card"
              >
                <div class="record-rule-card-title">规则 {{ index + 1 }}：{{ step.title }}</div>
                <div class="record-rule-card-description">{{ step.description }}</div>
                <div class="record-rule-card-summary">{{ ruleStepSummary(step, index + 1) }}</div>
                <div class="record-rule-card-stats">
                  <span class="record-rule-card-stat">{{ step.key === 'illegal-total' ? '筛出' : '符合该类' }} {{ step.outputCount }} 条</span>
                  <span class="record-rule-card-stat">{{ step.key === 'illegal-total' ? '占原始' : '占全部非法' }} {{ ruleStepRetainedRate(step) }}</span>
                  <span class="record-rule-card-stat">检查基数 {{ step.inputCount }} 条</span>
                </div>
              </article>
            </div>
          </section>

          <section class="record-detail-section">
            <div class="record-detail-section-title">数据是怎么一步步变化的</div>
            <div class="record-process-chain">
              <article
                v-for="(step, index) in ruleExplanationSteps"
                :key="`${step.key}-process`"
                class="record-process-card"
              >
                <div class="record-process-step">第 {{ index + 1 }} 步</div>
                <div class="record-process-title">{{ step.title }}</div>
                <div class="record-process-value">{{ step.outputCount }} 条</div>
                <div class="record-process-note">
                  {{ index === 0 ? '这是最开始纳入检查的合并请求。' : `这一轮处理后，共有 ${step.outputCount} 条被筛出来。` }}
                </div>
              </article>
            </div>
          </section>

          <section class="record-detail-section">
            <div class="record-detail-section-title">最后这些数字怎么算</div>
            <div class="record-formula-card-grid">
              <article
                v-for="metric in ruleExplanationMetrics"
                :key="metric.key"
                class="record-formula-card"
              >
                <div class="record-formula-card-title">{{ metric.label }}</div>
                <div class="record-formula-card-definition">{{ metricFormulaSummary(metric) }}</div>
                <div class="record-formula-card-formula">{{ metric.formula }}</div>
                <div v-if="metric.note" class="record-formula-card-note">{{ metric.note }}</div>
              </article>
            </div>
          </section>
        </template>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.record-page-shell {
  display: grid;
  gap: 12px;
}

.record-page-toolbar-meta {
  display: grid;
  gap: 2px;
}

.record-page-toolbar-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.92);
  line-height: 1.2;
}

.record-page-toolbar-desc {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.45);
  line-height: 1.4;
}

.record-page-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.record-page-summary-label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.record-page-summary-divider {
  width: 1px;
  height: 14px;
  background: rgba(15, 23, 42, 0.1);
}

.record-page-sort-tag,
.record-page-config-tag {
  border-radius: 999px;
}

.record-detail-trigger {
  padding-inline: 0;
  font-weight: 500;
  color: rgba(37, 99, 235, 0.88);
}

.record-detail-trigger:hover {
  color: rgb(29, 78, 216);
}

.record-detail-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

.record-detail-drawer :deep(.el-drawer__body) {
  padding-top: 16px;
  background: #fafafa;
}

.record-detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.record-detail-header-main {
  display: grid;
  gap: 8px;
}

.record-detail-header-kicker {
  font-size: 12px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.42);
  letter-spacing: 0.02em;
}

.record-detail-header-title {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
  color: rgba(15, 23, 42, 0.94);
}

.record-detail-header-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.record-detail-meta-item {
  font-size: 13px;
  color: rgba(15, 23, 42, 0.58);
}

.record-detail-meta-dot {
  width: 4px;
  height: 4px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.18);
}

.record-detail-header-actions {
  display: flex;
  align-items: center;
}

.record-detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 16px;
}

.record-detail-section-title {
  font-size: 12px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.record-detail-content {
  padding: 12px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.76);
  line-height: 1.6;
  font-size: 13px;
}

.record-detail-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.record-detail-empty {
  color: rgba(15, 23, 42, 0.4);
}

.record-detail-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.record-detail-metric-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.record-detail-metric-label {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.48);
}

.record-detail-metric-value {
  font-size: 22px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.92);
}

.record-rule-panel {
  display: grid;
  gap: 16px;
}

.record-rule-summary-card {
  display: grid;
  gap: 8px;
  padding: 16px 18px;
  border-radius: 12px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
}

.record-rule-summary-main {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.9);
}

.record-rule-summary-sub {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.66);
}

.record-rule-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.record-rule-overview-card,
.record-rule-card,
.record-formula-card,
.record-process-card {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #f0f0f0;
}

.record-rule-overview-label {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.52);
}

.record-rule-overview-value,
.record-process-value {
  font-size: 22px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.92);
}

.record-rule-card-grid,
.record-formula-card-grid {
  display: grid;
  gap: 12px;
}

.record-rule-card-title,
.record-formula-card-title,
.record-process-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.record-rule-card-description,
.record-formula-card-definition,
.record-formula-card-note,
.record-process-note {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.record-rule-card-summary,
.record-formula-card-formula {
  padding: 10px 12px;
  border-radius: 12px;
  background: #fafafa;
  border: 1px solid #f5f5f5;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.8);
}

.record-rule-card-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.record-rule-card-stat {
  padding: 6px 10px;
  border-radius: 999px;
  background: #fafafa;
  font-size: 12px;
  color: #595959;
}

.record-process-chain {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.record-process-step {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.46);
}

@media (max-width: 960px) {
  .record-rule-overview-grid,
  .record-detail-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
