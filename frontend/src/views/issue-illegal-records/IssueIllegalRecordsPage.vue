<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import type { LocationQuery } from 'vue-router';
import { useAcceptedPageView } from '../../composables/useAcceptedPageView';
import StatisticBoardFreshnessBanner from '../../components/StatisticBoardFreshnessBanner.vue';
import { buildFilterGroupFromRouteQuery } from '../../components/statistic-board-route-query';
import { sanitizeFilterDraftGroup } from '../../components/statistic-board-filters';
// 非法记录通用页承接系统测试和客户问题两类场景，差异通过配置和接口适配传入。
// 页面内部统一处理关键词、条件筛选、规则说明、分页和导出，避免两个业务域重复实现。
import { ElMessage } from '../../element-plus-services';
import { Download, InfoFilled, Refresh, RefreshRight, View } from '@element-plus/icons-vue';
import BaseRecordTable from '../../components/base/BaseRecordTable.vue';
import IssueStatusTags from '../../components/IssueStatusTags.vue';
import PageSettingsButton from '../../components/PageSettingsButton.vue';
import PageStateShell from '../../components/base/PageStateShell.vue';
import RuleExplanationDrawer from '../../components/RuleExplanationDrawer.vue';
import SyncMetaBadge from '../../components/realtime/SyncMetaBadge.vue';
import StatisticFilterBuilder from '../../components/StatisticFilterBuilder.vue';
import { authState } from '../../composables/auth-state';
import { hasPermission } from '../../feature-manifest';
import { useConditionFilterGroupState } from '../../composables/useConditionFilterGroupState';
import { useRecordPageController } from '../../composables/useRecordPageController';
import { useDataScope } from '../../composables/useDataScope';
import { ISSUE_RECORD_QUERY_KEYS } from '../../composables/record-route-query-keys';
import { useRouteTableState } from '../../composables/useRouteTableState';
import { useRuleExplanationPanel } from '../../composables/useRuleExplanationPanel';
import { useRealtimeWorkspaceStatus } from '../../composables/useRealtimeWorkspaceStatus';
import { useRealtimeRefreshLifecycle } from '../../composables/useRealtimeRefreshLifecycle';
import { usePageAutoRefreshPreference } from '../../composables/usePageAutoRefreshPreference';
import { useRecordTableFilterPriority } from '../../composables/useRecordTableFilterPriority';
import type { StatisticBoardRuleExplanationResponse, StatisticFilterField } from '../../types/api';
import type { IssueIllegalRecordRow, IssueIllegalRecordsPageConfig } from './issue-illegal-records-types';
import { downloadBlob } from '../../utils/csv-download';
import { filterFieldsByBlockedDimensions } from '../../components/filter-priority';
import { formatLocalDateTime as formatDateTime } from '../../utils/beijing-time';
import { getErrorMessage } from '../../utils/user-message';

const props = defineProps<IssueIllegalRecordsPageConfig>();
const pageScopeKey = computed(() => `record-page:${props.workspaceKey}`);
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(() => pageScopeKey.value);

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
    pageSize: 20,
    sortBy: props.defaultSortBy ?? 'updatedAt',
    sortOrder: props.defaultSortOrder ?? 'desc',
  },
  watchedQueryKeys: ISSUE_RECORD_QUERY_KEYS,
  autoRefreshOnEnter: readAutoRefreshOnEnter,
});

const rows = ref<IssueIllegalRecordRow[]>([]);
const total = ref(0);
const pageInitialized = ref(false);
const filterOptionsLoaded = ref(false);
const detailVisible = ref(false);
const selectedRow = ref<IssueIllegalRecordRow | null>(null);
const exportLoading = ref(false);
const primaryDefaultPatchInFlight = ref(false);
const filterOptions = ref({ ...props.initialFilterOptions });
interface IllegalFrame {
  response: Awaited<ReturnType<typeof props.loadRecords>>;
  options: Awaited<ReturnType<typeof props.loadFilterOptions>>;
  query: Readonly<Record<string, unknown>>;
  request: ReturnType<typeof buildCurrentQueryParams>;
  detail: IssueIllegalRecordRow | null;
  rule: StatisticBoardRuleExplanationResponse | null;
}
const acceptedPage = useAcceptedPageView<IllegalFrame>({ pageKey: props.pageKey,
  load: () => { throw new Error('非法记录页需要捕获完整查询后准备'); } });
