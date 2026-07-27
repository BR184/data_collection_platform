<script setup lang="ts">
import { computed, ref, watch } from 'vue';
// 客户问题正式记录页复用共享记录页底座，只在这里定义客户问题自己的范围和列展示。
// 查询条件统一落到 issue_fact 口径，避免页面层再重复实现筛选规则。
import { ElMessage } from '../element-plus-services';
import { Download, InfoFilled, Refresh, RefreshRight } from '@element-plus/icons-vue';
import BaseRecordTable from '../components/base/BaseRecordTable.vue';
import IssueStatusTags from '../components/IssueStatusTags.vue';
import PageSettingsButton from '../components/PageSettingsButton.vue';
import RuleExplanationDrawer from '../components/RuleExplanationDrawer.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import StatisticFilterBuilder from '../components/StatisticFilterBuilder.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import type {
  CustomerIssueRecordFilterOptionsResponse,
  CustomerIssueRecordRowResponse,
  CustomerIssueRecordTopic,
  StatisticBoardRuleExplanationResponse,
  StatisticFilterField,
} from '../types/api';
import { buildCustomerIssueRecordConditionFields } from './customer-issues/customer-issue-condition-fields';
import {
  CC_PRODUCT_RECORD_COLUMNS,
  DELAY_RECORD_COLUMNS,
} from './customer-issues/customer-issue-record-columns';
import {
  formatCustomerIssueRecordDateTime as formatDateTime,
  mapCustomerIssueRecordTableRows,
  normalizeCustomerIssueState as normalizeIssueState,
  parseCustomerIssuePlannedMergeVersions,
} from './customer-issues/customer-issue-record-table-rows';
import { isCcProductQuickFilterKey } from './customer-issues/customer-issue-quick-filter-fields';
import { useRuleExplanationPanel } from '../composables/useRuleExplanationPanel';
import { CUSTOMER_ISSUE_RECORD_QUERY_KEYS } from '../feature-manifest/customer-issue-record-query-contract';
import { useRouteTableState } from '../composables/useRouteTableState';
import { useRealtimeWorkspaceStatus, waitForRealtimeWorkspaceRefresh } from '../composables/useRealtimeWorkspaceStatus';
import { useConditionFilterGroupState } from '../composables/useConditionFilterGroupState';
import { useRecordPageController } from '../composables/useRecordPageController';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { useRecordTableFilterPriority } from '../composables/useRecordTableFilterPriority';
import type { RecordTableActiveFilterTag, RecordTableFilterField } from '../types/record-table';
import { downloadBlob } from '../utils/csv-download';
import { useRoute } from 'vue-router';

const currentRoute = useRoute();
const resolveTopic = () => currentRoute.meta.pageKey === 'customer-issues-delay-issues' ? 'delay' : 'cc-product';
const pageScopeKey = computed(() => `record-page:customer-issue-records:${resolveTopic()}`);
const { readAutoRefreshOnEnter } = usePageAutoRefreshPreference(() => pageScopeKey.value);
const {
  route,
  page,
  pageSize,
  sortBy,
  sortOrder,
  patchQuery,
  bindLoader,
  reload,
  isTableLoading,
} = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: 'updatedAt',
    sortOrder: 'desc',
  },
  watchedQueryKeys: CUSTOMER_ISSUE_RECORD_QUERY_KEYS,
  immediate: false,
  minLoadingMs: 0,
  autoRefreshOnEnter: readAutoRefreshOnEnter,
});

const rows = ref<CustomerIssueRecordRowResponse[]>([]);
const total = ref(0);
const filterOptionsLoaded = ref(false);
const filterOptionsLoading = ref(false);
const filterOptionsError = ref('');
const tableLoadError = ref('');
const detailVisible = ref(false);
const selectedRow = ref<CustomerIssueRecordRowResponse | null>(null);
const exportLoading = ref(false);
const realtimeRefreshLoading = ref(false);
const milestoneDefaultPatchInFlight = ref(false);

