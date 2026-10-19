<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { biDashboardApi, type BiCustomerIssueQuery } from '../../api-client/bi-dashboard-api';
import { getErrorMessage } from '../../utils/user-message';
import BiChartPanel from './components/BiChartPanel.vue';
import BiMetricStrip from './components/BiMetricStrip.vue';
import { DailyDefectTrendChart, DelayHeatmapChart, DeveloperWorkloadChart, DistributionDonutChart, StackedCategoryBarChart, VerticalCategoryBarChart } from './charts/types';
import type { CategorySeriesData, DailyDefectTrendData, DelayHeatmapData, DeveloperWorkloadRow, NamedValue } from './charts/chart-data';
import { formatNumber, sectionPresentation } from './data/presentation';
import { buildDelayHeatmapData } from './data/system-test-presentation';
import { sortDeveloperWorkloadRows, sortNamedValues, type BiSortOption, type BiSortOrder } from './data/sorting';
import type { BiChartDownloadContext, BiCustomerIssueMetric, BiCustomerIssuePageData, BiDataStatus, BiMemberSelection, BiPageResponse } from './data/types';

const route = useRoute();
const router = useRouter();
const response = shallowRef<BiPageResponse<BiCustomerIssuePageData> | null>(null);
const acceptedSignature = ref<string | null>(null);
const loading = ref(false);
const loadError = ref('');
let requestSequence = 0;

const dimensions = ['customer', 'module', 'function'] as const;
type Dimension = typeof dimensions[number];
const labels: Record<Dimension, string> = { customer: '客户', module: '模块', function: '功能' };
const queryKeys = [
  'milestoneBusinessKey', 'customerKind', 'customer', 'moduleKind', 'module', 'functionKind', 'function',
] as const;

const routeSignature = computed(() => JSON.stringify(queryKeys.map((key) => route.query[key])));
const parsedQuery = computed(() => parseRouteQuery(route.query));
const responseForRoute = computed(() => acceptedSignature.value === routeSignature.value ? response.value : null);
const currentData = computed(() => responseForRoute.value?.data ?? null);
const pageFailure = computed(() => {
  const current = responseForRoute.value;
  if (current?.status !== 'ERROR') return '';
  return current.sections.find((section) => section.key === 'page')?.message || '客户问题 BI 加载失败，请稍后重试';
});
const candidatesReady = computed(() => Boolean(currentData.value) && !loading.value && !parsedQuery.value.error);
const milestoneSelection = computed(() => currentData.value?.selectedMilestoneBusinessKey
  ?? parsedQuery.value.query?.milestoneBusinessKey
  ?? '');

const defectMetrics = computed(() => currentData.value?.defectMetrics ?? []);
const requirementMetrics = computed(() => currentData.value?.requirementMetrics ?? []);
const requirementMetricItems = computed(() => requirementMetrics.value.map(metricItem));

/**
 * 缺陷指标按稳定 key 归组展示。
 *
 * 分组只依据 key，不依据数组位置；后端增删或重排指标都不会让卡片跨组错位。
 * key 集合与后端 BiCustomerIssueCalculator 的十七项契约由 CustomerIssueDashboardView.test.ts 锁定。
 */
const DEFECT_METRIC_GROUPS: Array<{ key: string; label: string; metricKeys: string[] }> = [
  {
    key: 'defect-overview',
    label: '缺陷概览',
    metricKeys: [
      'defect_total', 'defect_fixed', 'defect_unfixed', 'defect_applied_delay',
      'defect_data_exception', 'defect_not_reproduced',
    ],
  },
  {
    key: 'defect-fix-rates',
    label: '修复率（普通与校准后并列）',
    metricKeys: [
      'defect_fix_rate', 'defect_calibrated_fix_rate', 'level1_fix_rate',
      'p1_fix_rate', 'p1_calibrated_fix_rate', 'p2_fix_rate', 'p2_calibrated_fix_rate',
    ],
  },
  {
    key: 'defect-activity',
    label: '今日与本周活动',
    metricKeys: ['today_new', 'today_resolved', 'week_new', 'week_resolved'],
  },
];