const pageLoadError = ref('');
const readMetadata = computed(() => acceptedPage.accepted.value?.metadata);
const acceptedQuery = computed(() => acceptedPage.accepted.value?.data.query ?? route.query);
acceptedPage.start();
onBeforeUnmount(acceptedPage.dispose);
watch(() => authState.currentUser?.username, acceptedPage.clearPrivateData);
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  rows.value = frame?.data.response.records ?? [];
  total.value = frame?.data.response.total ?? 0;
  filterOptions.value = frame?.data.options ?? { ...props.initialFilterOptions };
  filterOptionsLoaded.value = Boolean(frame);
  resetRuleExplanation();
  ruleExplanation.value = frame?.data.rule ?? null;
  if (frame) {
    initializeFromQuery(frame.data.query as LocationQuery);
    if (detailVisible.value) selectedRow.value = frame.data.detail;
  } else { selectedRow.value = null; detailVisible.value = false; }
}, { flush: 'sync' });
const canRefreshLatestData = computed(
  () => hasPermission(authState.currentUser, 'business_data.refresh') && Boolean(props.requestRealtimeRefresh),
);
const primaryFilters = computed(() => props.buildPrimaryFilters?.(filterOptions.value) ?? []);
const nativePrimarySelectKeys = computed(() => new Set(props.nativePrimarySelectKeys ?? []));
const standaloneFilterKeys = computed(() => [
  ...nativePrimarySelectKeys.value,
  ...(props.scopeProvider ? [props.scopeProvider.queryKey] : []),
]);
const nativePrimaryFilters = computed(() =>
  primaryFilters.value.filter((field) => field.type === 'select' && nativePrimarySelectKeys.value.has(field.key)),
);
const tablePrimaryFilters = computed(() =>
  primaryFilters.value.filter((field) => !nativePrimarySelectKeys.value.has(field.key)),
);
const pageReady = computed(() => Boolean(acceptedPage.accepted.value) || pageInitialized.value);
const primaryFilterValues = computed<Record<string, unknown>>(() =>
  primaryFilters.value.reduce<Record<string, unknown>>((values, field) => {
    values[field.key] = route.query[field.key] ?? '';
    return values;
  }, {}),
);

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () =>
    props.loadRealtimeStatus?.(buildCurrentQueryParams(false))
      ?? Promise.reject(new Error('Realtime status is not configured')),
  emptyText: '-',
});

const {
  refreshButtonBusy,
  submitRefresh: submitRefreshLatestData,
  invalidateRefreshTracking,
} = useRealtimeRefreshLifecycle({
  submitRefresh: () =>
    props.requestRealtimeRefresh
      ? props.requestRealtimeRefresh()
      : Promise.reject(new Error('Realtime refresh is not configured')),
  loadStatus: loadRealtimeStatus,
  loadData: reloadRefreshedOptionsAndRecords,
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyError: (message) => ElMessage.error(message),
});

// 路由查询（项目、筛选、分页、排序）一旦切换，旧范围的刷新跟踪即失效。
watch(watchedQuerySignature, () => invalidateRefreshTracking());
onBeforeUnmount(() => {
  invalidateRefreshTracking();
});

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  resetRuleExplanation,
  openRuleExplanation,
} = useRuleExplanationPanel({
  load: () => acceptedPage.readAccepted((frame, read) => props.loadRuleExplanation(frame.request.projectId, read)),
  fallback: (reason) => createFallbackRuleExplanation(reason),
});

const conditionFilterFields = computed<StatisticFilterField[]>(() => {
  return filterFieldsByBlockedDimensions(props.buildConditionFields(filterOptions.value), standaloneFilterKeys.value);
});

const scopeOptions = computed(() => props.buildScopeOptions?.(filterOptions.value) ?? []);
useDataScope({
  provider: props.scopeProvider,
  options: scopeOptions,
  mountToShell: true,
  patchRouteQuery: patchQuery,
});

