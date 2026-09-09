<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import type { CategorySeriesData, CodingTrendData, NamedValue, QualityTrendData, ReviewQualityRow, ReviewScatterPoint, SubmissionTrendData } from '../charts/chart-data';
import {
  CodingTrendComboChart,
  DistributionDonutChart,
  QualityTrendSmallMultiplesChart,
  ReviewQualityDualPanelChart,
  ReviewQualityScatterChart,
  StackedCategoryBarChart,
  SubmissionFrequencyBarChart,
  SubmissionTrendComboChart,
  VerticalCategoryBarChart,
} from '../charts/types';
import { BI_PALETTE } from '../charts/palette';
import BiChartPanel from '../components/BiChartPanel.vue';
import BiChartSortControl, { type BiSortOrder } from '../components/BiChartSortControl.vue';
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import { formatNumber, metricStatus, sectionPresentation } from '../data/presentation';
import { codingDensityRange } from '../data/quality-targets';
import {
  sortCategorySeriesData,
  sortNamedValues,
  sortReviewQualityRows,
  sortReviewScatterPoints,
} from '../data/sorting';
import type { BiCodingPageData, BiPageResponse } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiCodingPageData>>, required: true },
  productVersionId: { type: Number, required: true },
  granularity: { type: String, default: 'day' },
});

defineEmits<{
  (event: 'update:granularity', value: string | number | boolean | undefined): void;
}>();

const submissionChart = new SubmissionTrendComboChart();
const codingTrendChart = new CodingTrendComboChart();
const categoryChart = new DistributionDonutChart();
const verticalBarChart = new VerticalCategoryBarChart();
const frequencyChart = new SubmissionFrequencyBarChart();
const scanChart = new StackedCategoryBarChart();
const reviewQualityChart = new ReviewQualityDualPanelChart({ densityRange: codingDensityRange, densityUnit: '个/KLOC', rateUnit: '行/小时' });
const reviewScatterChart = new ReviewQualityScatterChart({ densityRange: codingDensityRange, densityUnit: '个/KLOC', rateUnit: 'KLOC/小时' });
const qualityTrendChart = new QualityTrendSmallMultiplesChart();
const data = computed(() => props.response.data);

// 各图表排序状态
const moduleReviewSort = ref('status');
const moduleReviewSortOrder = ref<BiSortOrder>('asc');
const reviewCategorySort = ref('count');
const reviewCategorySortOrder = ref<BiSortOrder>('desc');
const scanSort = ref('bugs');
const scanSortOrder = ref<BiSortOrder>('desc');
const contributorSort = ref('count');
const contributorSortOrder = ref<BiSortOrder>('desc');
const moduleIncrementSort = ref('count');
const moduleIncrementSortOrder = ref<BiSortOrder>('desc');
const frequencySort = ref('name');
const frequencySortOrder = ref<BiSortOrder>('asc');
const reviewScatterSort = ref('density');
const reviewScatterSortOrder = ref<BiSortOrder>('desc');

const moduleReviewSortOptions = [
  { label: '异常优先', value: 'status' },
  { label: '走查缺陷密度', value: 'density' },
  { label: '走查速率', value: 'rate' },
  { label: '模块名称', value: 'name' },
];
const countNameSortOptions = [
  { label: '数量排序', value: 'count' },
  { label: '名称排序', value: 'name' },
];
const scanSortOptions = [
  { label: '问题总数', value: 'bugs' },
  { label: '扫描记录数', value: 'records' },
  { label: '状态名称', value: 'name' },
];
const contributorSortOptions = [
  { label: '代码行数', value: 'count' },
  { label: '人员姓名', value: 'name' },
];
const moduleIncrementSortOptions = [
  { label: '代码增量', value: 'count' },
  { label: '模块名称', value: 'name' },
];
const frequencySortOptions = [
  { label: '时间顺序', value: 'name' },
  { label: '提交频次', value: 'count' },
];
const reviewScatterSortOptions = [
  { label: '缺陷密度', value: 'density' },
  { label: '走查速率', value: 'rate' },
  { label: '走查日期', value: 'date' },
  { label: '模块名称', value: 'name' },
];

const metrics = computed<BiMetricItem[]>(() => {
  const summary = data.value?.summary;
  if (!summary) return [];
  return [
    { label: '累计新增代码', value: formatNumber(summary.addedKloc, 2), detail: 'KLOC' },
    { label: '合并请求', value: formatNumber(summary.mergeRequestCount), detail: '个' },
    { label: '贡献人员', value: formatNumber(summary.contributorCount), detail: '人' },
    { label: '人工走查缺陷密度', value: formatNumber(summary.reviewDefectDensity, 2), detail: '个 / KLOC', status: metricStatus(summary.reviewDensityAchieved) },
    { label: '人工走查速率', value: formatNumber(summary.reviewSpeedLocPerHour, 2), detail: '行 / 小时' },
    { label: '走查质量状态', value: summary.reviewDensityAchieved == null ? '不可计算' : summary.reviewDensityAchieved ? '达标' : '未达标', detail: '按缺陷密度判断', status: metricStatus(summary.reviewDensityAchieved) },
  ];
});