const defectMetricGroups = computed(() => DEFECT_METRIC_GROUPS.map((group) => ({
  key: group.key,
  label: group.label,
  items: group.metricKeys
    .map((key) => defectMetrics.value.find((metric) => metric.key === key))
    .filter((metric): metric is BiCustomerIssueMetric => metric !== undefined)
    .map(metricItem),
})).filter((group) => group.items.length > 0));

// 客户页高基数分类图统一横向条形：分类沿纵轴、最大值在上，视图纵向浏览；导出仍为完整数据。
const moduleDefectChart = new StackedCategoryBarChart({
  yAxisName: '缺陷数 (个)',
  orientation: 'horizontal-bars',
  visibleCategories: 10,
});
const distributionChart = new DistributionDonutChart();
const moduleSeverityChart = new StackedCategoryBarChart({
  yAxisName: '缺陷数 (个)',
  orientation: 'horizontal-bars',
  visibleCategories: 8,
});
const causeChart = new VerticalCategoryBarChart({
  nameHeader: '缺陷原因',
  valueHeader: '缺陷数 (个)',
});
const delayChart = new DelayHeatmapChart();
const workloadChart = new DeveloperWorkloadChart({
  orientation: 'horizontal-bars',
  visibleCategories: 10,
});
const demandChart = new StackedCategoryBarChart({
  yAxisName: '需求数 (个)',
  orientation: 'horizontal-bars',
  visibleCategories: 10,
});
const dailyChart = new DailyDefectTrendChart();

// 各图表排序状态
const moduleDefectSort = ref('total');
const moduleDefectSortOrder = ref<BiSortOrder>('desc');
const moduleDefectSortOptions: BiSortOption[] = [
  { label: '缺陷总数', value: 'total' },
  { label: '未修复数', value: 'unfixed' },
  { label: '已修复数', value: 'fixed' },
  { label: '模块名称', value: 'name' },
];

const severitySort = ref('count');
const severitySortOrder = ref<BiSortOrder>('desc');
const severitySortOptions: BiSortOption[] = [
  { label: '数量排序', value: 'count' },
  { label: '名称排序', value: 'name' },
];

const moduleSeveritySort = ref('total');
const moduleSeveritySortOrder = ref<BiSortOrder>('desc');
const moduleSeveritySortOptions: BiSortOption[] = [
  { label: '缺陷总数', value: 'total' },
  { label: '模块名称', value: 'name' },
];

const causeSort = ref('count');
const causeSortOrder = ref<BiSortOrder>('desc');
const causeSortOptions: BiSortOption[] = [
  { label: '数量排序', value: 'count' },
  { label: '名称排序', value: 'name' },
];

const assigneeSort = ref('open');
const assigneeSortOrder = ref<BiSortOrder>('desc');
const assigneeSortOptions: BiSortOption[] = [
  { label: '待修复数', value: 'open' },
  { label: '缺陷总数', value: 'total' },
  { label: '已修复数', value: 'fixed' },
  { label: '修复率', value: 'rate' },
  { label: '人员姓名', value: 'name' },
];

const moduleDemandSort = ref('total');
const moduleDemandSortOrder = ref<BiSortOrder>('desc');
const moduleDemandSortOptions: BiSortOption[] = [
  { label: '需求总数', value: 'total' },
  { label: '未解决数', value: 'unresolved' },
  { label: '已解决数', value: 'resolved' },
  { label: '模块名称', value: 'name' },
];