const filterOptions = ref<CustomerIssueRecordFilterOptionsResponse>(createEmptyFilterOptions());
let filterOptionsRunId = 0;
let tableDataRunId = 0;

const topic = computed<CustomerIssueRecordTopic>(() =>
  resolveTopic(),
);
const requiresMilestoneDefault = computed(() => isDelayTopic.value);
const milestoneDefaultReady = computed(() => {
  if (!requiresMilestoneDefault.value) {
    return true;
  }
  if (String(route.query.milestoneTitle ?? '').trim()) {
    return true;
  }
  return filterOptionsLoaded.value && !filterOptions.value.milestoneTitles.some((option) => option.value);
});
const isDelayTopic = computed(() => topic.value === 'delay');
const pageTitle = computed(() => (isDelayTopic.value ? '延期问题明细' : 'CC_PRODUCT 议题明细'));
const canRefreshLatestData = computed(() => hasPermission(authState.currentUser, 'business_data.refresh'));
const emptyDescription = computed(() =>
  isDelayTopic.value ? '当前筛选条件下没有延期问题。' : '当前筛选条件下没有 CC_PRODUCT 议题。',
);

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getCustomerIssueRecordRealtimeStatus(topic.value, buildCurrentQueryParams(false)),
  emptyText: '-',
});

const {
  ruleExplanation,
  ruleExplanationLoading,
  ruleExplanationVisible,
  resetRuleExplanation,
  openRuleExplanation,
} = useRuleExplanationPanel({
  load: () => api.getCustomerIssueRecordRuleExplanation(topic.value),
  fallback: (reason) => createFallbackRuleExplanation(reason),
});

const conditionFilterFields = computed<StatisticFilterField[]>(() =>
  buildCustomerIssueRecordConditionFields(filterOptions.value, !isDelayTopic.value)
    .filter((field) =>
      field.key !== 'milestoneTitle' && (isDelayTopic.value || field.key !== 'createdAt')),
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
const conditionFiltersExpanded = ref(false);

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
  loadTableData: reload,
  resetDraft,
  buildApplyQueryPatch,
  buildResetQueryPatch,
  defaultSortBy: 'updatedAt',
  defaultSortOrder: 'desc',
  resetClearKeys: [
    'keyword',
    'issueIid',
    'title',
    'projectName',
    'moduleName',
    'functionName',
    'customerName',
    'testingPhase',
    'reasonCategory',
    'authorName',
    'handlerName',
    'assigneeName',
    'fixUser',
    'delayCause',
    'severityLevel',
    'priorityLevel',
    'issueState',
    'bugStatus',
    'category',
    'milestoneTitle',
    'createdAtStart',
    'createdAtEnd',
    'updatedAtStart',
    'updatedAtEnd',
  ],
  queryClearKeys: [
  ],
  rangeKeys: {
    updatedAtRange: { startKey: 'updatedAtStart', endKey: 'updatedAtEnd' },
    createdAtRange: { startKey: 'createdAtStart', endKey: 'createdAtEnd' },
  },
});

const columns = computed(() =>
  isDelayTopic.value ? DELAY_RECORD_COLUMNS : CC_PRODUCT_RECORD_COLUMNS,
);

const filterValues = computed<Record<string, unknown>>(() => ({
  createdAtRange: route.query.createdAtStart && route.query.createdAtEnd
    ? [String(route.query.createdAtStart), String(route.query.createdAtEnd)]
    : [],
  updatedAtRange: route.query.updatedAtStart && route.query.updatedAtEnd
    ? [String(route.query.updatedAtStart), String(route.query.updatedAtEnd)]
    : [],
  milestoneTitle: String(route.query.milestoneTitle ?? ''),
  issueIid: String(route.query.issueIid ?? ''),
  title: String(route.query.title ?? ''),
  projectName: String(route.query.projectName ?? ''),
  moduleName: String(route.query.moduleName ?? ''),
  functionName: String(route.query.functionName ?? ''),
  customerName: String(route.query.customerName ?? ''),
  testingPhase: String(route.query.testingPhase ?? ''),
  reasonCategory: String(route.query.reasonCategory ?? ''),
  authorName: String(route.query.authorName ?? ''),
  handlerName: String(route.query.handlerName ?? ''),
  assigneeName: String(route.query.assigneeName ?? ''),
  fixUser: String(route.query.fixUser ?? ''),
  delayCause: String(route.query.delayCause ?? ''),
  severityLevel: String(route.query.severityLevel ?? ''),
  priorityLevel: String(route.query.priorityLevel ?? ''),
  issueState: String(route.query.issueState ?? ''),
  bugStatus: String(route.query.bugStatus ?? ''),
  category: String(route.query.category ?? ''),
}));