const submissionTrend = computed<SubmissionTrendData>(() => ({
  periods: data.value?.submissionTrend.map((item) => item.period) ?? [],
  commits: data.value?.submissionTrend.map((item) => item.commitCount) ?? [],
  mergeRequests: data.value?.submissionTrend.map((item) => item.mergeRequestCount) ?? [],
}));
const codeTrend = computed<CodingTrendData>(() => ({
  periods: data.value?.codeTrend.map((item) => item.period) ?? [],
  addedLines: data.value?.codeTrend.map((item) => item.addedLines) ?? [],
  cumulativeLines: data.value?.codeTrend.map((item) => item.cumulativeLines) ?? [],
}));
const contributors = computed<NamedValue[]>(() => {
  const raw = (data.value?.contributors ?? []).map((item) => ({ name: item.contributor.displayName, value: item.addedLines }));
  return sortNamedValues(raw, contributorSort.value, contributorSortOrder.value);
});
const frequencies = computed<NamedValue[]>(() => {
  const raw = (data.value?.submissionTrend ?? []).map((item) => ({ name: item.period, value: item.commitCount ?? 0 }));
  return sortNamedValues(raw, frequencySort.value, frequencySortOrder.value);
});
const modules = computed<NamedValue[]>(() => {
  const raw = (data.value?.moduleIncrements ?? []).map((item) => ({ name: item.module.displayName, value: item.addedLines }));
  return sortNamedValues(raw, moduleIncrementSort.value, moduleIncrementSortOrder.value);
});
const reviewCategories = computed<NamedValue[]>(() => {
  const raw = (data.value?.reviewCategories ?? []).map((item, index) => ({
    name: item.category,
    value: item.count,
    color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red][index % 5],
  }));
  return sortNamedValues(raw, reviewCategorySort.value, reviewCategorySortOrder.value);
});
const scanData = computed<CategorySeriesData>(() => {
  const grouped = new Map<string, { records: number; bugs: number }>();
  for (const point of data.value?.scanTrend ?? []) {
    const status = point.status?.trim();
    if (!status) continue;
    const current = grouped.get(status) ?? { records: 0, bugs: 0 };
    current.records += 1;
    current.bugs += point.bugCount ?? 0;
    grouped.set(status, current);
  }
  const categories = [...grouped.keys()];
  const raw: CategorySeriesData = {
    categories,
    series: [
      { name: '扫描记录数', values: categories.map((status) => grouped.get(status)?.records ?? 0) },
      { name: '问题总数', values: categories.map((status) => grouped.get(status)?.bugs ?? 0) },
    ],
  };
  return sortCategorySeriesData(raw, scanSort.value, scanSortOrder.value);
});
const moduleReviews = computed<ReviewQualityRow[]>(() => {
  const raw = (data.value?.moduleReviewQuality ?? []).map((item) => ({ name: item.module.displayName, density: item.defectDensity, rate: item.reviewSpeedLocPerHour, achieved: item.achieved }));
  return sortReviewQualityRows(raw, moduleReviewSort.value, moduleReviewSortOrder.value);
});
const reviewPoints = computed<ReviewScatterPoint[]>(() => {
  const raw = (data.value?.reviewPoints ?? []).map((item) => ({
    name: item.module.displayName,
    date: item.reviewDate,
    rate: item.reviewSpeedKlocPerHour,
    density: item.defectDensity,
    achieved: item.achieved,
  }));
  return sortReviewScatterPoints(raw, reviewScatterSort.value, reviewScatterSortOrder.value);
});
const qualityTrend = computed<QualityTrendData>(() => {
  const periods = [...new Set([
    ...(data.value?.commentRatePoints.map((item) => item.observedOn) ?? []),
    ...(data.value?.reviewDensityTrend.map((item) => item.period) ?? []),
  ])].sort();
  const comments = new Map(data.value?.commentRatePoints.map((item) => [item.observedOn, item.commentRate]));
  const densities = new Map(data.value?.reviewDensityTrend.map((item) => [item.period, item.reviewDefectDensity]));
  return { periods, commentRates: periods.map((period) => comments.get(period) ?? null), defectDensities: periods.map((period) => densities.get(period) ?? null) };
});
</script>