const moduleDefects = computed<CategorySeriesData>(() => {
  const rows = (currentData.value?.moduleDefects ?? []).slice();
  rows.sort((a, b) => {
    if (moduleDefectSort.value === 'name') {
      const cmp = a.module.localeCompare(b.module, 'zh-CN');
      return moduleDefectSortOrder.value === 'asc' ? cmp : -cmp;
    }
    if (moduleDefectSort.value === 'unfixed') {
      const cmp = a.unfixedCount - b.unfixedCount;
      return moduleDefectSortOrder.value === 'asc' ? cmp : -cmp;
    }
    if (moduleDefectSort.value === 'fixed') {
      const cmp = a.fixedCount - b.fixedCount;
      return moduleDefectSortOrder.value === 'asc' ? cmp : -cmp;
    }
    const cmp = a.totalCount - b.totalCount;
    return moduleDefectSortOrder.value === 'asc' ? cmp : -cmp;
  });
  return {
    categories: rows.map((row) => row.module),
    series: [
      { name: '已修复', values: rows.map((row) => row.fixedCount) },
      { name: '未修复', values: rows.map((row) => row.unfixedCount) },
    ],
  };
});
const severityDistribution = computed<NamedValue[]>(() => {
  const rows = (currentData.value?.severityDistribution ?? []).map((row) => ({ name: row.severity, value: row.count }));
  return sortNamedValues(rows, severitySort.value, severitySortOrder.value);
});
const moduleSeverity = computed<CategorySeriesData>(() => {
  const rows = currentData.value?.moduleSeverity ?? [];
  const moduleTotals = new Map<string, number>();
  for (const row of rows) {
    moduleTotals.set(row.module, (moduleTotals.get(row.module) ?? 0) + row.count);
  }
  const categories = [...new Set(rows.map((row) => row.module))];
  categories.sort((a, b) => {
    if (moduleSeveritySort.value === 'name') {
      const cmp = a.localeCompare(b, 'zh-CN');
      return moduleSeveritySortOrder.value === 'asc' ? cmp : -cmp;
    }
    const cmp = (moduleTotals.get(a) ?? 0) - (moduleTotals.get(b) ?? 0);
    return moduleSeveritySortOrder.value === 'asc' ? cmp : -cmp;
  });
  const severities = [...new Set(rows.map((row) => row.severity))];
  return {
    categories,
    series: severities.map((severity) => ({
      name: severity,
      values: categories.map((module) => rows.find((row) => row.module === module && row.severity === severity)?.count ?? 0),
    })),
  };
});
const causeDistribution = computed<NamedValue[]>(() => {
  const data = currentData.value;
  const values: NamedValue[] = (data?.causeDistribution ?? []).map((row) => ({
    name: `${row.groupName} · ${row.causeName}`,
    value: row.count,
    category: row.groupName,
  }));
  if (data && data.unclassifiedCauseCount > 0) {
    values.push({ name: '未归类', value: data.unclassifiedCauseCount, category: '未归类' });
  }
  return sortNamedValues(values, causeSort.value, causeSortOrder.value);
});
const delayAnalysis = computed<DelayHeatmapData>(() => buildDelayHeatmapData(currentData.value?.delayAnalysis ?? []));
const assigneeWorkload = computed<DeveloperWorkloadRow[]>(() => {
  const rows = (currentData.value?.assigneeWorkload ?? []).map((row) => ({
    name: row.assignee,
    total: row.totalCount,
    open: row.unfixedCount,
    fixed: row.fixedCount,
  }));
  return sortDeveloperWorkloadRows(rows, assigneeSort.value, assigneeSortOrder.value);
});
const moduleDemand = computed<CategorySeriesData>(() => {
  const rows = (currentData.value?.moduleDemand ?? []).slice();
  rows.sort((a, b) => {
    if (moduleDemandSort.value === 'name') {
      const cmp = a.module.localeCompare(b.module, 'zh-CN');
      return moduleDemandSortOrder.value === 'asc' ? cmp : -cmp;
    }
    if (moduleDemandSort.value === 'unresolved') {
      const cmp = a.unresolvedCount - b.unresolvedCount;
      return moduleDemandSortOrder.value === 'asc' ? cmp : -cmp;
    }
    if (moduleDemandSort.value === 'resolved') {
      const cmp = a.resolvedCount - b.resolvedCount;
      return moduleDemandSortOrder.value === 'asc' ? cmp : -cmp;
    }
    const cmp = a.totalCount - b.totalCount;
    return moduleDemandSortOrder.value === 'asc' ? cmp : -cmp;
  });
  return {
    categories: rows.map((row) => row.module),
    series: [
      { name: '需求已解决', values: rows.map((row) => row.resolvedCount) },
      { name: '需求未解决', values: rows.map((row) => row.unresolvedCount) },
    ],
  };
});
const dailyTrend = computed<DailyDefectTrendData>(() => {
  const rows = currentData.value?.dailyTrend ?? [];
  return {
    dates: rows.map((row) => row.date),
    cumulativeCreatedCounts: rows.map((row) => row.cumulativeCreatedCount),
    cumulativeFixedCounts: rows.map((row) => row.cumulativeFixedCount),
  };
});