const primaryFilters = computed<RecordTableFilterField[]>(() => [
  {
    key: 'milestoneTitle',
    label: '里程碑',
    type: 'select',
    defaultStrategy: isDelayTopic.value ? 'first-available' : 'empty',
    clearable: !isDelayTopic.value,
    placeholder: isDelayTopic.value ? '切换里程碑' : '全部里程碑',
    width: 180,
    options: isDelayTopic.value
      ? filterOptions.value.milestoneTitles
      : [{ label: '全部里程碑', value: '' }, ...filterOptions.value.milestoneTitles],
  },
  {
    key: 'issueIid',
    label: '议题编号',
    type: 'input',
    placeholder: '输入议题编号',
    width: 156,
  },
  { key: 'title', label: '议题标题', type: 'input', placeholder: '输入标题关键字' },
  ...(!isDelayTopic.value
    ? [{
      key: 'customerName',
      label: '客户',
      type: 'select' as const,
      width: 180,
      options: [{ label: '全部客户', value: '' }, ...filterOptions.value.customerNames],
    }]
    : []),
  {
    key: 'projectName',
    label: '项目',
    type: 'select',
    options: [{ label: '全部项目', value: '' }, ...filterOptions.value.projectNames],
  },
  {
    key: 'moduleName',
    label: '模块名',
    type: 'select',
    width: 180,
    options: [{ label: '全部模块', value: '' }, ...filterOptions.value.moduleNames],
  },
  {
    key: 'functionName',
    label: isDelayTopic.value ? '功能名' : '功能名称',
    type: 'select',
    width: 180,
    options: [{ label: '全部功能', value: '' }, ...filterOptions.value.functionNames],
  },
  {
    key: 'reasonCategory',
    label: '缺陷原因',
    type: 'select',
    options: [{ label: '全部缺陷原因', value: '' }, ...filterOptions.value.reasonCategories],
  },
  {
    key: 'authorName',
    label: '议题提交人',
    type: 'select',
    options: [{ label: '全部提交人', value: '' }, ...filterOptions.value.authorNames],
  },
  ...(!isDelayTopic.value
    ? [{
      key: 'handlerName',
      label: '议题处理人',
      type: 'select' as const,
      options: [{ label: '全部处理人', value: '' }, ...filterOptions.value.handlerNames],
    }]
    : []),
  {
    key: 'assigneeName',
    label: isDelayTopic.value ? '议题处理人' : '议题指派人',
    type: 'select',
    options: [
      { label: isDelayTopic.value ? '全部处理人' : '全部指派人', value: '' },
      ...filterOptions.value.assigneeNames,
    ],
  },
  {
    key: 'severityLevel',
    label: '严重程度',
    type: 'select',
    options: [{ label: '全部严重程度', value: '' }, ...filterOptions.value.severityLevels],
  },
  {
    key: 'priorityLevel',
    label: '缺陷优先级',
    type: 'select',
    options: [{ label: '全部优先级', value: '' }, ...filterOptions.value.priorityLevels],
  },
  {
    key: 'issueState',
    label: '议题状态',
    type: 'select',
    options: [{ label: '全部状态', value: '' }, ...filterOptions.value.issueStates],
  },
  {
    key: 'bugStatus',
    label: '测试状态',
    type: 'select',
    options: [{ label: '全部测试状态', value: '' }, ...filterOptions.value.bugStatuses],
  },
  ...(!isDelayTopic.value
    ? [{
      key: 'testingPhase',
      label: '测试阶段',
      type: 'select' as const,
      width: 200,
      options: [{ label: '全部测试阶段', value: '' }, ...filterOptions.value.testingPhases],
    }]
    : []),
  {
    key: 'category',
    label: '议题类别',
    type: 'select',
    options: [{ label: '全部类别', value: '' }, ...filterOptions.value.categories],
  },
  ...(!isDelayTopic.value
    ? [
      {
        key: 'delayCause',
        label: '延期原因',
        type: 'select' as const,
        width: 180,
        options: [{ label: '全部延期原因', value: '' }, ...filterOptions.value.delayCauses],
      },
      {
        key: 'fixUser',
        label: '缺陷修复人',
        type: 'select' as const,
        width: 180,
        options: [{ label: '全部修复人', value: '' }, ...filterOptions.value.fixUsers],
      },
    ]
    : []),
  {
    key: 'createdAtRange',
    label: '提交时间',
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
].filter((filter) => isDelayTopic.value || isCcProductQuickFilterKey(filter.key)));

const priorityQuickFilters = computed<RecordTableFilterField[]>(() => [
  { key: 'keyword', label: '任意关键字', type: 'input', placeholder: '输入任意关键字搜索', width: 260 },
  ...primaryFilters.value,
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
  quickValues: filterValues,
  filterDraft,
  rangeKeys: {
    updatedAtRange: { startKey: 'updatedAtStart', endKey: 'updatedAtEnd' },
    createdAtRange: { startKey: 'createdAtStart', endKey: 'createdAtEnd' },
  },
});

const primaryActiveFilterTags = computed<RecordTableActiveFilterTag[]>(() => {
  const tags: RecordTableActiveFilterTag[] = [];
  const values = filterValues.value;
  if (values.milestoneTitle) {
    tags.push({ key: 'milestoneTitle', label: '里程碑', value: String(values.milestoneTitle) });
  }
  if (values.issueIid) {
    tags.push({ key: 'issueIid', label: '议题编号', value: String(values.issueIid) });
  }
  if (values.title) {
    tags.push({ key: 'title', label: '议题标题', value: String(values.title) });
  }
  if (values.projectName) {
    tags.push({ key: 'projectName', label: '项目', value: String(values.projectName) });
  }
  if (values.moduleName) {
    tags.push({ key: 'moduleName', label: '模块名', value: String(values.moduleName) });
  }
  if (values.functionName) {
    tags.push({
      key: 'functionName',
      label: isDelayTopic.value ? '功能名' : '功能名称',
      value: String(values.functionName),
    });
  }
  if (!isDelayTopic.value && values.testingPhase) {
    tags.push({ key: 'testingPhase', label: '测试阶段', value: String(values.testingPhase) });
  }
  if (values.reasonCategory) {
    tags.push({ key: 'reasonCategory', label: '缺陷原因', value: String(values.reasonCategory) });
  }
  if (values.authorName) {
    tags.push({ key: 'authorName', label: '议题提交人', value: String(values.authorName) });
  }
  if (!isDelayTopic.value && values.handlerName) {
    tags.push({ key: 'handlerName', label: '议题处理人', value: String(values.handlerName) });
  }
  if (values.assigneeName) {
    tags.push({
      key: 'assigneeName',
      label: isDelayTopic.value ? '议题处理人' : '议题指派人',
      value: String(values.assigneeName),
    });
  }
  if (!isDelayTopic.value && values.delayCause) {
    tags.push({ key: 'delayCause', label: '延期原因', value: String(values.delayCause) });
  }
  if (!isDelayTopic.value && values.fixUser) {
    tags.push({ key: 'fixUser', label: '缺陷修复人', value: String(values.fixUser) });
  }
  if (values.severityLevel) {
    tags.push({ key: 'severityLevel', label: '严重程度', value: String(values.severityLevel) });
  }
  if (values.priorityLevel) {
    tags.push({ key: 'priorityLevel', label: '缺陷优先级', value: String(values.priorityLevel) });
  }
  if (values.issueState) {
    tags.push({ key: 'issueState', label: '议题状态', value: String(values.issueState) });
  }
  if (values.bugStatus) {
    tags.push({ key: 'bugStatus', label: '测试状态', value: String(values.bugStatus) });
  }
  if (values.category) {
    tags.push({ key: 'category', label: '议题类别', value: String(values.category) });
  }
  if (isDelayTopic.value && Array.isArray(values.createdAtRange) && values.createdAtRange.length === 2) {
    tags.push({
      key: 'createdAtRange',
      label: '提交时间',
      value: `${values.createdAtRange[0]} ~ ${values.createdAtRange[1]}`,
    });
  }
  if (Array.isArray(values.updatedAtRange) && values.updatedAtRange.length === 2) {
    tags.push({
      key: 'updatedAtRange',
      label: '更新时间',
      value: `${values.updatedAtRange[0]} ~ ${values.updatedAtRange[1]}`,
    });
  }
  return tags;
});

const allActiveFilterTags = computed<RecordTableActiveFilterTag[]>(() => [
  ...conditionActiveFilterTags.value,
  ...primaryActiveFilterTags.value,
]);

const tableRows = computed<Record<string, unknown>[]>(() => mapCustomerIssueRecordTableRows(rows.value));
const selectedPlanMergeVersionBranches = computed(() =>
  parseCustomerIssuePlannedMergeVersions(selectedRow.value?.plannedMergeVersionBranch),
);

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

function buildDelayFlags(row: CustomerIssueRecordRowResponse) {
  const flags = [];
  if (row.delayIssue) flags.push({ label: '申请延期', type: 'warning' as const });
  if (row.responseDelayed) flags.push({ label: '响应延期', type: 'danger' as const });
  if (row.resolveDelayed) flags.push({ label: '解决延期', type: 'danger' as const });
  return flags;
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

function createEmptyFilterOptions(): CustomerIssueRecordFilterOptionsResponse {
  return {
    projectNames: [],
    moduleNames: [],
    functionNames: [],
    customerNames: [],
    reasonCategories: [],
    severityLevels: [],
    priorityLevels: [],
    issueStates: [],
    bugStatuses: [],
    categories: [],
    authorNames: [],
    handlerNames: [],
    assigneeNames: [],
    testingPhases: [],
    fixUsers: [],
    delayCauses: [],
    milestoneTitles: [],
  };
}

async function loadTableData() {
  const runId = ++tableDataRunId;
  const requestedTopic = topic.value;
  tableLoadError.value = '';
  initializeFromQuery(route.query);
  try {
    const response = await api.getCustomerIssueRecords(buildCurrentQueryParams(true));
    if (runId !== tableDataRunId || requestedTopic !== topic.value) {
      return;
    }
    rows.value = response.records;
    total.value = response.total;
  } catch (error) {
    if (runId !== tableDataRunId || requestedTopic !== topic.value) {
      return;
    }
    rows.value = [];
    total.value = 0;
    tableLoadError.value = errorMessage(error, `${pageTitle.value}加载失败`);
  }
}

async function loadFilterOptions() {
  const runId = ++filterOptionsRunId;
  const requestedTopic = topic.value;
  filterOptionsLoading.value = true;
  filterOptionsError.value = '';
  try {
    const response = await api.getCustomerIssueRecordFilterOptions(requestedTopic);
    if (runId !== filterOptionsRunId || requestedTopic !== topic.value) {
      return false;
    }
    filterOptions.value = response;
    filterOptionsLoaded.value = true;
    return true;
  } catch (error) {
    if (runId !== filterOptionsRunId || requestedTopic !== topic.value) {
      return false;
    }
    filterOptionsLoaded.value = false;
    filterOptionsError.value = errorMessage(error, `${pageTitle.value}筛选项加载失败`);
    return false;
  } finally {
    if (runId === filterOptionsRunId && requestedTopic === topic.value) {
      filterOptionsLoading.value = false;
    }
  }
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message.trim() ? error.message : fallback;
}

function buildCurrentQueryParams(includePagination: boolean) {
  return {
    topic: topic.value,
    keyword: String(route.query.keyword ?? ''),
    issueIid: String(route.query.issueIid ?? ''),
    title: String(route.query.title ?? ''),
    projectName: String(route.query.projectName ?? ''),
    moduleName: String(route.query.moduleName ?? ''),
    functionName: String(route.query.functionName ?? ''),
    ...(!isDelayTopic.value ? { customerName: String(route.query.customerName ?? '') } : {}),
    reasonCategory: String(route.query.reasonCategory ?? ''),
    authorName: String(route.query.authorName ?? ''),
    ...(!isDelayTopic.value ? { handlerName: String(route.query.handlerName ?? '') } : {}),
    assigneeName: String(route.query.assigneeName ?? ''),
    ...(!isDelayTopic.value
      ? {
        testingPhase: String(route.query.testingPhase ?? ''),
        fixUser: String(route.query.fixUser ?? ''),
        delayCause: String(route.query.delayCause ?? ''),
      }
      : {}),
    severityLevel: String(route.query.severityLevel ?? ''),
    priorityLevel: String(route.query.priorityLevel ?? ''),
    issueState: String(route.query.issueState ?? ''),
    bugStatus: String(route.query.bugStatus ?? ''),
    category: String(route.query.category ?? ''),
    milestoneTitle: String(route.query.milestoneTitle ?? ''),
    ...(isDelayTopic.value
      ? {
        createdAtStart: String(route.query.createdAtStart ?? ''),
        createdAtEnd: String(route.query.createdAtEnd ?? ''),
      }
      : {}),
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
    const workbook = await api.exportCustomerIssueRecords(buildCurrentQueryParams(false));
    downloadBlob(workbook, customerIssueExportFilename());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出失败');
  } finally {
    exportLoading.value = false;
  }
}

function customerIssueExportFilename() {
  return isDelayTopic.value ? '延期问题明细.xlsx' : '（全量）CCProduct议题查询结果.xlsx';
}

async function handleRefreshLatestData() {
  realtimeRefreshLoading.value = true;
  try {
    const status = await api.refreshCustomerIssueRecordRealtime(topic.value);
    ElMessage.success(status.message || '已开始刷新最新数据');
    await waitForRealtimeWorkspaceRefresh(status, loadSyncStatus);
    await Promise.all([loadFilterOptions(), reload()]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新最新数据失败');
  } finally {
    realtimeRefreshLoading.value = false;
  }
}

bindLoader(async () => {
  void loadSyncStatus();
  if (milestoneDefaultPatchInFlight.value || !milestoneDefaultReady.value) {
    return;
  }
  await loadTableData();
});

watch(
  [topic],
  async () => {
    filterOptionsRunId += 1;
    tableDataRunId += 1;
    filterOptions.value = createEmptyFilterOptions();
    filterOptionsLoaded.value = false;
    filterOptionsLoading.value = false;
    filterOptionsError.value = '';
    tableLoadError.value = '';
    rows.value = [];
    total.value = 0;
    resetRuleExplanation();
    const hasExplicitMilestone = Boolean(String(route.query.milestoneTitle ?? '').trim());
    if (!isDelayTopic.value || hasExplicitMilestone) {
      void loadFilterOptions();
      await reload();
      return;
    }
    await loadFilterOptions();
  },
  { immediate: true },
);

watch(
  [() => route.query.milestoneTitle, filterOptionsLoaded],
  async () => {
    if (
      !isDelayTopic.value
      || !filterOptionsLoaded.value
      || milestoneDefaultPatchInFlight.value
      || String(route.query.milestoneTitle ?? '').trim()
    ) {
      return;
    }
    const patchedDefault = await applyMilestoneDefault();
    if (patchedDefault || milestoneDefaultReady.value) {
      await reload();
    }
  },
);

async function applyMilestoneDefault() {
  if (!filterOptionsLoaded.value || milestoneDefaultPatchInFlight.value) {
    return false;
  }
  if (!requiresMilestoneDefault.value) {
    return false;
  }
  if (String(route.query.milestoneTitle ?? '').trim()) {
    return false;
  }
  const fallback = filterOptions.value.milestoneTitles.find((option) => option.value)?.value ?? '';
  if (!fallback) {
    return false;
  }
  milestoneDefaultPatchInFlight.value = true;
  try {
    await patchQuery({ page: 1, milestoneTitle: fallback });
  } finally {
    milestoneDefaultPatchInFlight.value = false;
  }
  return true;
}

function openDetailDrawer(row: Record<string, unknown>) {
  selectedRow.value = (row.__raw as CustomerIssueRecordRowResponse) ?? null;
  detailVisible.value = true;
}

async function handleClearFilter(key: string) {
  if (key === 'filterGroup') {
    resetPriorityState();
  }
  await handleBaseClearFilter(key);
}

async function handleFilterChange(payload: { key: string; value: string | string[] | null }) {
  if (payload.key === 'createdAtRange') {
    const [start, end] = Array.isArray(payload.value) ? payload.value : [];
    await patchQuery({ page: 1, createdAtStart: start || null, createdAtEnd: end || null });
    return;
  }
  if (payload.key === 'updatedAtRange') {
    const [start, end] = Array.isArray(payload.value) ? payload.value : [];
    await patchQuery({ page: 1, updatedAtStart: start || null, updatedAtEnd: end || null });
    return;
  }
  await patchQuery({
    page: 1,
    [payload.key]: Array.isArray(payload.value) ? payload.value.join(',') || null : payload.value || null,
  });
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
  <section class="customer-record-page">
    <div v-if="filterOptionsError || tableLoadError" class="customer-record-load-alerts">
      <el-alert v-if="filterOptionsError" type="warning" :closable="false" show-icon>
        <template #title>
          <div class="customer-record-load-alert-title">
            <span>{{ filterOptionsError }}</span>
            <el-button link type="primary" :loading="filterOptionsLoading" @click="loadFilterOptions">
              重试筛选项
            </el-button>
          </div>
        </template>
      </el-alert>
      <el-alert v-if="tableLoadError" type="error" :closable="false" show-icon>
        <template #title>
          <div class="customer-record-load-alert-title">
            <span>{{ tableLoadError }}</span>
            <el-button link type="primary" :loading="isTableLoading" @click="reload">
              重试列表
            </el-button>
          </div>
        </template>
      </el-alert>
    </div>
    <BaseRecordTable
        :columns="columns"
        :rows="tableRows"
        :loading="isTableLoading"
        :page="page"
        :page-size="pageSize"
        :total="total"
        :active-filter-tags="allActiveFilterTags"
        :primary-filters="primaryFilters"
        :filter-values="filterValues"
        :hidden-filter-keys="hiddenFilterKeys"
        :disabled-filter-keys="disabledFilterKeys"
        :highlighted-filter-keys="highlightedFilterKeys"
        :quick-filter-change-guard="guardQuickFilterChange"
        :keyword="String(route.query.keyword ?? '')"
        :keyword-auto-search="true"
        search-placeholder="输入任意关键字搜索"
        :show-search="true"
        :show-refresh="false"
        :settings-scope-key="pageScopeKey"
        :sort-by="sortBy"
        :sort-order="sortOrder"
        default-sort-by="updatedAt"
        default-sort-order="desc"
        :empty-description="emptyDescription"
        quick-filter-mode
        quick-filter-toggle-placement="filter-builder"
        :filter-builder-expanded="conditionFiltersExpanded"
        query-button-text="查询"
        @search="handleKeywordSearch"
        @filter-change="handleFilterChange"
        @reset="handleReset"
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
          <div class="customer-record-filter-stack">
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

        <template #primary-actions>
          <div class="customer-record-toolbar-actions">
            <SyncMetaBadge :value="lastSyncedText" />
            <el-tag effect="plain" :type="isDelayTopic ? 'warning' : 'primary'">当前 {{ total }} 条</el-tag>
            <el-button
              v-if="canRefreshLatestData"
              class="app-action-button app-action-button--refresh"
              plain
              :icon="RefreshRight"
              :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
              @click="handleRefreshLatestData"
            >
              刷新最新数据
            </el-button>
            <el-button class="app-action-button app-action-button--refresh" plain :icon="Refresh" @click="handleRefresh">刷新</el-button>
            <el-button
              class="app-action-button app-action-button--rule"
              plain
              :icon="InfoFilled"
              :loading="ruleExplanationLoading"
              @click="openRuleExplanation"
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
              {{ isDelayTopic ? '导出' : '下载查询数据' }}
            </el-button>
            <PageSettingsButton :scope-key="pageScopeKey" />
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
            <el-descriptions-item label="议题状态">{{ normalizeIssueState(selectedRow.issueState, selectedRow.closedAt) }}</el-descriptions-item>
            <el-descriptions-item label="项目">{{ selectedRow.projectName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="里程碑">{{ selectedRow.milestoneTitle || '-' }}</el-descriptions-item>
            <el-descriptions-item label="模块名">{{ selectedRow.moduleNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="功能名称">{{ selectedRow.functionName || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="!isDelayTopic" label="客户">{{ selectedRow.customerNames || '-' }}</el-descriptions-item>
            <el-descriptions-item label="测试阶段">{{ selectedRow.testingPhase || '未设定测试阶段' }}</el-descriptions-item>
            <el-descriptions-item label="缺陷原因">{{ selectedRow.reasonCategory || '未归因' }}</el-descriptions-item>
            <el-descriptions-item label="严重程度">{{ selectedRow.severityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="缺陷优先级">{{ selectedRow.priorityLevel || '-' }}</el-descriptions-item>
            <el-descriptions-item label="测试状态">
              <IssueStatusTags :value="selectedRow.bugStatus" />
            </el-descriptions-item>
            <el-descriptions-item label="议题类别">{{ selectedRow.category || '-' }}</el-descriptions-item>
            <el-descriptions-item label="议题提交人">{{ selectedRow.authorName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="议题处理人">{{ selectedRow.handlerName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="议题指派人">{{ selectedRow.assigneeName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="延期原因">{{ selectedRow.delayCause || '-' }}</el-descriptions-item>
            <el-descriptions-item label="缺陷修复人">{{ selectedRow.fixUser || '-' }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ formatDateTime(selectedRow.createdAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="!isDelayTopic" label="缺陷滞留时长（小时）">
              {{ selectedRow.retentionHours == null ? '-' : selectedRow.retentionHours }}
            </el-descriptions-item>
            <el-descriptions-item v-if="!isDelayTopic" label="计划解决时间">
              {{ selectedRow.plannedResolutionText || formatDateTime(selectedRow.plannedResolutionAt) }}
            </el-descriptions-item>
            <el-descriptions-item v-if="!isDelayTopic" label="计划合并版本分支">
              <div class="customer-record-tags">
                <el-tag
                  v-for="branch in selectedPlanMergeVersionBranches"
                  :key="branch"
                  effect="plain"
                  size="small"
                >
                  {{ branch }}
                </el-tag>
                <span v-if="!selectedPlanMergeVersionBranches.length">-</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(selectedRow.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item label="关闭时间">{{ formatDateTime(selectedRow.closedAt) }}</el-descriptions-item>
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

    <RuleExplanationDrawer
      v-model="ruleExplanationVisible"
      :loading="ruleExplanationLoading"
      :title="ruleExplanation?.title || `${pageTitle}规则说明`"
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
</template>

<style scoped>
.customer-record-page {
  display: grid;
  gap: 12px;
}

.customer-record-load-alerts {
  display: grid;
  gap: 8px;
}

.customer-record-load-alert-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.customer-record-load-alert-title > span {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 720px) {
  .customer-record-load-alert-title {
    align-items: flex-start;
    flex-wrap: wrap;
  }
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

</style>
