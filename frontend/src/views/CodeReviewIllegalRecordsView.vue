<script setup lang="ts">
import { computed, ref } from 'vue';
// 代码走查非法记录页承接固定老平台口径下的记录检索结果，重点是让违规数据可筛选、可导出。
import { ElMessage } from '../element-plus-services';
import { Download, InfoFilled, RefreshRight, View } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import PageSettingsButton from '../components/PageSettingsButton.vue';
import RuleExplanationDrawer from '../components/RuleExplanationDrawer.vue';
import SmartSelect from '../components/base/SmartSelect.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordRowResponse,
  OptionItemResponse,
} from '../types/api';
import { useRuleExplanationPanel } from '../composables/useRuleExplanationPanel';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { CODE_REVIEW_RECORD_QUERY_KEYS } from '../composables/record-route-query-keys';
import { useRouteTableState } from '../composables/useRouteTableState';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { useRecordPageController } from '../composables/useRecordPageController';
import type { RecordTableActiveFilterTag } from '../types/record-table';
import {
  CODE_REVIEW_QUERY_CLEAR_KEYS,
  CODE_REVIEW_RANGE_KEYS,
  buildCodeReviewPrimaryFilters,
  buildCodeReviewIllegalRecordColumns,
  buildCodeReviewQuickFilterTags,
  buildCodeReviewRuleExplanationOverview,
  createCodeReviewConditionFields,
  createCodeReviewRuleExplanationFallback,
  createDefaultCodeReviewFilterOptions,
  formatCodeReviewDate,
  formatCodeReviewDateTime,
  formatCodeReviewMetric,
  mapCodeReviewIllegalTableRows,
} from './code-review-illegal-records-view-helpers';
import { downloadBlob } from '../utils/csv-download';
import { CODE_REVIEW_SOURCE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';
import { useDataScope } from '../composables/useDataScope';
import { formatBeijingDateTime } from '../utils/beijing-time';

const PAGE_SCOPE_KEY = 'record-page:code-review-illegal-records';
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(PAGE_SCOPE_KEY);
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
    pageSize: 40,
    sortBy: 'mergedAt',
    sortOrder: 'desc',
  },
  watchedQueryKeys: CODE_REVIEW_RECORD_QUERY_KEYS,
  autoRefreshOnEnter: readAutoRefreshOnEnter,
});

const rows = ref<CodeReviewIllegalRecordRowResponse[]>([]);
const total = ref(0);
const detailVisible = ref(false);
const selectedRow = ref<CodeReviewIllegalRecordRowResponse | null>(null);
const exportLoading = ref(false);
const realtimeRefreshLoading = ref(false);
const matchModeEnabled = ref(true);
const sourceOptions = ref<OptionItemResponse[]>([]);
const canRefreshLatestData = computed(() => authState.currentUser.role === 'ADMIN');
const showRefreshLatestData = computed(() => canRefreshLatestData.value && !matchModeEnabled.value);

const filterOptions = ref<CodeReviewIllegalRecordFilterOptionsResponse>(
  createDefaultCodeReviewFilterOptions(),
);
const conditionFilterFields = computed(() =>
  createCodeReviewConditionFields(filterOptions.value, matchModeEnabled.value),
);
const primaryFilters = computed(() =>
  buildCodeReviewPrimaryFilters(filterOptions.value, matchModeEnabled.value),
);

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  openRuleExplanation: openRuleExplanationPanel,
} = useRuleExplanationPanel({
  load: () => api.getCodeReviewIllegalRecordRuleExplanation(),
  fallback: (reason) => createCodeReviewRuleExplanationFallback(reason),
});

const {
  filterDraft,
  activeFilterTags: conditionFilterGroupTags,
  initializeFromQuery,
  buildFilterPayload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  buildConditionApplyQueryPatch,
  buildConditionResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);