function loadCurrentRoute(): void {
  const sequence = ++requestSequence;
  const signature = routeSignature.value;
  acceptedSignature.value = null;
  loadError.value = '';
  const parsed = parsedQuery.value;
  if (parsed.error || !parsed.query) {
    loading.value = false;
    response.value = null;
    return;
  }
  loading.value = true;
  void biDashboardApi.loadCustomerIssues(parsed.query)
    .then((nextResponse) => {
      if (sequence !== requestSequence) return;
      response.value = nextResponse;
      acceptedSignature.value = signature;
    })
    .catch((error: unknown) => {
      if (sequence !== requestSequence) return;
      loadError.value = getErrorMessage(error, '客户问题 BI 加载失败');
    })
    .finally(() => {
      if (sequence === requestSequence) loading.value = false;
    });
}

watch(routeSignature, loadCurrentRoute, { immediate: true });
onBeforeUnmount(() => {
  requestSequence++;
  loading.value = false;
});

function optionsFor(dimension: Dimension): Array<{ token: string; label: string }> {
  const currentSelection = parsedQuery.value.query?.[dimension] ?? { kind: 'ALL' as const };
  const allLabel = `全部${labels[dimension]}`;
  if (candidatesReady.value && currentData.value) {
    return [
      { token: 'ALL', label: allLabel },
      ...currentData.value[`${dimension}s` as 'customers' | 'modules' | 'functions'].map((option) => ({
        token: memberToken(option.kind, option.value),
        label: option.displayName,
      })),
    ];
  }
  // Loading, failed, invalid, or mismatched routes retain only their selected label for display.
  // Old-range candidates are never left in the selectable option list.
  return [
    { token: 'ALL', label: allLabel },
    ...(currentSelection.kind === 'ALL' ? [] : [{
      token: memberToken(currentSelection.kind, currentSelection.kind === 'VALUE' ? currentSelection.value : null),
      label: memberLabel(dimension, currentSelection),
    }]),
  ];
}

function selectedToken(dimension: Dimension): string {
  const selection = parsedQuery.value.query?.[dimension] ?? { kind: 'ALL' as const };
  return memberToken(selection.kind, selection.kind === 'VALUE' ? selection.value : null);
}

function selectMember(dimension: Dimension, token: string): void {
  if (!candidatesReady.value || !currentData.value) return;
  const option = optionsFor(dimension).find((candidate) => candidate.token === token);
  if (!option) return;
  const selection = token === 'ALL' ? { kind: 'ALL' as const } : token === 'MISSING'
    ? { kind: 'MISSING' as const }
    : parseValueToken(token);
  if (!selection) return;
  void updateSelectionQuery(dimension, selection);
}

async function selectMilestone(value: string): Promise<void> {
  await router.push({ path: route.path, query: { ...route.query, milestoneBusinessKey: value } });
}

async function updateSelectionQuery(dimension: Dimension, selection: BiMemberSelection): Promise<void> {
  const query = { ...route.query };
  const kindKey = `${dimension}Kind`;
  if (selection.kind === 'ALL') {
    delete query[kindKey];
    delete query[dimension];
  } else {
    query[kindKey] = selection.kind;
    if (selection.kind === 'VALUE') query[dimension] = selection.value;
    else delete query[dimension];
  }
  await router.push({ path: route.path, query });
}

async function resetInvalidRoute(): Promise<void> {
  const query = { ...route.query };
  for (const key of queryKeys) delete query[key];
  await router.push({ path: route.path, query });
}

function refresh(): void {
  loadCurrentRoute();
}

