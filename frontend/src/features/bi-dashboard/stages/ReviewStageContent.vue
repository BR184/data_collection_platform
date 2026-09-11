<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import { DistributionDonutChart, ReviewQualityDualPanelChart, ReviewQualityScatterChart } from '../charts/types';
import type { NamedValue, ReviewQualityRow, ReviewScatterPoint } from '../charts/chart-data';
import { BI_PALETTE } from '../charts/palette';
import BiChartPanel from '../components/BiChartPanel.vue';
import type { BiSortOrder } from '../components/BiChartSortControl.vue';
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import { formatNumber, metricStatus, sectionPresentation } from '../data/presentation';
import { BI_CHART_EXPLANATIONS } from '../data/chart-explanations';
import { reviewDensityRange } from '../data/quality-targets';
import { sortNamedValues, sortReviewQualityRows, sortReviewScatterPoints } from '../data/sorting';
import type { BiPageKey, BiPageResponse, BiReviewPageData } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiReviewPageData>>, required: true },
  productVersionId: { type: Number, required: true },
  productVersionName: { type: String, required: true },
  stageLabel: { type: String, required: true },
  pageKey: { type: String as PropType<BiPageKey>, required: true },
});

const donutChart = new DistributionDonutChart();
const densityRange = reviewDensityRange(props.pageKey);
const densityTargetValue = `${densityRange[0].toFixed(2)}–${densityRange[1].toFixed(2)}`;
const densityTargetRule = `缺陷密度目标：${densityRange[0].toFixed(2)} ~ ${densityRange[1].toFixed(2)} 个/页 · 评审速率不设目标`;
const qualityChart = new ReviewQualityDualPanelChart({ densityRange, densityUnit: '个/页', rateUnit: '页/小时' });
const scatterChart = new ReviewQualityScatterChart({ densityRange, densityUnit: '个/页', rateUnit: '页/小时' });

const categoryDescription = computed(() => (
  props.pageKey === 'requirement-review'
    ? BI_CHART_EXPLANATIONS.requirementReviewCategories
    : BI_CHART_EXPLANATIONS.designReviewCategories
));
const qualityDescription = computed(() => (
  props.pageKey === 'requirement-review'
    ? BI_CHART_EXPLANATIONS.requirementReviewQuality
    : BI_CHART_EXPLANATIONS.designReviewQuality
));
const scatterDescription = computed(() => (
  props.pageKey === 'requirement-review'
    ? BI_CHART_EXPLANATIONS.requirementReviewScatter
    : BI_CHART_EXPLANATIONS.designReviewScatter
));

const moduleSort = ref('status');
const moduleSortOrder = ref<BiSortOrder>('asc');
const categorySort = ref('count');
const categorySortOrder = ref<BiSortOrder>('desc');
const scatterSort = ref('density');
const scatterSortOrder = ref<BiSortOrder>('desc');

const moduleSortOptions = [
  { label: '异常优先', value: 'status' },
  { label: '缺陷密度', value: 'density' },
  { label: '评审速率', value: 'rate' },
  { label: '模块名称', value: 'name' },
];
const categorySortOptions = [
  { label: '问题数量', value: 'count' },
  { label: '类别名称', value: 'name' },
];
const scatterSortOptions = [
  { label: '缺陷密度', value: 'density' },
  { label: '评审速率', value: 'rate' },
  { label: '评审日期', value: 'date' },
  { label: '模块名称', value: 'name' },
];

const metrics = computed<BiMetricItem[]>(() => {
  const summary = props.response.data?.summary;
  if (!summary) return [];
  const exceptionCount = (props.response.data?.modules ?? []).filter((item) => item.achieved === false).length;
  return [
    { label: '整体缺陷密度', value: formatNumber(summary.defectDensity, 2), detail: '个 / 页', status: metricStatus(summary.achieved) },
    { label: '整体评审速率', value: formatNumber(summary.reviewRate, 2), detail: '页 / 小时' },
    { label: '缺陷密度目标', value: densityTargetValue, detail: '个 / 页' },
    { label: '达标状态', value: summary.achieved == null ? '不可计算' : summary.achieved ? '达标' : '未达标', detail: '按缺陷密度判断', status: metricStatus(summary.achieved) },
    { label: '异常模块', value: String(exceptionCount), detail: '超出目标区间', status: exceptionCount > 0 ? 'danger' : 'success' },
  ];
});