watch(
  [primaryFilters, () => route.query],
  async () => {
    if (!filterOptionsLoaded.value || primaryDefaultPatchInFlight.value) {
      return;
    }
    await applyPrimaryFilterDefaults();
  },
  { immediate: true, deep: true },
);

const {
  filterDraft,
  activeFilterTags: conditionActiveFilterTags,
  initializeFromQuery,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  buildConditionApplyQueryPatch,
  buildConditionResetQueryPatch,
} = useConditionFilterGroupState(conditionFilterFields);
const conditionFiltersExpanded = ref(false);
const loadTableData = async () => { await reload(); };

const priorityQuickFilters = computed(() => [
  { key: 'keyword', label: '任意关键字', type: 'input' as const, placeholder: '输入任意关键字搜索', width: 260 },
  ...tablePrimaryFilters.value,
]);

const {
  hiddenFilterKeys,
  disabledFilterKeys,
  highlightedFilterKeys,
  buildPriorityApplyPatch,
  notifyDetectedConflicts,
  guardQuickFilterChange,
  resetPriorityState,
} = useRecordTableFilterPriority({
  quickFilters: priorityQuickFilters,
  quickValues: computed(() => ({ ...primaryFilterValues.value, keyword: String(route.query.keyword ?? '') })),
  filterDraft,
  standaloneFilterKeys,
  rangeKeys: {
    updatedAtRange: { startKey: 'updatedAtStart', endKey: 'updatedAtEnd' },
    createdAtRange: { startKey: 'createdAtStart', endKey: 'createdAtEnd' },
  },
});

