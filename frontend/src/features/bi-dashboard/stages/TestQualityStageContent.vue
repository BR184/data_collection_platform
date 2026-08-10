<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import { TestQualityAttainmentChart } from '../charts/types';
import type { TestAttainmentRow } from '../charts/chart-data';
import BiAnalysisToolbar, { type BiToolbarOption } from '../components/BiAnalysisToolbar.vue';
import BiChartPanel from '../components/BiChartPanel.vue';
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import { formatNumber, formatPercent, metricStatus, sectionPresentation } from '../data/presentation';
import type { BiPageKey, BiPageResponse, BiTestAttainment, BiTestQualityPageData } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiTestQualityPageData>>, required: true },
  productVersionId: { type: Number, required: true },
  stageLabel: { type: String, required: true },
  pageKey: { type: String as PropType<BiPageKey>, required: true },
});

const chart = new TestQualityAttainmentChart();
const selectedModuleId = ref('');
const moduleLimit = ref('all');
const moduleSort = ref('statusAsc');
const moduleSearch = ref('');
const featureLimit = ref('all');
const featureSort = ref('statusAsc');
const featureSearch = ref('');
const limitOptions: BiToolbarOption[] = [
  { label: '显示全部', value: 'all' },
  { label: '显示 10 项', value: '10' },
  { label: '显示 20 项', value: '20' },
];
const sortOptions: BiToolbarOption[] = [
  { label: '未达标优先', value: 'statusAsc' },
  { label: '通过率升序', value: 'rateAsc' },
  { label: '统计数降序', value: 'countDesc' },
  { label: '名称排序', value: 'nameAsc' },
];
const featureSortOptions = sortOptions.filter((item) => item.value !== 'countDesc');

const allModules = computed<TestAttainmentRow[]>(() => (props.response.data?.modules ?? []).map((item) => ({
  id: item.moduleId,
  name: item.moduleName,
  passRate: item.attainment.passRate,
  targetRate: item.attainment.targetRate,
  counts: chartCounts(item.attainment),
  achieved: item.attainment.achieved,
})));

const modules = computed<TestAttainmentRow[]>(() => {
  const search = moduleSearch.value.trim().toLowerCase();
  const rows = allModules.value
    .filter((item) => !search || item.name.toLowerCase().includes(search) || item.id.toLowerCase().includes(search))
    .slice()
    .sort(sortRows(moduleSort.value));
  return limitRows(rows, moduleLimit.value);
});

const functions = computed<TestAttainmentRow[]>(() => {
  const search = featureSearch.value.trim().toLowerCase();
  const rows = (props.response.data?.functions ?? [])
    .filter((item) => item.moduleId === selectedModuleId.value)
    .map((item) => ({
      id: item.functionId,
      name: item.functionName,
      passRate: item.attainment.passRate,
      targetRate: item.attainment.targetRate,
      counts: chartCounts(item.attainment),
      achieved: item.attainment.achieved,
    }))
    .filter((item) => !search || item.name.toLowerCase().includes(search) || item.id.toLowerCase().includes(search))
    .sort(sortRows(featureSort.value));
  return limitRows(rows, featureLimit.value);
});

const metrics = computed<BiMetricItem[]>(() => {
  const overall = props.response.data?.overall;
  if (!overall) return [];
  const counts = overall.counts;
  return [
    { label: '整体通过率', value: formatPercent(overall.passRate), status: metricStatus(overall.achieved) },
    { label: '目标通过率', value: formatPercent(overall.targetRate), detail: '最低目标' },
    {
      label: counts ? countLabel(counts.unit) : '统计数量',
      value: counts ? `${formatNumber(counts.attainedCount)} / ${formatNumber(counts.totalCount)}` : '--',
      detail: counts ? '达标 / 统计' : 'CAT 未提供可验证计数',
    },
    { label: '达标状态', value: overall.achieved == null ? '不可计算' : overall.achieved ? '达标' : '未达标', detail: `目标 ≥ ${formatPercent(overall.targetRate)}`, status: metricStatus(overall.achieved) },
  ];
});