function chartContext(chartInstanceId: string): BiChartDownloadContext {
  const page = responseForRoute.value;
  const data = currentData.value;
  const filters = parsedQuery.value.query;
  if (!page || !data || !filters) {
    throw new Error('客户问题下载上下文尚未就绪');
  }
  return {
    pageKey: 'customer-issues',
    chartInstanceId,
    sourceVersion: page.sourceVersion,
    scope: {
      rangeType: 'CUSTOMER_ISSUE',
      milestoneBusinessKey: data.selectedMilestoneBusinessKey,
      businessDate: data.businessDate,
      customerKind: filters.customer.kind,
      ...(filters.customer.kind === 'VALUE' ? { customer: filters.customer.value } : {}),
      moduleKind: filters.module.kind,
      ...(filters.module.kind === 'VALUE' ? { module: filters.module.value } : {}),
      functionKind: filters.function.kind,
      ...(filters.function.kind === 'VALUE' ? { function: filters.function.value } : {}),
    },
  };
}

function sectionState(key: string): { status: BiDataStatus; message: string } {
  const page = responseForRoute.value;
  return page ? sectionPresentation(page, key) : { status: 'INCOMPLETE', message: '等待当前范围的数据和候选项通过校验。' };
}

function metricItem(metric: BiCustomerIssueMetric) {
  const value = metric.unit === '%'
    ? metric.percentage == null ? '--' : `${metric.percentage.toFixed(2)}%`
    : metric.value == null ? '--' : `${formatNumber(metric.value)}${metric.unit ? ` ${metric.unit}` : ''}`;
  const detail = metric.denominator != null
    ? `${formatNumber(metric.numerator)} / ${formatNumber(metric.denominator)}`
    : metric.value == null ? '来源时间或分类不完整' : undefined;
  return { label: metric.label, value, detail, status: 'neutral' as const };
}

function parseRouteQuery(query: Record<string, unknown>): { query: BiCustomerIssueQuery & Record<Dimension, BiMemberSelection> | null; error: string } {
  try {
    const milestoneBusinessKey = singleQuery(query.milestoneBusinessKey, 'milestoneBusinessKey');
    if (milestoneBusinessKey === '') throw new Error('里程碑范围不能为空');
    return {
      query: {
        milestoneBusinessKey: milestoneBusinessKey ?? undefined,
        customer: parseSelection(query.customerKind, query.customer, 'customer'),
        module: parseSelection(query.moduleKind, query.module, 'module'),
        function: parseSelection(query.functionKind, query.function, 'function'),
      },
      error: '',
    };
  } catch (error) {
    return { query: null, error: error instanceof Error ? error.message : '链接中的筛选参数无效' };
  }
}

function parseSelection(kindValue: unknown, memberValue: unknown, parameter: string): BiMemberSelection {
  const kind = singleQuery(kindValue, `${parameter}Kind`);
  const value = singleQuery(memberValue, parameter);
  if (kind == null && value == null) return { kind: 'ALL' };
  if (kind == null) throw new Error(`${parameter} 缺少类型标记`);
  if (kind === 'ALL' || kind === 'MISSING') {
    if (value != null) throw new Error(`${parameter} 的 ${kind} 类型不能携带成员值`);
    return { kind };
  }
  if (kind === 'VALUE') {
    if (value == null || value.trim() === '') throw new Error(`${parameter} 的 VALUE 类型必须携带成员值`);
    return { kind, value };
  }
  throw new Error(`${parameter} 类型只能是 ALL、MISSING 或 VALUE`);
}

function singleQuery(value: unknown, key: string): string | null {
  if (value == null) return null;
  if (typeof value !== 'string') throw new Error(`${key} 只能出现一次`);
  return value;
}

function memberToken(kind: 'VALUE' | 'MISSING' | 'ALL', value: string | null): string {
  return kind === 'VALUE' ? `VALUE:${encodeURIComponent(value ?? '')}` : kind;
}