const {
  handleReset,
  handleQuery,
  handleSizeChange,
  handleCurrentChange,
  handleSortChange,
  handleClearFilter: baseHandleClearFilter,
} = useRecordPageController({
  getRouteQuery: () => route.query,
  patchQuery,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  defaultSortBy: 'mergedAt',
  defaultSortOrder: 'desc',
  resetClearKeys: CODE_REVIEW_QUERY_CLEAR_KEYS,
  rangeKeys: CODE_REVIEW_RANGE_KEYS,
});

const filterValues = computed<Record<string, unknown>>(() => {
  const mergedAtStart = String(route.query.mergedAtStart ?? '');
  const mergedAtEnd = String(route.query.mergedAtEnd ?? '');
  return {
    mergeRequestIid: String(route.query.mergeRequestIid ?? ''),
    keyword: String(route.query.keyword ?? ''),
    owner: String(route.query.owner ?? ''),
    mergedBy: String(route.query.mergedBy ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    targetBranch: String(route.query.targetBranch ?? ''),
    illegalType: String(route.query.illegalType ?? ''),
    projectName: String(route.query.projectName ?? ''),
    repositoryName: String(route.query.repositoryName ?? ''),
    mergedAtRange: mergedAtStart && mergedAtEnd ? [mergedAtStart, mergedAtEnd] : [],
  };
});

const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  return [
    ...conditionFilterGroupTags.value,
    ...buildCodeReviewQuickFilterTags(filterValues.value, matchModeEnabled.value),
  ];
});

const columns = computed(() => buildCodeReviewIllegalRecordColumns(matchModeEnabled.value));
const sourceSwitchOptions = computed(() => buildScopeOptions(sourceOptions.value));
const projectScopeUsesRepository = computed(
  () => matchModeEnabled.value && effectiveSourceValue.value !== 'dgm',
);
const sourceScopeProvider = computed(() => ({
  ...CODE_REVIEW_SOURCE_SCOPE_PROVIDER,
  defaultStrategy: 'empty' as const,
}));
const effectiveSourceValue = computed(() =>
  sourceScope.value.value || sourceOptions.value[0]?.value || (matchModeEnabled.value ? 'cc' : ''),
);
const effectiveRepositoryName = computed(() =>
  String(route.query.repositoryName ?? '') || (matchModeEnabled.value ? defaultRepositoryNameForSource(effectiveSourceValue.value) : ''),
);
const projectScopeValue = computed(() =>
  projectScopeUsesRepository.value
    ? effectiveRepositoryName.value
    : String(route.query.projectName ?? ''),
);
const projectScopeOptions = computed(() =>
  projectScopeUsesRepository.value
    ? filterOptions.value.repositoryNames ?? []
    : filterOptions.value.projectNames ?? [],
);
const projectScopeLabel = computed(() => (projectScopeUsesRepository.value ? '所属项目' : '项目'));
const projectScopePlaceholder = computed(() =>
  projectScopeUsesRepository.value ? '全部所属项目' : '全部项目',
);
const sourceScope = useDataScope({
  provider: sourceScopeProvider,
  options: computed(() => buildScopeOptions(sourceOptions.value)),
  clearQueryKeysOnChange: ['projectId', 'projectName', 'repositoryName'],
  mountToShell: true,
  loading: isTableLoading,
});

const tableEmptyDescription = computed(() =>
  '当前筛选条件下没有查询到非法记录。',
);

const tableRows = computed<Record<string, unknown>[]>(() => mapCodeReviewIllegalTableRows(rows.value));
const selectedScopeName = computed(() =>
  matchModeEnabled.value
    ? selectedRow.value?.repositoryName || '-'
    : selectedRow.value?.projectName || '-',
);