<template>
  <div class="bi-stage-stack">
    <BiMetricStrip v-if="metrics.length" variant="quality" :items="metrics" />
    <div class="bi-charts-grid">
      <!-- 1. 置顶全幅：各模块人工代码走查质量 -->
      <BiChartPanel
        title="各模块人工代码走查质量"
        subtitle="左：走查缺陷密度（个/KLOC）；右：走查速率（行/小时）"
        :chart="reviewQualityChart"
        :data="moduleReviews"
        :height="470"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'module-review-quality').status"
        :status-message="sectionPresentation(response, 'module-review-quality').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="moduleReviewSort"
        v-model:order="moduleReviewSortOrder"
        :sort-options="moduleReviewSortOptions"
      />

      <!-- 2. 代码走查问题分布 (50%) + 静态代码扫描结果 (50%) -->
      <BiChartPanel
        title="代码走查问题分布"
        :chart="categoryChart"
        :data="reviewCategories"
        :height="356"
        variant="pie"
        layout="compact"
        :status="sectionPresentation(response, 'review-categories').status"
        :status-message="sectionPresentation(response, 'review-categories').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="reviewCategorySort"
        v-model:order="reviewCategorySortOrder"
        :sort-options="countNameSortOptions"
      />
      <BiChartPanel
        title="静态代码扫描结果"
        subtitle="单位：个"
        :chart="scanChart"
        :data="scanData"
        :height="356"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'static-scan').status"
        :status-message="sectionPresentation(response, 'static-scan').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="scanSort"
        v-model:order="scanSortOrder"
        :sort-options="scanSortOptions"
      />

      <!-- 3. 开发人员代码贡献 (50%) + 各模块代码增量 (50%) -->
      <BiChartPanel
        title="开发人员代码贡献"
        subtitle="单位：新增代码行数 (行)"
        :chart="verticalBarChart"
        :data="contributors"
        :height="356"
        variant="analysis"
        layout="compact"
        :status="sectionPresentation(response, 'contributors').status"
        :status-message="sectionPresentation(response, 'contributors').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="contributorSort"
        v-model:order="contributorSortOrder"
        :sort-options="contributorSortOptions"
      />
      <BiChartPanel
        title="各模块代码增量"
        subtitle="单位：新增代码行数 (行)"
        :chart="verticalBarChart"
        :data="modules"
        :height="356"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'module-increments').status"
        :status-message="sectionPresentation(response, 'module-increments').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="moduleIncrementSort"
        v-model:order="moduleIncrementSortOrder"
        :sort-options="moduleIncrementSortOptions"
      />

      <!-- 4. 代码注释率与走查密度趋势 (全幅双轨道) -->
      <BiChartPanel
        title="代码注释率与走查密度趋势"
        subtitle="左轨：代码注释率 (%)；右轨：人工走查缺陷密度 (个/KLOC)"
        :chart="qualityTrendChart"
        :data="qualityTrend"
        :height="390"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'quality-trend').status"
        :status-message="sectionPresentation(response, 'quality-trend').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
      />

      <!-- 5. 代码增量趋势 (50%, 内部包含“按日/按周”控件) + 提交趋势 (50%) -->
      <BiChartPanel
        title="代码增量趋势"
        subtitle="单位：代码量 (KLOC)"
        :chart="codingTrendChart"
        :data="codeTrend"
        :height="356"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'code-trend').status"
        :status-message="sectionPresentation(response, 'code-trend').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
      >
        <template #actions>
          <el-segmented
            :model-value="granularity"
            :options="[{ label: '按日', value: 'day' }, { label: '按周', value: 'week' }]"
            size="small"
            @change="$emit('update:granularity', $event)"
          />
        </template>
      </BiChartPanel>
      <BiChartPanel
        title="提交趋势"
        :chart="submissionChart"
        :data="submissionTrend"
        :height="356"
        variant="analysis"
        layout="compact"
        :status="sectionPresentation(response, 'submission-trend').status"
        :status-message="sectionPresentation(response, 'submission-trend').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
      />

      <!-- 6. 最底：代码提交频次时间分布 (全幅置底) -->
      <BiChartPanel
        title="代码提交频次时间分布"
        :chart="frequencyChart"
        :data="frequencies"
        :height="300"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'submission-trend').status"
        :status-message="sectionPresentation(response, 'submission-trend').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="frequencySort"
        v-model:order="frequencySortOrder"
        :sort-options="frequencySortOptions"
      />

      <!-- 单次人工走查质量分布 (下钻/明细) -->
      <BiChartPanel
        title="单次人工走查质量分布"
        subtitle="横轴：走查速率 (KLOC/小时)；纵轴：缺陷密度 (个/KLOC)"
        :chart="reviewScatterChart"
        :data="reviewPoints"
        :height="390"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'review-scatter').status"
        :status-message="sectionPresentation(response, 'review-scatter').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
        v-model:sort="reviewScatterSort"
        v-model:order="reviewScatterSortOrder"
        :sort-options="reviewScatterSortOptions"
      />
    </div>
  </div>
</template>
