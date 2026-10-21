<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { LocationQuery } from 'vue-router';
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
import { useAcceptedPageView } from '../composables/useAcceptedPageView';
import StatisticBoardFreshnessBanner from '../components/StatisticBoardFreshnessBanner.vue';
import { buildFilterGroupFromRouteQuery } from '../components/statistic-board-route-query';
import { sanitizeFilterDraftGroup } from '../components/statistic-board-filters';
import type { ReadViewTransport } from '../types/api/read-view';
import type {
  CodeReviewIllegalRecordFilterOptionsResponse,
  CodeReviewIllegalRecordRowResponse,
  CodeReviewIllegalRecordListResponse,
  StatisticBoardRuleExplanationResponse,
  OptionItemResponse,
} from '../types/api';
import { useRuleExplanationPanel } from '../composables/useRuleExplanationPanel';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { useRealtimeRefreshLifecycle } from '../composables/useRealtimeRefreshLifecycle';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { CODE_REVIEW_RECORD_QUERY_KEYS } from '../composables/record-route-query-keys';
import { useRouteTableState } from '../composables/useRouteTableState';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { useRecordPageController } from '../composables/useRecordPageController';
import { useRecordTableFilterPriority } from '../composables/useRecordTableFilterPriority';
import type { RecordTableActiveFilterTag } from '../types/record-table';
import { filterFieldsByBlockedDimensions } from '../components/filter-priority';
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
import { downloadBlob, formatExportFileDate } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';
import { CODE_REVIEW_SOURCE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';
import { useDataScope } from '../composables/useDataScope';

const PAGE_SCOPE_KEY = 'record-page:code-review-illegal-records';
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(PAGE_SCOPE_KEY);
const {
  route,
  reload,
  patchQuery,
  bindLoader,
  isTableLoading,
  watchedQuerySignature,
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
const rowRefreshLoadingKey = ref('');
const matchModeEnabled = ref(true);
const sourceOptions = ref<OptionItemResponse[]>([]);
interface IllegalFrame {
  response: CodeReviewIllegalRecordListResponse;
  options: CodeReviewIllegalRecordFilterOptionsResponse;
  sources: OptionItemResponse[];
  compatibilityRead: boolean;
  query: Readonly<Record<string, unknown>>;
  request: ReturnType<typeof buildCurrentQueryParams>;
  detail: CodeReviewIllegalRecordRowResponse | null;
  rule: StatisticBoardRuleExplanationResponse | null;
}
const acceptedPage = useAcceptedPageView<IllegalFrame>({
  pageKey: 'code-review-illegal-records',
  load: () => { throw new Error('非法记录页必须捕获整批查询'); },
});
const tableLoadError = ref('');
const acceptedQuery = computed(() => acceptedPage.accepted.value?.data.query ?? route.query);
const displayedSortOrder = computed(() => String(acceptedQuery.value.sortOrder ?? 'desc') as 'asc' | 'desc');
acceptedPage.start();
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  rows.value = frame?.data.response.records ?? [];
  total.value = frame?.data.response.total ?? 0;
  filterOptions.value = frame?.data.options ?? createDefaultCodeReviewFilterOptions();
  sourceOptions.value = frame?.data.sources ?? [];
  if (frame) {
    matchModeEnabled.value = frame.data.compatibilityRead;
    initializeFromQuery(frame.data.query as LocationQuery);
    if (detailVisible.value) selectedRow.value = frame.data.detail;
    resetRuleExplanation(); ruleExplanation.value = frame.data.rule;
  } else {
    selectedRow.value = null; detailVisible.value = false; resetRuleExplanation();
  }
}, { flush: 'sync' });
const showRefreshLatestData = computed(() => !matchModeEnabled.value);

const filterOptions = ref<CodeReviewIllegalRecordFilterOptionsResponse>(
  createDefaultCodeReviewFilterOptions(),
);
const standaloneFilterKeys = computed(() => ['source', conditionScopeFieldKey.value]);
const conditionFilterFields = computed(() =>
  filterFieldsByBlockedDimensions(
    createCodeReviewConditionFields(filterOptions.value, matchModeEnabled.value),
    standaloneFilterKeys.value,
  ),
);
const primaryFilters = computed(() =>
  buildCodeReviewPrimaryFilters(filterOptions.value, matchModeEnabled.value),
);

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  openRuleExplanation: openRuleExplanationPanel,
  resetRuleExplanation,
} = useRuleExplanationPanel({
  load: () => acceptedPage.readAccepted((_frame, read) => api.getCodeReviewIllegalRecordRuleExplanation(read)),
  fallback: (reason) => createCodeReviewRuleExplanationFallback(reason),
});