const {
  handleReset: baseHandleReset,
  handleKeywordSearch,
  handleRefresh,
  handleSizeChange,
  handleCurrentChange,
  handleSortChange,
  handleClearFilter: handleBaseClearFilter,
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
const activeFilterTags = computed(() => [
  ...conditionActiveFilterTags.value,
  ...tablePrimaryFilters.value.flatMap((field) => {
    const value = primaryFilterValues.value[field.key];
    const normalizedValue = Array.isArray(value)
      ? value.map((item) => String(item)).filter(Boolean).join('、')
      : String(value ?? '');
    if (!normalizedValue) {
      return [];
    }
    const option = field.options?.find((item) => item.value === normalizedValue);
    return [{ key: field.key, label: field.label, value: option?.label || normalizedValue }];
  }),
]);
const ruleSteps = computed(() => ruleExplanation.value?.flowSteps ?? []);
const ruleFirstCount = computed(() => ruleSteps.value[0]?.inputCount ?? 0);
const ruleFinalCount = computed(() => ruleSteps.value.at(-1)?.outputCount ?? 0);
const ruleRetainedRate = computed(() =>
  ruleFirstCount.value ? `${((ruleFinalCount.value / ruleFirstCount.value) * 100).toFixed(1)}%` : '0%',
);
const ruleOverviewCards = computed(() => [
  { label: '原始数据', value: ruleFirstCount.value },
  { label: '命中记录', value: ruleFinalCount.value },
  { label: '命中比例', value: ruleRetainedRate.value },
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

async function applyPrimaryFilterDefaults() {
  if (!filterOptionsLoaded.value || primaryDefaultPatchInFlight.value) {
    return false;
  }
  const patch: Record<string, string | number> = {};
  for (const field of primaryFilters.value) {
    if (field.defaultStrategy !== 'first-available') {
      continue;
    }
    const currentValue = String(route.query[field.key] ?? '').trim();
    if (currentValue) {
      continue;
    }
    const fallback = field.options?.find((option) => option.value)?.value ?? '';
    if (fallback) {
      patch[field.key] = fallback;
    }
  }
  if (!Object.keys(patch).length) {
    return false;
  }
  primaryDefaultPatchInFlight.value = true;
  try {
    await patchQuery({ page: 1, ...patch });
  } finally {
    primaryDefaultPatchInFlight.value = false;
  }
  return true;
}

function buildCurrentQueryParams(
  includePagination: boolean,
  query: Readonly<Record<string, unknown>> = route.query,
) {
  return {
    projectId: props.fixedProjectId != null ? String(props.fixedProjectId)
      : (query.projectId as string | undefined) ?? (props.defaultProjectId == null ? undefined : String(props.defaultProjectId)),
    keyword: String(query.keyword ?? ''),
    issueIid: String(query.issueIid ?? ''),
    title: String(query.title ?? ''),
    projectName: String(query.projectName ?? ''),
    moduleName: String(query.moduleName ?? ''),
    testingPhase: String(query.testingPhase ?? ''),
    illegalReason: String(query.illegalReason ?? ''),
    severityLevel: String(query.severityLevel ?? ''),
    priorityLevel: String(query.priorityLevel ?? ''),
    issueState: String(query.issueState ?? ''),
    bugStatus: String(query.bugStatus ?? ''),
    category: String(query.category ?? ''),
    milestoneTitle: String(query.milestoneTitle ?? ''),
    authorName: String(query.authorName ?? ''),
    assigneeName: String(query.assigneeName ?? ''),
    createdAtStart: String(query.createdAtStart ?? ''),
    createdAtEnd: String(query.createdAtEnd ?? ''),
    updatedAtStart: String(query.updatedAtStart ?? ''),
    updatedAtEnd: String(query.updatedAtEnd ?? ''),
    filterGroup: sanitizeFilterDraftGroup(buildFilterGroupFromRouteQuery(query as LocationQuery)),
    ...(includePagination ? {
      page: parseQueryPositiveInteger(query.page, 1),
      size: parseQueryPositiveInteger(query.pageSize, 20),
    } : {}),
    sortBy: String(query.sortBy ?? '') || props.defaultSortBy || 'updatedAt',
    sortOrder: (String(query.sortOrder ?? '') || props.defaultSortOrder || 'desc') as 'asc' | 'desc',
  };
}

function parseQueryPositiveInteger(value: unknown, fallback: number) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

async function handleExport() {
  if (!props.exportRecords) {
    return;
  }
  exportLoading.value = true;
  try {
    await acceptedPage.readAccepted((frame, read) => props.exportRecords!({ ...frame.request, read }),
      exported => downloadBlob(exported, `${props.exportFilenamePrefix || props.title}.xlsx`));
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '导出失败'));
  } finally {
    exportLoading.value = false;
  }
}

async function handleRefreshLatestData() {
  await submitRefreshLatestData();
}

/** 刷新完成后准备列表、候选与打开详情，失败保留当前完整画面。 */
async function reloadRefreshedOptionsAndRecords() { await reload(); }

bindLoader(async ({ query, isCurrent }) => {
  if (!isCurrent()) return;
  const request = buildCurrentQueryParams(true, query);
  const scope: Record<string, string> = {};
  for (const key of ['projectId', 'milestoneTitle', 'testingPhase'] as const) {
    if (request[key]) scope[key] = String(request[key]);
  }
  const loaded = await acceptedPage.prepare(scope, async (_scope, read) => {
    const root = detailVisible.value ? selectedRow.value : null;
    const includeRule = ruleExplanationVisible.value;
    const [response, options, detail, rule] = await Promise.all([
      props.loadRecords({ ...request, read }), props.loadFilterOptions(request.projectId, read),
      root?.projectId && root.issueId ? props.loadRecord(root.sourceInstance || 'default', root.projectId, root.issueId, read) : Promise.resolve(null),
      includeRule ? props.loadRuleExplanation(request.projectId, read) : Promise.resolve(null),
    ]);
    return { response, options, detail, rule, request, query };
  });
  if (!loaded && isCurrent()) throw acceptedPage.lastError.value ?? new Error('页面准备已取消');
}, () => {
  pageInitialized.value = true; pageLoadError.value = ''; void loadRealtimeStatus();
}, error => {
  pageInitialized.value = true;
  pageLoadError.value = getErrorMessage(error, acceptedPage.accepted.value
    ? `${props.title}加载失败，已保留当前完整数据` : '尚无完整产出，请检查数据更新状态');
  ElMessage.error(pageLoadError.value);
});

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as IssueIllegalRecordRow) ?? null;
  detailVisible.value = true;
}