const ruleExplanationSteps = computed(() => ruleExplanation.value?.flowSteps || []);
const ruleExplanationMetrics = computed(() => ruleExplanation.value?.metricDefinitions || []);
const ruleOverview = computed(() => buildCodeReviewRuleExplanationOverview(ruleExplanation.value));
const ruleFirstInputCount = computed(() => ruleOverview.value.firstInputCount);
const ruleFinalOutputCount = computed(() => ruleOverview.value.finalOutputCount);
const ruleFinalRetainedRate = computed(() => ruleOverview.value.finalRetainedRate);
const qaFriendlyRuleSummary = computed(() => ruleOverview.value.summary);
const ruleExclusionSteps = computed(() => ruleExplanationSteps.value.slice(1));

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as CodeReviewIllegalRecordRowResponse) ?? null;
  detailVisible.value = true;
}

async function loadFilterOptions() {
  filterOptions.value = await api.getCodeReviewIllegalRecordFilterOptions(
    undefined,
    effectiveSourceValue.value || undefined,
    String(route.query.projectName ?? ''),
    matchModeEnabled.value ? effectiveRepositoryName.value : undefined,
  );
}

async function loadSourceOptions() {
  const options = await api.getCodeReviewMultiBoardSourceOptions();
  sourceOptions.value = Array.isArray(options) ? options : [];
}

async function loadMatchModeStatus() {
  if (!canRefreshLatestData.value) {
    matchModeEnabled.value = true;
    return;
  }
  const settings = await api.getCodeReviewMatchModeDbSettings();
  matchModeEnabled.value = settings.enabled;
}

function syncCodeReviewRouteDefaults() {
  const patch: Record<string, string | number | null> = {};
  if (!String(route.query.source ?? '').trim() && effectiveSourceValue.value) {
    patch.source = effectiveSourceValue.value;
  }
  if (matchModeEnabled.value && !String(route.query.repositoryName ?? '').trim()) {
    patch.repositoryName = defaultRepositoryNameForSource(effectiveSourceValue.value);
    patch.projectName = null;
    patch.targetBranch = null;
  }
  if (!Object.keys(patch).length) {
    return;
  }
  void patchQuery({
    ...patch,
    page: 1,
  }).catch(() => {
    // The table uses effective defaults for the current load; a failed URL sync should not blank the page.
  });
}

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getCodeReviewIllegalRecordRealtimeStatus(),
  emptyText: '-',
});

async function loadTableData() {
  const response = await api.getCodeReviewIllegalRecords(buildCurrentQueryParams(true));
  rows.value = response.records;
  total.value = response.total;
}

function buildCurrentQueryParams(includePagination: boolean) {
  return {
    projectId: undefined,
    repositoryName: effectiveRepositoryName.value,
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
    source: effectiveSourceValue.value || undefined,
    filterGroup: buildFilterPayload(),
    ...(includePagination ? { page: page.value, size: pageSize.value } : {}),
    sortBy: sortBy.value || 'mergedAt',
    sortOrder: (sortOrder.value || 'desc') as 'asc' | 'desc',
  };
}

function defaultRepositoryNameForSource(source: string) {
  return source === 'dgm' ? 'DGM' : 'CrownCAD';
}

async function handleExport() {
  exportLoading.value = true;
  try {
    const workbook = await api.exportCodeReviewIllegalRecords(buildCurrentQueryParams(false));
    downloadBlob(workbook, codeReviewIllegalExportFilename());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败');
  } finally {
    exportLoading.value = false;
  }
}

function codeReviewIllegalExportFilename() {
  const isDgm = effectiveSourceValue.value === 'dgm';
  const params = [
    String(route.query.projectName ?? ''),
    String(route.query.illegalType ?? ''),
    String(route.query.moduleName ?? ''),
    String(route.query.targetBranch ?? ''),
    String(route.query.owner ?? ''),
    String(route.query.mergeRequestIid ?? ''),
    ...(isDgm ? [] : [effectiveRepositoryName.value]),
    mergedAtRangeFilenamePart(),
  ].map((value) => value.trim()).filter(Boolean);
  const prefix = params.length > 0 ? params.join('_') : '';
  if (isDgm) {
    return `${prefix}${new Date().toLocaleString()}_内核代码走查非法数据.xlsx`;
  }
  return `${prefix}代码走查非法数据.xlsx`;
}