const {
  filterDraft,
  activeFilterTags: conditionFilterGroupTags,
  initializeFromQuery,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  buildConditionApplyQueryPatch,
  buildConditionResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);
const conditionFiltersExpanded = ref(false);

const {
  handleReset: baseHandleReset,
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

const {
  hiddenFilterKeys,
  disabledFilterKeys,
  highlightedFilterKeys,
  buildPriorityApplyPatch,
  notifyDetectedConflicts,
  guardQuickFilterChange,
  resetPriorityState,
} = useRecordTableFilterPriority({
  quickFilters: primaryFilters,
  quickValues: filterValues,
  filterDraft,
  standaloneFilterKeys,
  rangeKeys: CODE_REVIEW_RANGE_KEYS,
});

const activeFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  return [
    ...conditionFilterGroupTags.value,
    ...buildCodeReviewQuickFilterTags(
      filterValues.value,
      matchModeEnabled.value,
      {
        defaultRepositoryName: projectScopeUsesRepository.value
          ? defaultRepositoryNameForSource(effectiveSourceValue.value)
          : '',
      },
    ),
  ];
});

const sourceSwitchOptions = computed(() => buildScopeOptions(sourceOptions.value));
const sourceScopeProvider = computed(() => ({
  ...CODE_REVIEW_SOURCE_SCOPE_PROVIDER,
  defaultStrategy: 'empty' as const,
}));
const effectiveSourceValue = computed(() =>
  sourceScope.value.value || (matchModeEnabled.value ? 'cc' : sourceOptions.value[0]?.value || ''),
);
const activeSourceIsDgm = computed(() => effectiveSourceValue.value === 'dgm');
const columns = computed(() =>
  buildCodeReviewIllegalRecordColumns(matchModeEnabled.value, activeSourceIsDgm.value),
);
const projectScopeUsesRepository = computed(() => !activeSourceIsDgm.value);
const effectiveRepositoryName = computed(() =>
  String(route.query.repositoryName ?? '') || (projectScopeUsesRepository.value ? defaultRepositoryNameForSource(effectiveSourceValue.value) : ''),
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
const projectScopeLabel = computed(() =>
  projectScopeUsesRepository.value ? '所属项目' : '项目名称',
);
const projectScopePlaceholder = computed(() =>
  projectScopeUsesRepository.value ? '全部所属项目' : '全部项目名称',
);
const sourceScope = useDataScope({
  provider: sourceScopeProvider,
  options: computed(() => buildScopeOptions(sourceOptions.value)),
  clearQueryKeysOnChange: [
    ...CODE_REVIEW_QUERY_CLEAR_KEYS,
    'projectId',
    'filterGroup',
    'filterLogic',
  ],
  loading: isTableLoading,
  patchRouteQuery: patchQuery,
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
const showSelectedScope = computed(() => !activeSourceIsDgm.value);
const conditionScopeFieldKey = computed(() =>
  String(route.query.source ?? '').trim() === 'dgm' ? 'projectName' : 'repositoryName',
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

function rowActionKey(row: Record<string, unknown>) {
  return String(row.id ?? '');
}

async function handleRefreshMatchModeRow(row: Record<string, unknown>) {
  const raw = row.__raw as CodeReviewIllegalRecordRowResponse | undefined;
  if (!raw?.mergeRequestIid) {
    ElMessage.warning('当前记录缺少合并请求编号，无法刷新');
    return;
  }
  rowRefreshLoadingKey.value = rowActionKey(row);
  try {
    await api.refreshCodeReviewIllegalRecord({
      source: effectiveSourceValue.value || raw.sourceInstance,
      projectId: raw.projectId || 0,
      mergeRequestIid: raw.mergeRequestIid,
    });
    ElMessage.success('已刷新本条合并请求数据');
    await reload();
    void loadSyncStatus();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '刷新本条数据失败'));
  } finally {
    rowRefreshLoadingKey.value = '';
  }
}

async function loadFilterOptions(
  query: Readonly<Record<string, unknown>>,
  effectiveSource: string,
  matchMode: boolean,
  repositoryName: string,
  read: ReadViewTransport,
) {
  return api.getCodeReviewIllegalRecordFilterOptions(
    undefined,
    effectiveSource || undefined,
    String(query.projectName ?? ''),
    matchMode ? repositoryName : undefined,
    read,
  );
}

const {
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getCodeReviewIllegalRecordRealtimeStatus(effectiveSourceValue.value || undefined),
  emptyText: '-',
});

const {
  refreshButtonBusy,
  submitRefresh: submitRefreshLatestData,
  invalidateRefreshTracking,
} = useRealtimeRefreshLifecycle({
  submitRefresh: () => api.refreshCodeReviewIllegalRecords(),
  loadStatus: loadSyncStatus,
  loadData: async () => {
    await reload();
  },
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyError: (message) => ElMessage.error(message),
});

// 路由查询（来源、筛选、分页、排序）一旦切换，旧范围的刷新跟踪即失效。
watch(watchedQuerySignature, () => invalidateRefreshTracking());
onBeforeUnmount(() => {
  invalidateRefreshTracking();
  acceptedPage.dispose();
});

function buildCurrentQueryParams(
  includePagination: boolean,
  query: Readonly<Record<string, unknown>> = route.query,
  effectiveSource: string = effectiveSourceValue.value,
  repositoryName: string = effectiveRepositoryName.value,
) {
  return {
    projectId: undefined,
    repositoryName,
    mergedAtStart: String(query.mergedAtStart ?? ''),
    mergedAtEnd: String(query.mergedAtEnd ?? ''),
    keyword: String(query.keyword ?? ''),
    projectName: String(query.projectName ?? ''),
    requestType: String(query.requestType ?? ''),
    targetBranch: String(query.targetBranch ?? ''),
    mergedBy: String(query.mergedBy ?? ''),
    moduleName: String(query.moduleName ?? ''),
    illegalType: String(query.illegalType ?? ''),
    mergeRequestIid: String(query.mergeRequestIid ?? ''),
    owner: String(query.owner ?? ''),
    source: effectiveSource || undefined,
    filterGroup: sanitizeFilterDraftGroup(buildFilterGroupFromRouteQuery(query as LocationQuery)),
    ...(includePagination ? {
      page: parseQueryPositiveInteger(query.page, 1),
      size: parseQueryPositiveInteger(query.pageSize, 40),
    } : {}),
    sortBy: String(query.sortBy ?? '') || 'mergedAt',
    sortOrder: (String(query.sortOrder ?? '') || 'desc') as 'asc' | 'desc',
  };
}

function parseQueryPositiveInteger(value: unknown, fallback: number) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function defaultRepositoryNameForSource(source: string) {
  return source === 'dgm' ? 'DGM' : 'CrownCAD';
}

async function handleExport() {
  exportLoading.value = true;
  try {
    await acceptedPage.readAccepted((frame, read) => api.exportCodeReviewIllegalRecords(frame.request, read),
      (workbook, frame) => downloadBlob(workbook, codeReviewIllegalExportFilename(frame.query)));
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '导出失败'));
  } finally {
    exportLoading.value = false;
  }
}

