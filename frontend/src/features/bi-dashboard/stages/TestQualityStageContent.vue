<script setup lang="ts">
import { computed, ref, watch, type PropType } from 'vue';
import { TestQualityAttainmentChart } from '../charts/types';
import type { TestAttainmentRow } from '../charts/chart-data';
import BiChartPanel from '../components/BiChartPanel.vue';
import type { BiSortOrder } from '../components/BiChartSortControl.vue';
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
const moduleSort = ref('status');
const moduleSortOrder = ref<BiSortOrder>('asc');
const featureSort = ref('status');
const featureSortOrder = ref<BiSortOrder>('asc');

const moduleSortOptions = [
  { label: '异常优先', value: 'status' },
  { label: '通过率', value: 'rate' },
  { label: '统计总数', value: 'total' },
  { label: '达标数量', value: 'attained' },
  { label: '模块名称', value: 'name' },
];
const featureSortOptions = [
  { label: '异常优先', value: 'status' },
  { label: '通过率', value: 'rate' },
  { label: '功能名称', value: 'name' },
];

const allModules = computed<TestAttainmentRow[]>(() => (props.response.data?.modules ?? []).map((item) => ({
  id: item.moduleId,
  name: item.moduleName,
  passRate: item.attainment.passRate,
  targetRate: item.attainment.targetRate,
  counts: chartCounts(item.attainment),
  achieved: item.attainment.achieved,
})));

const modules = computed<TestAttainmentRow[]>(() => {
  return allModules.value
    .slice()
    .sort(sortTestRows(moduleSort.value, moduleSortOrder.value));
});

const functions = computed<TestAttainmentRow[]>(() => {
  return (props.response.data?.functions ?? [])
    .filter((item) => item.moduleId === selectedModuleId.value)
    .map((item) => ({
      id: item.functionId,
      name: item.functionName,
      passRate: item.attainment.passRate,
      targetRate: item.attainment.targetRate,
      counts: chartCounts(item.attainment),
      achieved: item.attainment.achieved,
    }))
    .sort(sortTestRows(featureSort.value, featureSortOrder.value));
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

function sortTestRows(sortBy: string, order: BiSortOrder) {
  return (left: TestAttainmentRow, right: TestAttainmentRow): number => {
    if (sortBy === 'rate') {
      const cmp = (left.passRate ?? 101) - (right.passRate ?? 101);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'total') {
      const cmp = (left.counts?.total ?? 0) - (right.counts?.total ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'attained') {
      const cmp = (left.counts?.attained ?? 0) - (right.counts?.attained ?? 0);
      return order === 'asc' ? cmp : -cmp;
    }
    if (sortBy === 'name') {
      const cmp = left.name.localeCompare(right.name, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const statusCmp = Number(left.achieved ?? true) - Number(right.achieved ?? true);
    if (statusCmp !== 0) return statusCmp;
    const cmp = (left.passRate ?? 101) - (right.passRate ?? 101);
    return order === 'asc' ? cmp : -cmp;
  };
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
        v-model:sort="moduleSort"
        v-model:order="moduleSortOrder"
        :sort-options="moduleSortOptions"
        @point-click="selectModuleFromChart"
      />
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
        v-model:sort="featureSort"
        v-model:order="featureSortOrder"
        :sort-options="featureSortOptions"
      >
        <template #actions>
          <el-select v-model="selectedModuleId" class="bi-module-select" size="small" filterable placeholder="选择模块" aria-label="选择功能所属模块">
            <el-option v-for="module in allModules" :key="module.id" :label="module.name" :value="module.id" />
          </el-select>
        </template>
      </BiChartPanel>
    </div>
  </div>
</template>

<style scoped>
.bi-module-select {
  width: 150px;
}

:deep(.bi-module-select .el-select__wrapper) {
  min-height: 28px;
  height: 28px;
  padding: 0 6px;
  background: #f8fafc;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  box-shadow: none !important;
  transition: all 0.2s ease;
}

:deep(.bi-module-select .el-select__wrapper:hover) {
  border-color: #cbd5e1;
  background: #f1f5f9;
}

:deep(.bi-module-select .el-select__selected-item) {
  font-size: 12px;
  color: #475467;
}

@media (max-width: 760px) {
  .bi-module-select { width: 100%; }
}
</style>