function parseValueToken(token: string): BiMemberSelection | null {
  if (!token.startsWith('VALUE:')) return null;
  try {
    const value = decodeURIComponent(token.slice('VALUE:'.length));
    return value.trim() ? { kind: 'VALUE', value } : null;
  } catch {
    return null;
  }
}

function memberLabel(dimension: Dimension, selection: BiMemberSelection): string {
  return selection.kind === 'MISSING'
    ? `未标注${labels[dimension]}`
    : selection.kind === 'VALUE' ? selection.value : `全部${labels[dimension]}`;
}

const failureMessage = computed(() => parsedQuery.value.error || loadError.value || pageFailure.value);
const pageStatus = computed(() => responseForRoute.value?.status ?? 'INCOMPLETE');
const pageStatusLabel = computed(() => pageStatus.value === 'EMPTY' ? '范围内暂无议题'
  : pageStatus.value === 'INCOMPLETE' ? '数据暂不完整' : pageStatus.value === 'ERROR' ? '页面加载失败' : '正在等待数据');

function currentOptions(dimension: Dimension) {
  return optionsFor(dimension);
}

function sectionStatus(key: string) {
  return sectionState(key);
}
</script>

<template>
  <main class="bi-dashboard bi-customer-issues" data-bottom-scroll-safe="true">
    <header class="bi-page-head">
      <div class="bi-page-heading">
        <h1>客户问题</h1>
      </div>
      <div class="bi-page-controls" aria-label="客户问题统计筛选">
        <el-select
          class="bi-milestone-select"
          :model-value="milestoneSelection"
          :loading="loading"
          :disabled="!candidatesReady"
          filterable
          placeholder="客户里程碑"
          aria-label="客户里程碑范围"
          @change="selectMilestone"
        >
          <el-option
            v-for="item in currentData?.milestones ?? []"
            :key="item.businessKey"
            :label="item.displayName"
            :value="item.businessKey"
          />
        </el-select>
        <el-select
          v-for="dimension in dimensions"
          :key="dimension"
          class="bi-dimension-select"
          :model-value="selectedToken(dimension)"
          :loading="loading"
          :disabled="!candidatesReady"
          filterable
          :placeholder="labels[dimension]"
          :aria-label="`${labels[dimension]}筛选`"
          @change="selectMember(dimension, $event)"
        >
          <el-option
            v-for="option in currentOptions(dimension)"
            :key="option.token"
            :label="option.label"
            :value="option.token"
          />
        </el-select>
        <el-tooltip content="刷新当前页面" placement="top">
          <el-button :icon="Refresh" circle :loading="loading" aria-label="刷新当前页面" @click="refresh" />
        </el-tooltip>
      </div>
    </header>

    <el-alert
      v-if="failureMessage"
      type="error"
      :closable="false"
      show-icon
      :title="failureMessage"
    >
      <template #default>
        <el-button v-if="parsedQuery.error" size="small" @click="resetInvalidRoute">清除非法筛选</el-button>
        <el-button v-else size="small" @click="refresh">重试</el-button>
      </template>
    </el-alert>

    <div v-else-if="loading && !responseForRoute" class="bi-page-loading" v-loading="true" aria-label="正在加载客户问题数据" />

    <el-result
      v-else-if="!responseForRoute || !currentData"
      class="bi-page-failure"
      :icon="pageStatus === 'ERROR' ? 'error' : 'warning'"
      :title="pageStatusLabel"
      :sub-title="responseForRoute?.sections[0]?.message ?? '来源完整性、里程碑目录或需求身份尚未满足展示条件。'"
    >
      <template #extra>
        <el-button type="primary" :loading="loading" @click="refresh">重新加载</el-button>
      </template>
    </el-result>

    <template v-else>
      <section class="bi-stage-stack">
        <div class="bi-section-header">
          <span class="bi-section-indicator bi-section-indicator--defect"></span>
          <h2 class="bi-section-title">缺陷指标</h2>
          <span class="bi-section-badge">常规缺陷及修复质量</span>
        </div>
        <div v-for="group in defectMetricGroups" :key="group.key" class="bi-metric-group" :data-metric-group="group.key">
          <h3 class="bi-metric-group__title">{{ group.label }}</h3>
          <BiMetricStrip variant="quality" :items="group.items" />
        </div>

        <div class="bi-charts-grid">
          <BiChartPanel
            title="模块缺陷数"
            description="当前筛选范围内按模块展示已修复和未修复常规缺陷；总量按完整议题身份去重，多模块分别进入各模块。"
            :chart="moduleDefectChart"
            :data="moduleDefects"
            :height="390"
            layout="half"
            :status="sectionStatus('module-defects').status"
            :status-message="sectionStatus('module-defects').message"
            :download-context="chartContext('customer-issue-module-defects')"
            v-model:sort="moduleDefectSort"
            v-model:order="moduleDefectSortOrder"
            :sort-options="moduleDefectSortOptions"
          />
          <BiChartPanel
            title="缺陷严重级别分布"
            description="常规缺陷按严重级别分布，未标注级别保留为独立类别。"
            :chart="distributionChart"
            :data="severityDistribution"
            :height="390"
            layout="half"
            variant="pie"
            :status="sectionStatus('severity-distribution').status"
            :status-message="sectionStatus('severity-distribution').message"
            :download-context="chartContext('customer-issue-severity-distribution')"
            v-model:sort="severitySort"
            v-model:order="severitySortOrder"
            :sort-options="severitySortOptions"
          />
          <BiChartPanel
            title="模块与缺陷严重级别"
            description="每个模块内按严重级别拆分常规缺陷数；全局议题总数不累加模块柱。"
            :chart="moduleSeverityChart"
            :data="moduleSeverity"
            :height="410"
            layout="full"
            variant="analysis"
            :status="sectionStatus('module-severity').status"
            :status-message="sectionStatus('module-severity').message"
            :download-context="chartContext('customer-issue-module-severity')"
            v-model:sort="moduleSeveritySort"
            v-model:order="moduleSeveritySortOrder"
            :sort-options="moduleSeveritySortOptions"
          />
          <BiChartPanel
            title="缺陷原因分布"
            description="按客户问题事实中的原因类别展示常规缺陷；未归类数单独保留。"
            :chart="causeChart"
            :data="causeDistribution"
            :height="430"
            layout="half"
            :status="sectionStatus('cause-distribution').status"
            :status-message="sectionStatus('cause-distribution').message"
            :download-context="chartContext('customer-issue-cause-distribution')"
            v-model:sort="causeSort"
            v-model:order="causeSortOrder"
            :sort-options="causeSortOptions"
          />
          <BiChartPanel
            title="申请延期分析"
            description="仅统计常规缺陷中当前处于申请延期状态的议题，按延期原因与严重级别交叉分布。"
            :chart="delayChart"
            :data="delayAnalysis"
            :height="430"
            layout="half"
            variant="analysis"
            :status="sectionStatus('delay-analysis').status"
            :status-message="sectionStatus('delay-analysis').message"
            :download-context="chartContext('customer-issue-delay-analysis')"
          />
          <BiChartPanel
            title="按指派人统计缺陷数"
            description="按当前指派责任人汇总常规缺陷，并区分已修复与未修复。"
            :chart="workloadChart"
            :data="assigneeWorkload"
            :height="430"
            layout="full"
            variant="analysis"
            :status="sectionStatus('assignee-workload').status"
            :status-message="sectionStatus('assignee-workload').message"
            :download-context="chartContext('customer-issue-assignee-workload')"
            v-model:sort="assigneeSort"
            v-model:order="assigneeSortOrder"
            :sort-options="assigneeSortOptions"
          />
          <BiChartPanel
            title="缺陷累计新增与累计修复"
            subtitle="按当前筛选范围回看自然日并逐日累加，单位：个"
            description="累计新增按议题创建日累加，累计修复按完成修复加标日累加；同一来源版本内序列非下降。按当前事实回看，撤销或重新修复会改变历史累计值，本图不代表历史事件流水。"
            :chart="dailyChart"
            :data="dailyTrend"
            :height="430"
            layout="full"
            variant="analysis"
            :status="sectionStatus('daily-defect-trend').status"
            :status-message="sectionStatus('daily-defect-trend').message"
            :download-context="chartContext('customer-issue-daily-trend')"
          />
        </div>
      </section>
      <div class="bi-section-divider"></div>
      <section class="bi-stage-stack">
        <div class="bi-section-header">
          <span class="bi-section-indicator bi-section-indicator--demand"></span>
          <h2 class="bi-section-title">需求指标</h2>
          <span class="bi-section-badge">客户需求及交付状态</span>
        </div>
        <BiMetricStrip v-if="requirementMetricItems.length" variant="supporting" :items="requirementMetricItems" />
        <el-alert
          v-if="sectionStatus('requirement-overview').status === 'INCOMPLETE'"
          type="warning"
          :closable="false"
          :title="sectionStatus('requirement-overview').message"
        />
        <div class="bi-charts-grid">
          <BiChartPanel
            title="模块需求分布"
            description="展示各模块的客户需求分布。统计明确标记为客户需求的议题（排除常规缺陷）；多模块需求进入关联模块，全局需求指标不累加模块柱。"
            :chart="demandChart"
            :data="moduleDemand"
            :height="410"
            layout="full"
            variant="analysis"
            :status="sectionStatus('module-requirements').status"
            :status-message="sectionStatus('module-requirements').message"
            :download-context="chartContext('customer-issue-module-demand')"
            v-model:sort="moduleDemandSort"
            v-model:order="moduleDemandSortOrder"
            :sort-options="moduleDemandSortOptions"
          />
        </div>
      </section>
    </template>
  </main>