function codeReviewIllegalExportFilename(query = acceptedQuery.value) {
  const isDgm = String(query.source ?? '') === 'dgm';
  const params = [
    String(query.projectName ?? ''),
    String(query.illegalType ?? ''),
    String(query.moduleName ?? ''),
    String(query.targetBranch ?? ''),
    String(query.owner ?? ''),
    String(query.mergeRequestIid ?? ''),
    ...(isDgm ? [] : [String(query.repositoryName ?? '')]),
    mergedAtRangeFilenamePart(query),
  ].map((value) => value.trim()).filter(Boolean);
  const prefix = params.length > 0 ? params.join('_') : '';
  if (isDgm) {
    return `${prefix}${formatExportFileDate(new Date())}_内核代码走查非法数据.xlsx`;
  }
  return `${prefix}代码走查非法数据.xlsx`;
}

function mergedAtRangeFilenamePart(query = acceptedQuery.value) {
  const start = String(query.mergedAtStart ?? '').trim();
  const end = String(query.mergedAtEnd ?? '').trim();
  return start && end ? `${start}-${end}` : '';
}

async function handleRefreshLatestData() {
  await submitRefreshLatestData();
}

bindLoader(async ({ query, isCurrent }) => {
  if (!isCurrent()) return;
  // 既有 CC/CrownCAD 默认范围先规范化；不能在取得租约后改变来源范围。
  const requestedSource = String(query.source ?? '').trim().toLowerCase() || 'cc';
  const requestedRepository = requestedSource === 'dgm' ? ''
    : String(query.repositoryName ?? '').trim() || defaultRepositoryNameForSource(requestedSource);
  const scope: Record<string, string> = { source: requestedSource };
  if (requestedRepository) scope.repositoryName = requestedRepository;
  const loaded = await acceptedPage.prepare(scope, async (_scope, read) => {
    const root = detailVisible.value ? selectedRow.value : null;
    const includeRule = ruleExplanationVisible.value;
    const context = await api.getCodeReviewIllegalRecordContext(read);
    const source = requestedSource;
    const repositoryName = requestedRepository;
    const acceptedQuery = { ...query, source, repositoryName };
    const request = buildCurrentQueryParams(true, acceptedQuery, source, repositoryName);
    const [response, options, detail, rule] = await Promise.all([
      api.getCodeReviewIllegalRecords(request, read),
      loadFilterOptions(acceptedQuery, source, context.compatibilityRead, repositoryName, read),
      root ? api.getCodeReviewIllegalRecord(root, read) : Promise.resolve(null),
      includeRule ? api.getCodeReviewIllegalRecordRuleExplanation(read) : Promise.resolve(null),
    ]);
    return { response, options, detail, rule, request, query: acceptedQuery,
      sources: context.sources, compatibilityRead: context.compatibilityRead };
  });
  if (!loaded && isCurrent()) throw acceptedPage.lastError.value ?? new Error('页面准备已取消');
}, () => { tableLoadError.value = ''; void loadSyncStatus(); }, (error) => {
  tableLoadError.value = getErrorMessage(error, '非法记录数据加载失败，已保留当前完整数据');
});
watch(() => [detailVisible.value, selectedRow.value?.legacySourceId, selectedRow.value?.sourceInstance,
  selectedRow.value?.mergeRequestId, ruleExplanationVisible.value],
  () => { if (acceptedPage.preparing.value) { acceptedPage.cancelPreparation(); void reload(); } });