watch(allModules, (value) => {
  if (!value.some((item) => item.id === selectedModuleId.value)) {
    selectedModuleId.value = value[0]?.id ?? '';
  }
}, { immediate: true });

function sortRows(sort: string) {
  return (left: TestAttainmentRow, right: TestAttainmentRow): number => {
    if (sort === 'rateAsc') return (left.passRate ?? 101) - (right.passRate ?? 101) || left.name.localeCompare(right.name, 'zh-CN');
    if (sort === 'countDesc') return (right.counts?.total ?? -1) - (left.counts?.total ?? -1) || left.name.localeCompare(right.name, 'zh-CN');
    if (sort === 'nameAsc') return left.name.localeCompare(right.name, 'zh-CN');
    return Number(left.achieved ?? true) - Number(right.achieved ?? true) || (left.passRate ?? 101) - (right.passRate ?? 101);
  };
}

function limitRows<T>(rows: T[], limit: string): T[] {
  const count = Number(limit);
  return Number.isSafeInteger(count) && count > 0 ? rows.slice(0, count) : rows;
}

function chartCounts(attainment: BiTestAttainment): TestAttainmentRow['counts'] {
  if (!attainment.counts) return null;
  return {
    label: countLabel(attainment.counts.unit),
    attained: attainment.counts.attainedCount,
    total: attainment.counts.totalCount,
  };
}

function countLabel(unit: NonNullable<BiTestAttainment['counts']>['unit']): string {
  return unit === 'FUNCTION' ? '达标功能 / 统计功能' : '通过用例 / 执行用例';
}

function selectModuleFromChart(event: { dataIndex: number }): void {
  const module = modules.value[event.dataIndex];
  if (module) selectedModuleId.value = module.id;
}

function setModuleLimit(value: string | number): void { moduleLimit.value = String(value); }
function setFeatureLimit(value: string | number): void { featureLimit.value = String(value); }
</script>

<template>
  <div class="bi-stage-stack">
    <BiMetricStrip v-if="metrics.length" variant="quality" :items="metrics" />
    <div class="bi-charts-grid">
      <BiChartPanel
        :title="`各模块${stageLabel}达标情况`"
        subtitle="按模块比较通过率、目标线和达标功能 / 统计功能；点击模块定位下方功能"
        :chart="chart"
        :data="modules"
        :height="520"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'test-quality').status"
        :status-message="sectionPresentation(response, 'test-quality').message"
        :product-version-id="productVersionId"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
        @point-click="selectModuleFromChart"
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
        :title="selectedModuleId ? '模块下功能达标情况' : '功能达标情况'"
        :subtitle="selectedModuleId ? `当前模块：${allModules.find((item) => item.id === selectedModuleId)?.name ?? ''}` : '选择模块后查看功能级数据'"
        :chart="chart"
        :data="functions"
        :height="430"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'test-quality').status"
        :status-message="sectionPresentation(response, 'test-quality').message"
        :product-version-id="productVersionId"
        :page-key="pageKey"
        :source-version="response.sourceVersion"
      >
        <template #actions>
          <el-select v-model="selectedModuleId" class="bi-module-select" size="small" filterable placeholder="选择模块" aria-label="选择功能所属模块">
            <el-option v-for="module in allModules" :key="module.id" :label="module.name" :value="module.id" />
          </el-select>
          <BiAnalysisToolbar
            :limit="featureLimit"
            :sort="featureSort"
            :search="featureSearch"
            :limit-options="limitOptions"
            :sort-options="featureSortOptions"
            search-placeholder="搜索功能"
            @update:limit="setFeatureLimit"
            @update:sort="featureSort = $event"
            @update:search="featureSearch = $event"
          />
        </template>
      </BiChartPanel>
    </div>
  </div>
</template>

<style scoped>
.bi-module-select { width: 180px; }

@media (max-width: 760px) {
  .bi-module-select { width: 100%; }
}
</style>
