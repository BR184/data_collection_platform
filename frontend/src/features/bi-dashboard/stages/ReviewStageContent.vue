<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import { DistributionDonutChart, ReviewQualityDualPanelChart, ReviewQualityScatterChart } from '../charts/types';
import type { NamedValue, ReviewQualityRow, ReviewScatterPoint } from '../charts/chart-data';
import { BI_PALETTE } from '../charts/palette';
import BiAnalysisToolbar, { type BiToolbarOption } from '../components/BiAnalysisToolbar.vue';
import BiChartPanel from '../components/BiChartPanel.vue';
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import { formatNumber, metricStatus, sectionPresentation } from '../data/presentation';
import type { BiPageKey, BiPageResponse, BiReviewPageData } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiReviewPageData>>, required: true },
  productVersionId: { type: Number, required: true },
  stageLabel: { type: String, required: true },
  pageKey: { type: String as PropType<BiPageKey>, required: true },
});

const donutChart = new DistributionDonutChart();
const qualityChart = new ReviewQualityDualPanelChart({ densityRange: [0.2, 0.6], densityUnit: '问题/页', rateUnit: '页/小时' });
const scatterChart = new ReviewQualityScatterChart({ densityRange: [0.2, 0.6], densityUnit: '问题/页', rateUnit: '页/小时' });
const moduleLimit = ref('all');
const moduleSort = ref('statusAsc');
const moduleSearch = ref('');
const limitOptions: BiToolbarOption[] = [
  { label: '显示全部', value: 'all' },
  { label: '显示 10 项', value: '10' },
  { label: '显示 20 项', value: '20' },
];
const sortOptions: BiToolbarOption[] = [
  { label: '异常优先', value: 'statusAsc' },
  { label: '密度降序', value: 'densityDesc' },
  { label: '速率降序', value: 'rateDesc' },
  { label: '模块名称', value: 'nameAsc' },
];

const metrics = computed<BiMetricItem[]>(() => {
  const summary = props.response.data?.summary;
  if (!summary) return [];
  const exceptionCount = (props.response.data?.modules ?? []).filter((item) => item.achieved === false).length;
  return [
    { label: '整体缺陷密度', value: formatNumber(summary.defectDensity, 2), detail: '问题 / 页', status: metricStatus(summary.achieved) },
    { label: '整体评审速率', value: formatNumber(summary.reviewRate, 2), detail: '页 / 小时' },
    { label: '缺陷密度目标', value: '0.20–0.60', detail: '问题 / 页' },
    { label: '达标状态', value: summary.achieved == null ? '不可计算' : summary.achieved ? '达标' : '未达标', detail: '按缺陷密度判断', status: metricStatus(summary.achieved) },
    { label: '异常模块', value: String(exceptionCount), detail: '超出目标区间', status: exceptionCount > 0 ? 'danger' : 'success' },
  ];
});

const categories = computed<NamedValue[]>(() => (props.response.data?.categories ?? []).map((item, index) => ({
  name: item.category,
  value: item.count,
  color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red][index % 5],
})));

const modules = computed<ReviewQualityRow[]>(() => {
  const search = moduleSearch.value.trim().toLowerCase();
  const rows = (props.response.data?.modules ?? [])
    .filter((item) => !search || item.module.displayName.toLowerCase().includes(search))
    .slice()
    .sort((left, right) => {
      if (moduleSort.value === 'densityDesc') return (right.defectDensity ?? -1) - (left.defectDensity ?? -1);
      if (moduleSort.value === 'rateDesc') return (right.reviewRate ?? -1) - (left.reviewRate ?? -1);
      if (moduleSort.value === 'nameAsc') return left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      return Number(left.achieved ?? true) - Number(right.achieved ?? true) || (right.defectDensity ?? -1) - (left.defectDensity ?? -1);
    });
  return limitRows(rows, moduleLimit.value).map((item) => ({
    name: item.module.displayName,
    density: item.defectDensity,
    rate: item.reviewRate,
    achieved: item.achieved,
  }));
});

const points = computed<ReviewScatterPoint[]>(() => (props.response.data?.reviewPoints ?? []).map((item) => ({
  name: item.module.displayName,
  date: item.reviewDate,
  rate: item.reviewRate,
  density: item.defectDensity,
  achieved: item.achieved,
})));

function limitRows<T>(rows: T[], limit: string): T[] {
  const count = Number(limit);
  return Number.isSafeInteger(count) && count > 0 ? rows.slice(0, count) : rows;
}

function setModuleLimit(value: string | number): void { moduleLimit.value = String(value); }
</script>

<template>
  <div class="bi-stage-stack">
    <section class="bi-review-intro">
      <div>
        <span>{{ stageLabel }}评审质量</span>
        <h2>整体质量摘要</h2>
      </div>
      <p>缺陷密度目标 0.20–0.60 问题/页 · 评审速率不设目标</p>
    </section>
    <BiMetricStrip v-if="metrics.length" variant="review" :items="metrics" />

    <div class="bi-charts-grid">
      <BiChartPanel
        :title="`${stageLabel}评审问题类别分布`"
        :chart="donutChart"
        :data="categories"
        :height="356"
        variant="pie"
        layout="compact"
        :status="sectionPresentation(response, 'problem-categories').status"
        :status-message="sectionPresentation(response, 'problem-categories').message"
        :product-version-id="productVersionId"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
      />
      <BiChartPanel
        :title="`各模块${stageLabel}评审质量`"
        subtitle="左：缺陷密度与目标区间；右：评审速率"
        :chart="qualityChart"
        :data="modules"
        :height="430"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'module-quality').status"
        :status-message="sectionPresentation(response, 'module-quality').message"
        :product-version-id="productVersionId"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
      >
        <template #actions>
          <BiAnalysisToolbar
            :limit="moduleLimit"
            :sort="moduleSort"
            :search="moduleSearch"
            :limit-options="limitOptions"
            :sort-options="sortOptions"
            search-placeholder="搜索模块"
            @update:limit="setModuleLimit"
            @update:sort="moduleSort = $event"
            @update:search="moduleSearch = $event"
          />
        </template>
      </BiChartPanel>
      <BiChartPanel
        :title="`每次${stageLabel}评审质量分布`"
        subtitle="横轴评审速率，纵轴缺陷密度"
        :chart="scatterChart"
        :data="points"
        :height="390"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'review-scatter').status"
        :status-message="sectionPresentation(response, 'review-scatter').message"
        :product-version-id="productVersionId"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
      />
    </div>
  </div>
</template>

<style scoped>
.bi-review-intro {
  min-height: 42px;
  margin: 0 2px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.bi-review-intro span { color: #344e86; font-size: 12px; font-weight: 650; line-height: 18px; }
.bi-review-intro h2 { margin: 0; color: #1d2939; font-size: 18px; line-height: 26px; }
.bi-review-intro p { margin: 0; color: #667085; font-size: 12px; line-height: 18px; white-space: nowrap; }

@media (max-width: 760px) {
  .bi-review-intro { align-items: flex-start; flex-direction: column; gap: 3px; }
  .bi-review-intro p { white-space: normal; }
}
</style>