async function handleClearFilter(key: string) {
  if (key === 'filterGroup') {
    resetPriorityState();
  }
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
  await patchQuery(buildPriorityApplyPatch(buildConditionApplyQueryPatch(route.query)));
}

async function handleConditionFilterReset() {
  resetPriorityState();
  await patchQuery(buildConditionResetQueryPatch(route.query));
}

async function handleReset() {
  resetPriorityState();
  await baseHandleReset();
}

async function handleQuery() {
  await patchQuery(buildPriorityApplyPatch({
    ...buildApplyQueryPatch(route.query),
    page: 1,
  }));
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

</script>

<template>
  <section class="record-page-shell">
    <StatisticBoardFreshnessBanner v-bind="acceptedPage.accepted.value?.metadata" />
    <el-alert v-if="tableLoadError" :title="tableLoadError" type="warning" :closable="false" />
    <BaseRecordTable
      v-if="acceptedPage.accepted.value || isTableLoading"
      :columns="columns"
      :rows="tableRows"
      :loading="isTableLoading && !acceptedPage.accepted.value"
      :page="parseQueryPositiveInteger(acceptedQuery.page, 1)"
      :page-size="parseQueryPositiveInteger(acceptedQuery.pageSize, 40)"
      :page-size-options="[40, 60, 100]"
      :total="total"
      :row-actions-width="132"
      :primary-filters="primaryFilters"
      :filter-values="filterValues"
      :hidden-filter-keys="hiddenFilterKeys"
      :disabled-filter-keys="disabledFilterKeys"
      :highlighted-filter-keys="highlightedFilterKeys"
      :quick-filter-change-guard="guardQuickFilterChange"
      :active-filter-tags="activeFilterTags"
      :show-search="false"
      :show-refresh="false"
      :settings-scope-key="PAGE_SCOPE_KEY"
      quick-filter-mode
      quick-filter-toggle-placement="filter-builder"
      :filter-builder-expanded="conditionFiltersExpanded"
      :empty-description="tableEmptyDescription"
      :sort-by="String(acceptedQuery.sortBy ?? 'mergedAt')"
      :sort-order="displayedSortOrder"
      default-sort-by="mergedAt"
      default-sort-order="desc"
      @reset="handleReset"
      @filter-change="handleFilterChange"
      @query="handleQuery"
      @clear-filter="handleClearFilter"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
      @sort-change="handleSortChange"
    >
      <template
        #filter-builder="{
          quickFilterToggleVisible,
          quickFilterToggleText,
          quickFilterToggleIcon,
          quickFilterSummaryChips,
          toggleQuickFilter,
        }"
      >
        <StatisticFilterBuilder
          :model-value="filterDraft"
          :fields="conditionFilterFields"
          :extra-summary-chips="quickFilterSummaryChips"
          add-button-text="添加条件"
          v-model:expanded="conditionFiltersExpanded"
          show-apply-actions
          @draft-change="notifyDetectedConflicts(true)"
          @apply="handleConditionFilterApply"
          @reset="handleConditionFilterReset"
        >
          <template #summary-actions-extra>
            <el-button
              v-if="quickFilterToggleVisible"
              class="app-action-button app-action-button--filter"
              plain
              :icon="quickFilterToggleIcon"
              @click="toggleQuickFilter()"
            >
              {{ quickFilterToggleText }}
            </el-button>
          </template>
        </StatisticFilterBuilder>
      </template>

      <template #toolbar-prefix>
        <div class="code-review-illegal-toolbar-status">
          <div
            v-if="sourceSwitchOptions.length > 1"
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
              dropdown-mode="adaptive-tags"
              @change="handleProjectScopeChange"
            />
          </div>
          <SyncMetaBadge :value="lastSyncedText" />
        </div>
      </template>

      <template #primary-actions>
        <div class="code-review-illegal-toolbar-actions">
          <el-button
            v-if="showRefreshLatestData"
            class="app-action-button app-action-button--refresh"
            plain
            :icon="RefreshRight"
            :loading="refreshButtonBusy"
            @click="handleRefreshLatestData"
          >
            刷新最新数据
          </el-button>
          <el-button
            class="app-action-button app-action-button--rule"
            plain
            :icon="InfoFilled"
            :loading="ruleExplanationLoading"
            @click="handleOpenRuleExplanation"
          >
            规则说明
          </el-button>
          <el-button
            class="app-action-button app-action-button--export"
            plain
            :icon="Download"
            :loading="exportLoading"
            @click="handleExport"
          >
            下载代码走查非法数据
          </el-button>
          <PageSettingsButton :scope-key="PAGE_SCOPE_KEY" />
        </div>
      </template>

      <template #row-actions="{ row }">
        <div class="code-review-row-actions">
          <el-button
            class="code-review-row-action-button"
            :icon="RefreshRight"
            size="small"
            plain
            :loading="rowRefreshLoadingKey === rowActionKey(row)"
            @click="handleRefreshMatchModeRow(row)"
          >
            刷新
          </el-button>
          <el-button
            class="code-review-row-action-button"
            :icon="View"
            size="small"
            plain
            @click="openDetailDrawer(row)"
          >
            详情
          </el-button>
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
              <template v-if="showSelectedScope">
                <span class="record-detail-meta-item">{{ selectedScopeName }}</span>
                <span class="record-detail-meta-dot" />
              </template>
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

      <el-empty v-if="!selectedRow" description="该记录已从最新完整数据中撤回" />
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
            <el-descriptions-item v-if="showSelectedScope" label="所属项目">{{ selectedScopeName }}</el-descriptions-item>
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
        { label: '命中记录', value: ruleFinalOutputCount },
        { label: '命中比例', value: ruleFinalRetainedRate },
      ]"
      :info-items="[
        { label: '当前使用规则版本', value: ruleExplanation?.version },
        { label: '统计范围', value: ruleExplanation?.scopeDescription },
        { label: '口径类型', value: '默认口径' },
      ]"
      :exclusion-steps="ruleExclusionSteps"
      :process-steps="ruleExplanationSteps"
      :metrics="ruleExplanationMetrics"
      exclusion-title="非法判定规则"
      process-title="处理流程"
      metrics-title="指标定义"
    />
  </section>
</template>

<style scoped>
.record-page-shell {
  display: grid;
  gap: 12px;
}

.code-review-illegal-toolbar-status,
.code-review-illegal-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.code-review-illegal-toolbar-actions {
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
  gap: 6px;
  width: 100%;
  min-width: 0;
}

.code-review-row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.code-review-row-action-button {
  flex: 0 0 auto;
  min-width: 54px;
  height: 28px;
  padding: 0 6px;
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