watch(() => `${detailVisible.value ? `${selectedRow.value?.sourceInstance}:${selectedRow.value?.projectId}:${selectedRow.value?.issueId}` : ''}:${ruleExplanationVisible.value}`,
  () => { if (acceptedPage.preparing.value) { acceptedPage.cancelPreparation(); void reload(); } });

async function handleClearFilter(key: string) {
  if (key === 'filterGroup') {
    resetPriorityState();
  }
  await handleBaseClearFilter(key);
}

async function handlePrimaryFilterChange(payload: { key: string; value: string | string[] | null }) {
  const value = Array.isArray(payload.value) ? payload.value.filter(Boolean).join(',') : String(payload.value ?? '');
  await patchQuery({ page: 1, [payload.key]: value || null });
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
      <el-alert v-if="pageLoadError" :title="pageLoadError" type="error" :closable="false" show-icon />
      <StatisticBoardFreshnessBanner v-if="readMetadata" :data-as-of="readMetadata.dataAsOf"
        :pending-updates="readMetadata.pendingUpdates" :source-state="readMetadata.sourceState"
        :source-state-detail="readMetadata.sourceStateDetail" :escalated="readMetadata.escalated" />
      <BaseRecordTable v-if="acceptedPage.accepted.value || isTableLoading"
        :columns="columns"
        :rows="tableRows"
        :loading="isTableLoading && !acceptedPage.accepted.value"
        :keyword-auto-search="true"
        :page="parseQueryPositiveInteger(acceptedQuery.page, 1)"
        :page-size="parseQueryPositiveInteger(acceptedQuery.pageSize, 20)"
        :total="total"
        :primary-filters="tablePrimaryFilters"
        :filter-values="primaryFilterValues"
        :hidden-filter-keys="hiddenFilterKeys"
        :disabled-filter-keys="disabledFilterKeys"
        :highlighted-filter-keys="highlightedFilterKeys"
        :quick-filter-change-guard="guardQuickFilterChange"
        :active-filter-tags="activeFilterTags"
        :keyword="String(route.query.keyword ?? '')"
        search-placeholder="输入任意关键字搜索"
        :show-search="true"
        :show-refresh="false"
        :settings-scope-key="pageScopeKey"
        :sort-by="String(acceptedQuery.sortBy ?? props.defaultSortBy ?? 'updatedAt')"
        :sort-order="String(acceptedQuery.sortOrder ?? props.defaultSortOrder ?? 'desc')"
        :default-sort-by="props.defaultSortBy ?? 'updatedAt'"
        :default-sort-order="props.defaultSortOrder ?? 'desc'"
        :empty-description="emptyDescription"
        quick-filter-mode
        quick-filter-toggle-placement="filter-builder"
        :filter-builder-expanded="conditionFiltersExpanded"
        query-button-text="查询"
        @reset="handleReset"
        @search="handleKeywordSearch"
        @query="handleQuery"
        @filter-change="handlePrimaryFilterChange"
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
          <div class="issue-illegal-filter-stack">
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
          </div>
        </template>

        <template #toolbar-prefix>
          <div v-if="nativePrimaryFilters.length" class="issue-illegal-range-toolbar">
            <el-select
              v-for="field in nativePrimaryFilters"
              :key="field.key"
              :model-value="String(primaryFilterValues[field.key] ?? '')"
              :placeholder="field.placeholder || field.label"
              :clearable="field.clearable ?? true"
              filterable
              class="issue-illegal-range-select"
              :style="{ width: `${field.width ?? 240}px` }"
              @change="handlePrimaryFilterChange({ key: field.key, value: String($event ?? '') })"
              @clear="handlePrimaryFilterChange({ key: field.key, value: '' })"
            >
              <el-option
                v-for="option in field.options ?? []"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <template v-if="props.loadRealtimeStatus">
              <SyncMetaBadge :value="lastSyncedText" />
              <el-tag
                effect="plain"
                size="small"
                :type="syncStatus?.refreshing ? 'warning' : 'success'"
                class="issue-illegal-range-sync-tag"
              >
                {{ syncStatus?.refreshing ? '同步中' : '已是最新' }}
              </el-tag>
            </template>
          </div>
        </template>

        <template #primary-actions>
          <div class="issue-illegal-toolbar-actions customer-illegal-toolbar-actions">
            <SyncMetaBadge v-if="props.loadRealtimeStatus && !nativePrimaryFilters.length" :value="lastSyncedText" />
            <el-tag effect="plain" type="warning">{{ totalTagText(total) }}</el-tag>
            <el-button
              v-if="canRefreshLatestData"
              class="app-action-button app-action-button--refresh"
              plain
              :icon="RefreshRight"
              :loading="refreshButtonBusy"
              @click="handleRefreshLatestData"
            >
              刷新最新数据
            </el-button>
            <el-button class="app-action-button app-action-button--refresh" plain :icon="Refresh" @click="handleRefresh">刷新</el-button>
            <el-button
              class="app-action-button app-action-button--rule btn-gray"
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
            >
              规则说明
            </el-button>
            <el-button
              v-if="exportRecords"
              class="app-action-button app-action-button--export"
              :icon="Download"
              :loading="exportLoading"
              @click="handleExport"
            >
              下载查询数据
            </el-button>
            <PageSettingsButton :scope-key="pageScopeKey" />
          </div>
        </template>

        <template #row-actions="{ row }">
          <div class="issue-illegal-row-actions customer-illegal-row-actions">
            <el-button
              class="issue-illegal-row-action-button customer-illegal-row-action-button"
              :icon="View"
              size="small"
              plain
              @click="openDetailDrawer(row)"
            >
              查看详情
            </el-button>
          </div>
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
              <el-descriptions-item label="议题标题">{{ selectedRow.title || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="issueStateDetailLabel || '状态'">
                {{ normalizeIssueState(selectedRow.issueState) }}
              </el-descriptions-item>
              <el-descriptions-item label="项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
              <el-descriptions-item v-if="selectedRow.testingPhase" label="测试阶段">
                {{ selectedRow.testingPhase }}
              </el-descriptions-item>
              <el-descriptions-item label="里程碑">{{ selectedRow.milestoneTitle || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="moduleDetailLabel || '模块'">
                {{ selectedRow.moduleNames || '-' }}
              </el-descriptions-item>
              <el-descriptions-item v-if="selectedRow.functionName" label="功能名">
                {{ selectedRow.functionName }}
              </el-descriptions-item>
              <el-descriptions-item :label="authorDetailLabel || '创建人'">
                {{ selectedRow.authorName || '-' }}
              </el-descriptions-item>
              <el-descriptions-item :label="assigneeDetailLabel || '处理人'">
                {{ selectedRow.assigneeName || '-' }}
              </el-descriptions-item>
              <el-descriptions-item :label="severityDetailLabel || '严重程度'">
                {{ selectedRow.severityLevel || '-' }}
              </el-descriptions-item>
              <el-descriptions-item v-if="selectedRow.priorityLevel" label="优先级">
                {{ selectedRow.priorityLevel }}
              </el-descriptions-item>
              <el-descriptions-item :label="bugStatusDetailLabel || '测试状态'">
                <IssueStatusTags :value="selectedRow.bugStatus" />
              </el-descriptions-item>
              <el-descriptions-item label="分类">{{ selectedRow.category || '-' }}</el-descriptions-item>
              <el-descriptions-item :label="createdAtDetailLabel || '创建时间'">{{ formatDateTime(selectedRow.createdAt) }}</el-descriptions-item>
              <el-descriptions-item :label="updatedAtDetailLabel || '更新时间'">{{ formatDateTime(selectedRow.updatedAt) }}</el-descriptions-item>
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
        :summary-main="`命中记录 ${ruleFinalCount} 条。`"
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
        process-title="处理流程"
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

.issue-illegal-range-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}

.issue-illegal-range-select {
  max-width: min(100%, 360px);
}

.issue-illegal-range-sync-tag {
  height: 32px;
  border-radius: 999px;
  padding: 0 12px;
}

.issue-illegal-toolbar-actions,
.issue-illegal-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.issue-illegal-row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-width: 0;
}

.issue-illegal-row-action-button {
  flex: 0 0 auto;
  min-width: 76px;
  height: 28px;
  padding: 0 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
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