const categories = computed<NamedValue[]>(() => {
  const raw: NamedValue[] = (props.response.data?.categories ?? []).map((item, index) => ({
    name: item.category,
    value: item.count,
    color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red][index % 5],
  }));
  return sortNamedValues(raw, categorySort.value, categorySortOrder.value);
});

const modules = computed<ReviewQualityRow[]>(() => {
  const raw: ReviewQualityRow[] = (props.response.data?.modules ?? []).map((item) => ({
    name: item.module.displayName,
    density: item.defectDensity,
    rate: item.reviewRate,
    achieved: item.achieved,
  }));
  return sortReviewQualityRows(raw, moduleSort.value, moduleSortOrder.value);
});

const points = computed<ReviewScatterPoint[]>(() => {
  const raw: ReviewScatterPoint[] = (props.response.data?.reviewPoints ?? []).map((item) => ({
    name: item.module.displayName,
    date: item.reviewDate,
    rate: item.reviewRate,
    density: item.defectDensity,
    achieved: item.achieved,
  }));
  return sortReviewScatterPoints(raw, scatterSort.value, scatterSortOrder.value);
});
</script>

<template>
  <div class="bi-stage-stack">
    <div class="bi-rule-bar">
      <span class="bi-rule-bar__tag">质量目标</span>
      <span class="bi-rule-bar__text">{{ densityTargetRule }}</span>
    </div>
    <BiMetricStrip v-if="metrics.length" variant="review" :items="metrics" />

    <div class="bi-charts-grid">
      <BiChartPanel
        :title="`${stageLabel}评审问题类别分布`"
        :description="categoryDescription"
        :chart="donutChart"
        :data="categories"
        :height="430"
        variant="pie"
        layout="compact"
        :status="sectionPresentation(response, 'problem-categories').status"
        :status-message="sectionPresentation(response, 'problem-categories').message"
        :product-version-id="productVersionId"
        :product-version-name="productVersionName"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
        v-model:sort="categorySort"
        v-model:order="categorySortOrder"
        :sort-options="categorySortOptions"
      />
      <BiChartPanel
        :title="`各模块${stageLabel}评审质量`"
        subtitle="左：缺陷密度与目标区间；右：评审速率"
        :description="qualityDescription"
        :chart="qualityChart"
        :data="modules"
        :height="430"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'module-quality').status"
        :status-message="sectionPresentation(response, 'module-quality').message"
        :product-version-id="productVersionId"
        :product-version-name="productVersionName"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
        v-model:sort="moduleSort"
        v-model:order="moduleSortOrder"
        :sort-options="moduleSortOptions"
      />
      <BiChartPanel
        :title="`每次${stageLabel}评审质量分布`"
        subtitle="横轴评审速率，纵轴缺陷密度"
        :description="scatterDescription"
        :chart="scatterChart"
        :data="points"
        :height="390"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'review-scatter').status"
        :status-message="sectionPresentation(response, 'review-scatter').message"
        :product-version-id="productVersionId"
        :product-version-name="productVersionName"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
        v-model:sort="scatterSort"
        v-model:order="scatterSortOrder"
        :sort-options="scatterSortOptions"
      />
    </div>
  </div>
</template>

<style scoped>
.bi-rule-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  font-size: 12px;
  line-height: 18px;
}

.bi-rule-bar__tag {
  font-weight: 600;
  color: #166534;
  padding: 0 6px;
  background: #dcfce7;
  border-radius: 4px;
}

.bi-rule-bar__text {
  color: #15803d;
}
</style>