function mergedAtRangeFilenamePart() {
  const start = String(route.query.mergedAtStart ?? '').trim();
  const end = String(route.query.mergedAtEnd ?? '').trim();
  return start && end ? `${start}-${end}` : '';
}

async function handleRefreshLatestData() {
  realtimeRefreshLoading.value = true;
  try {
    let status = await api.refreshCodeReviewIllegalRecords();
    ElMessage.success(status.message || '已开始刷新最新数据');
    for (let attempt = 0; attempt < 8 && status.refreshing; attempt++) {
      await sleep(1000);
      status = (await loadSyncStatus()) ?? status;
    }
    await loadTableData();
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

bindLoader(async () => {
  try {
    await loadSourceOptions();
    await loadMatchModeStatus();
    syncCodeReviewRouteDefaults();
    await loadFilterOptions();
    initializeFromQuery(route.query);
    await loadTableData();
    await loadSyncStatus();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '非法记录数据加载失败');
    rows.value = [];
    total.value = 0;
  }
});

async function handleClearFilter(key: string) {
  if (key === 'mergedAtRange') {
    await patchQuery({ page: 1, mergedAtStart: null, mergedAtEnd: null });
    return;
  }
  await baseHandleClearFilter(key);
}

async function handleFilterChange(payload: { key: string; value: string | string[] | null }) {
  if (payload.key === 'mergedAtRange') {
    const [start, end] = Array.isArray(payload.value) ? payload.value : [];
    await patchQuery({ page: 1, mergedAtStart: start || null, mergedAtEnd: end || null });
    return;
  }
  await patchQuery({
    page: 1,
    [payload.key]: Array.isArray(payload.value) ? payload.value.join(',') || null : payload.value,
  });
}

async function handleOpenRuleExplanation() {
  await openRuleExplanationPanel();
  if (!ruleExplanation.value) {
    ruleExplanation.value = createCodeReviewRuleExplanationFallback(
      '规则说明暂未加载完成，请稍后再试。',
    );
  }
  if (!ruleExplanation.value.supported) {
    ElMessage.warning(ruleExplanation.value.unsupportedReason || '当前页面暂不支持规则说明');
  }
}

async function handleConditionFilterApply() {
  await patchQuery(buildConditionApplyQueryPatch(route.query));
}

async function handleConditionFilterReset() {
  await patchQuery(buildConditionResetQueryPatch(route.query));
}

async function handleProjectScopeChange(value: string | string[]) {
  const nextValue = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  await patchQuery(
    projectScopeUsesRepository.value
      ? {
          repositoryName: nextValue || null,
          projectName: null,
          projectId: null,
          mergedAtStart: null,
          mergedAtEnd: null,
          mergeRequestIid: null,
          owner: null,
          targetBranch: null,
          mergedBy: null,
          moduleName: null,
          illegalType: null,
          keyword: null,
          filterGroup: null,
          filterLogic: null,
          page: 1,
        }
      : { projectName: nextValue || null, repositoryName: null, projectId: null, page: 1 },
  );
}

async function handleSourceScopeChange(value: string | number | boolean | undefined) {
  const nextSource = String(value ?? '');
  if (!nextSource || nextSource === effectiveSourceValue.value) {
    return;
  }
  await sourceScope.setValue(nextSource);
}

const taskStartedText = computed(() =>
  syncStatus.value?.lastRefreshStartedAt
    ? formatBeijingDateTime(syncStatus.value.lastRefreshStartedAt, '')
    : '',
);
const taskDurationText = computed(() =>
  formatTaskDuration(
    syncStatus.value?.lastRefreshStartedAt,
    syncStatus.value?.lastRefreshFinishedAt,
    syncStatus.value?.refreshing,
  ),
);

function formatTaskDuration(startedAt?: string | null, finishedAt?: string | null, refreshing?: boolean | null) {
  if (!startedAt) {
    return '';
  }
  if (refreshing && !finishedAt) {
    return '进行中';
  }
  if (!finishedAt) {
    return '';
  }
  const start = new Date(startedAt).getTime();
  const finish = new Date(finishedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(finish) || finish < start) {
    return '';
  }
  const totalSeconds = Math.round((finish - start) / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes <= 0 ? `${seconds} 秒` : `${minutes} 分 ${seconds} 秒`;
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
      :page-size-options="[40, 60, 100]"
      :total="total"
      :row-actions-width="112"
      :primary-filters="primaryFilters"
      :filter-values="filterValues"
      :active-filter-tags="activeFilterTags"
      :show-search="false"
      :show-refresh="false"
      quick-filter-mode
      :empty-description="tableEmptyDescription"
      @reset="handleReset"
      @filter-change="handleFilterChange"
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
          show-apply-actions
          @apply="handleConditionFilterApply"
          @reset="handleConditionFilterReset"
        />
      </template>

      <template #primary-actions>
        <div class="code-review-illegal-toolbar-actions">
          <div
            v-if="matchModeEnabled && sourceSwitchOptions.length > 1"
            class="code-review-source-switch"
          >
            <span class="code-review-illegal-toolbar-label">数据源</span>
            <el-radio-group
              :model-value="effectiveSourceValue"
              size="small"
              @change="handleSourceScopeChange"
            >
              <el-radio-button
                v-for="option in sourceSwitchOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="code-review-project-scope">
            <span class="code-review-illegal-toolbar-label">{{ projectScopeLabel }}</span>
            <SmartSelect
              :model-value="projectScopeValue"
              :options="projectScopeOptions"
              :placeholder="projectScopePlaceholder"
              class="code-review-project-select"
              popper-class-extra="code-review-project-select-dropdown"
              @change="handleProjectScopeChange"
            />
          </div>
          <SyncMetaBadge :value="lastSyncedText" />
          <span v-if="taskStartedText" class="code-review-illegal-batch-meta">
            任务执行时间：{{ taskStartedText }}
          </span>
          <span v-if="taskDurationText" class="code-review-illegal-batch-meta">
            执行时长：{{ taskDurationText }}
          </span>
          <el-button
            v-if="showRefreshLatestData"
            plain
            :icon="RefreshRight"
            :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
            @click="handleRefreshLatestData"
          >
            刷新最新数据
          </el-button>
          <el-button
            plain
            :icon="InfoFilled"
            :loading="ruleExplanationLoading"
            @click="handleOpenRuleExplanation"
          >
            规则说明
          </el-button>
          <el-button plain :icon="Download" :loading="exportLoading" @click="handleExport">
            下载代码走查非法数据
          </el-button>
          <PageSettingsButton :scope-key="PAGE_SCOPE_KEY" />
          <span class="code-review-illegal-toolbar-divider" />
          <span class="code-review-illegal-toolbar-label">当前排序</span>
          <el-tag effect="plain" type="info" class="record-page-sort-tag">
            {{ sortBy || 'mergedAt' }} / {{ sortOrder || 'desc' }}
          </el-tag>
        </div>
      </template>

      <template #row-actions="{ row }">
        <div class="code-review-row-actions">
          <el-tooltip content="查看代码走查详情" placement="top">
            <el-button
              class="code-review-row-action-button"
              :icon="View"
              size="small"
              plain
              @click="openDetailDrawer(row)"
            >
              查看详细
            </el-button>
          </el-tooltip>
        </div>
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
              <span class="record-detail-meta-item">{{ selectedScopeName }}</span>
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
          <div class="record-detail-section-title">详细内容</div>
          <el-descriptions :column="2" border size="small" class="record-detail-descriptions">
            <el-descriptions-item label="走查编号">
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
            <el-descriptions-item label="所属项目">{{ selectedScopeName }}</el-descriptions-item>
            <el-descriptions-item label="走查时间">{{ formatCodeReviewDate(selectedRow.codeWalkthroughDate) }}</el-descriptions-item>
            <el-descriptions-item label="模块名">{{ selectedRow.moduleName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="被走查人">{{ selectedRow.author || '-' }}</el-descriptions-item>
            <el-descriptions-item label="走查人">{{ selectedRow.reviewerNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="被指派人">{{ selectedRow.assigneeNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="合并时间">{{ formatCodeReviewDateTime(selectedRow.mergedAt) }}</el-descriptions-item>
            <el-descriptions-item label="合并人">{{ selectedRow.mergedBy || '-' }}</el-descriptions-item>
            <el-descriptions-item label="合并目标分支">{{ selectedRow.targetBranch || '-' }}</el-descriptions-item>
            <el-descriptions-item label="走查工作量（分钟）">
              {{ formatCodeReviewMetric(selectedRow.reviewDurationMinutes) }}
            </el-descriptions-item>
            <el-descriptions-item label="新增走查代码行数（LOC）">
              {{ formatCodeReviewMetric(selectedRow.addedLines) }}
            </el-descriptions-item>
            <el-descriptions-item label="缺陷数（个）">
              {{ formatCodeReviewMetric(selectedRow.defectCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="代码走查速率（LOC/H）">
              {{ formatCodeReviewMetric(selectedRow.reviewSpeedLocPerHour) }}
            </el-descriptions-item>
            <el-descriptions-item label="代码走查缺陷密度">
              {{ formatCodeReviewMetric(selectedRow.defectDensityPerKloc) }}
            </el-descriptions-item>
            <el-descriptions-item label="代码走查效率">
              {{ formatCodeReviewMetric(selectedRow.reviewEfficiencyPerHour) }}
            </el-descriptions-item>
          </el-descriptions>
        </section>
      </template>
    </el-drawer>

    <RuleExplanationDrawer
      v-model="ruleExplanationVisible"
      :loading="ruleExplanationLoading"
      :title="ruleExplanation?.title || '代码走查非法记录规则说明'"
      :supported="Boolean(ruleExplanation?.supported)"
      :unsupported-reason="ruleExplanation?.unsupportedReason || '当前页面暂不支持规则说明。'"
      :summary-main="qaFriendlyRuleSummary"
      :summary="ruleExplanation?.summary"
      :overview-cards="[
        { label: '原始数据', value: ruleFirstInputCount },
        { label: '最终筛出', value: ruleFinalOutputCount },
        { label: '筛出比例', value: ruleFinalRetainedRate },
      ]"
      :info-items="[
        { label: '当前使用规则版本', value: ruleExplanation?.version },
        { label: '统计范围', value: ruleExplanation?.scopeDescription },
        { label: '口径类型', value: '默认口径' },
      ]"
      :exclusion-steps="ruleExclusionSteps"
      :process-steps="ruleExplanationSteps"
      :metrics="ruleExplanationMetrics"
      exclusion-title="当前默认口径下，哪些情况会被筛出来"
      process-title="数据是怎么一步步变化的"
      metrics-title="最后这些数字怎么算"
    />
  </section>
</template>

<style scoped>
.record-page-shell {
  display: grid;
  gap: 12px;
}

.code-review-illegal-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.code-review-illegal-toolbar-label {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.code-review-project-scope {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.code-review-source-switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.code-review-source-switch :deep(.el-radio-button__inner) {
  min-width: 64px;
}

.code-review-project-select {
  width: 220px;
  max-width: min(220px, 48vw);
}

.code-review-illegal-batch-meta {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  color: rgba(15, 23, 42, 0.62);
  background: rgba(255, 255, 255, 0.78);
  font-size: 12px;
  white-space: nowrap;
}

.code-review-illegal-toolbar-divider {
  width: 1px;
  height: 14px;
  background: rgba(15, 23, 42, 0.1);
}

@media (max-width: 1180px) {
  .code-review-illegal-toolbar-actions {
    justify-content: flex-start;
  }
}

.record-page-sort-tag,
.record-page-config-tag {
  border-radius: 999px;
}

.code-review-row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
}

.code-review-row-action-button {
  flex: 0 0 auto;
  min-width: 76px;
  height: 28px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
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

</style>