</template>

<style scoped>
.bi-dashboard {
  width: 100%;
  max-width: 1680px;
  margin: 0 auto;
  --bi-dashboard-bottom-safe-space: 80px;
  padding: 0 2px var(--bi-dashboard-bottom-safe-space);
  min-width: 0;
  display: grid;
  gap: 12px;
}

.bi-customer-issues { gap: 12px; }

.bi-page-head {
  min-height: 52px;
  padding: 0 2px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.bi-page-heading { display: flex; align-items: baseline; gap: 10px; }
.bi-page-heading h1 { margin: 0; color: #1d2939; font-size: 20px; font-weight: 700; line-height: 28px; letter-spacing: 0; }

.bi-page-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.bi-milestone-select { width: 170px; }
.bi-dimension-select { width: 140px; }

.bi-section-divider {
  width: 100%;
  border-top: 1px solid #e4e7ec;
  margin: 12px 0 4px;
}

.bi-section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}

.bi-section-indicator {
  width: 4px;
  height: 16px;
  border-radius: 2px;
  flex-shrink: 0;
}

.bi-section-indicator--defect {
  background-color: #5470c6;
}

.bi-section-indicator--demand {
  background-color: #0ea5e9;
}

.bi-section-title {
  margin: 0;
  color: #1d2939;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
}

.bi-section-badge {
  color: #667085;
  font-size: 12px;
  line-height: 18px;
  background: #f2f4f7;
  padding: 1px 8px;
  border-radius: 4px;
  font-weight: normal;
}

.bi-page-loading, .bi-page-failure { min-height: 420px; }

:deep(.bi-stage-stack) {
  min-width: 0;
  display: grid;
  gap: 12px;
}

:deep(.bi-grid),
:deep(.bi-charts-grid) {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 12px;
  align-items: stretch;
}

.bi-metric-group {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}

.bi-metric-group__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #475467;
}

@media (max-width: 1280px) {
  .bi-page-head { align-items: flex-start; }
  .bi-page-controls { flex-wrap: wrap; justify-content: flex-end; }
}

@media (max-width: 760px) {
  .bi-dashboard {
    padding: 0 0 var(--bi-dashboard-bottom-safe-space);
  }
  .bi-page-head { display: grid; gap: 10px; }
  .bi-page-controls { justify-content: flex-start; }
  .bi-milestone-select,
  .bi-dimension-select { width: min(100%, 220px); }
  :deep(.bi-grid),
  :deep(.bi-charts-grid) { grid-template-columns: minmax(0, 1fr); }
}
</style>
