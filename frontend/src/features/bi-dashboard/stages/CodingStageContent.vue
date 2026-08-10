<script setup lang="ts">
import { computed, type PropType } from 'vue';
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
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import { formatNumber, metricStatus, sectionPresentation } from '../data/presentation';
import type { BiCodingPageData, BiPageResponse } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiCodingPageData>>, required: true },
  productVersionId: { type: Number, required: true },
});

const submissionChart = new SubmissionTrendComboChart();
const codingTrendChart = new CodingTrendComboChart();
const categoryChart = new DistributionDonutChart();
const verticalBarChart = new VerticalCategoryBarChart();
const frequencyChart = new SubmissionFrequencyBarChart();
const scanChart = new StackedCategoryBarChart();
const reviewQualityChart = new ReviewQualityDualPanelChart({ densityRange: [2, 10], densityUnit: '个/KLOC', rateUnit: '行/小时' });
const reviewScatterChart = new ReviewQualityScatterChart({ densityRange: [2, 10], densityUnit: '个/KLOC', rateUnit: 'KLOC/小时' });
const qualityTrendChart = new QualityTrendSmallMultiplesChart();
const data = computed(() => props.response.data);

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
const contributors = computed<NamedValue[]>(() => (data.value?.contributors ?? []).map((item) => ({ name: item.contributor.displayName, value: item.addedLines })));
const frequencies = computed<NamedValue[]>(() => (data.value?.submissionTrend ?? []).map((item) => ({ name: item.period, value: item.commitCount ?? 0 })));
const modules = computed<NamedValue[]>(() => (data.value?.moduleIncrements ?? []).map((item) => ({ name: item.module.displayName, value: item.addedLines })));
const reviewCategories = computed<NamedValue[]>(() => (data.value?.reviewCategories ?? []).map((item, index) => ({
  name: item.category,
  value: item.count,
  color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red][index % 5],
})));
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
  return {
    categories,
    series: [
      { name: '扫描记录数', values: categories.map((status) => grouped.get(status)?.records ?? 0) },
      { name: '问题总数', values: categories.map((status) => grouped.get(status)?.bugs ?? 0) },
    ],
  };
});
const moduleReviews = computed<ReviewQualityRow[]>(() => (data.value?.moduleReviewQuality ?? []).map((item) => ({ name: item.module.displayName, density: item.defectDensity, rate: item.reviewSpeedLocPerHour, achieved: item.achieved })));
const reviewPoints = computed<ReviewScatterPoint[]>(() => (data.value?.reviewPoints ?? []).map((item) => ({
  name: item.module.displayName,
  date: item.reviewDate,
  rate: item.reviewSpeedKlocPerHour,
  density: item.defectDensity,
  achieved: item.achieved,
})));
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
      <BiChartPanel
        title="代码增量趋势"
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
      />
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
      <BiChartPanel
        title="人员贡献"
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
      />
      <BiChartPanel
        title="各模块代码增量"
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
      />
      <BiChartPanel
        title="提交频次时间分布"
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
      />
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
      />
      <BiChartPanel
        title="静态扫描结果"
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
      />
      <BiChartPanel
        title="各模块人工代码走查质量"
        subtitle="缺陷密度与目标区间、评审速率分轨展示"
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
      />
      <BiChartPanel
        title="单次人工走查质量分布"
        :chart="reviewScatterChart"
        :data="reviewPoints"
        :height="390"
        variant="analysis"
        layout="half"
        :status="sectionPresentation(response, 'review-scatter').status"
        :status-message="sectionPresentation(response, 'review-scatter').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
      />
      <BiChartPanel
        title="代码质量趋势"
        :chart="qualityTrendChart"
        :data="qualityTrend"
        :height="390"
        variant="analysis"
        layout="half"
        :status="sectionPresentation(response, 'quality-trend').status"
        :status-message="sectionPresentation(response, 'quality-trend').message"
        :product-version-id="productVersionId"
        page-key="coding"
        :source-version="response.sourceVersion"
      />
    </div>
  </div>
</template>
