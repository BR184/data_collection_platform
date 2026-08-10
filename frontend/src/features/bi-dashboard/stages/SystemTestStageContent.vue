<script setup lang="ts">
import { computed, ref, type PropType } from 'vue';
import type { CategorySeriesData, DelayHeatmapData, DeveloperWorkloadRow, ModuleRepairRow, NamedValue, OverlayBarRow, RoundQualityRow } from '../charts/chart-data';
import {
  DefectCauseBreakdownChart,
  DelayHeatmapChart,
  DeveloperWorkloadChart,
  DistributionDonutChart,
  ModuleRepairMatrixChart,
  OverlayCategoryBarChart,
  QualityRoundTrackChart,
  StackedCategoryBarChart,
} from '../charts/types';
import { BI_PALETTE } from '../charts/palette';
import BiAnalysisToolbar, { type BiToolbarOption } from '../components/BiAnalysisToolbar.vue';
import BiChartPanel from '../components/BiChartPanel.vue';
import BiMetricStrip, { type BiMetricItem } from '../components/BiMetricStrip.vue';
import BiQualityTargetPanel, { type BiQualityTargetItem } from '../components/BiQualityTargetPanel.vue';
import { formatNumber, formatPercent, metricStatus, sectionPresentation, systemTestTargetLabel } from '../data/presentation';
import { buildDefectCauseBreakdownData, buildDelayHeatmapData } from '../data/system-test-presentation';
import type { BiPageResponse, BiSystemTestPageData } from '../data/types';

const props = defineProps({
  response: { type: Object as PropType<BiPageResponse<BiSystemTestPageData>>, required: true },
  productVersionId: { type: Number, required: true },
});

const roundChart = new QualityRoundTrackChart();
const donutChart = new DistributionDonutChart();
const stackedChart = new StackedCategoryBarChart();
const repairChart = new ModuleRepairMatrixChart();
const overlayChart = new OverlayCategoryBarChart();
const causeBreakdownChart = new DefectCauseBreakdownChart();
const delayChart = new DelayHeatmapChart();
const developerChart = new DeveloperWorkloadChart();
const causeChartHeight = 432;
const data = computed(() => props.response.data);

const listOptions: BiToolbarOption[] = [
  { label: '显示全部', value: 'all' },
  { label: '显示 10 项', value: '10' },
  { label: '显示 20 项', value: '20' },
];
const repairSortOptions: BiToolbarOption[] = [
  { label: '修复率升序', value: 'rateAsc' },
  { label: '未修复数降序', value: 'openDesc' },
  { label: '模块名称', value: 'nameAsc' },
];
const developerSortOptions: BiToolbarOption[] = [
  { label: '待修复数降序', value: 'openDesc' },
  { label: '缺陷总数降序', value: 'totalDesc' },
  { label: '修复率升序', value: 'rateAsc' },
  { label: '人员名称', value: 'nameAsc' },
];
const developerStatusOptions: BiToolbarOption[] = [
  { label: '全部指派人', value: 'all' },
  { label: '存在待修复', value: 'open' },
];
const repairLimit = ref<string>('all');
const repairSort = ref('rateAsc');
const repairSearch = ref('');
const developerLimit = ref<string>('all');
const developerSort = ref('openDesc');
const developerSearch = ref('');
const developerStatus = ref('all');

const targetMetrics = computed<BiQualityTargetItem[]>(() => (data.value?.qualityTargets ?? []).map((item) => ({
  key: item.key,
  label: systemTestTargetLabel(item.key, item.label),
  value: item.status === 'NOT_APPLICABLE' ? '不适用' : formatPercent(item.fixRate),
  target: `目标 ${formatPercent(item.targetRate)}`,
  status: metricStatus(item.achieved),
  statusLabel: item.status === 'NOT_APPLICABLE'
    ? '不适用'
    : item.achieved == null ? '不可计算' : item.achieved ? '已达标' : '未达标',
})));

const overviewMetrics = computed<BiMetricItem[]>(() => {
  const overview = data.value?.overview;
  if (!overview) return [];
  return [
    { label: '累计发现缺陷数', value: formatNumber(overview.totalCount) },
    { label: '已修复缺陷数', value: formatNumber(overview.fixedCount) },
    { label: '当前未修复数', value: formatNumber(overview.openCount), status: overview.openCount > 0 ? 'danger' : 'success' },
    { label: '整体修复率', value: formatPercent(overview.fixRate) },
  ];
});

const rounds = computed<RoundQualityRow[]>(() => (data.value?.rounds ?? []).map((item) => ({
  name: item.roundName,
  levelOne: item.levelOneCount,
  levelTwo: item.levelTwoCount,
  levelThree: item.levelThreeCount,
  submitted: item.submittedCount,
  closed: item.closedCount,
  open: item.openCount,
  closeRate: item.closeRate,
})));

const severity = computed<NamedValue[]>(() => {
  const value = data.value?.severity;
  return value ? [
    { name: '一级', value: value.levelOneCount, color: BI_PALETTE.red },
    { name: '二级', value: value.levelTwoCount, color: BI_PALETTE.orange },
    { name: '三级', value: value.levelThreeCount, color: BI_PALETTE.blue },
  ] : [];
});

const visibleModules = computed(() => {
  const search = repairSearch.value.trim().toLowerCase();
  const rows = (data.value?.modules ?? [])
    .filter((item) => !search || item.module.displayName.toLowerCase().includes(search))
    .slice()
    .sort((left, right) => {
      if (repairSort.value === 'openDesc') return right.openCount - left.openCount || left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      if (repairSort.value === 'nameAsc') return left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      return (left.fixRate ?? 101) - (right.fixRate ?? 101) || left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
    });
  return limitRows(rows, repairLimit.value);
});

const moduleSeverity = computed<CategorySeriesData>(() => ({
  categories: visibleModules.value.map((item) => item.module.displayName),
  series: [
    { name: '一级缺陷', values: visibleModules.value.map((item) => item.levelOneCount), color: BI_PALETTE.red },
    { name: '二级缺陷', values: visibleModules.value.map((item) => item.levelTwoCount), color: BI_PALETTE.orange },
    { name: '三级缺陷', values: visibleModules.value.map((item) => item.levelThreeCount), color: BI_PALETTE.blue },
  ],
}));

const repair = computed<ModuleRepairRow[]>(() => visibleModules.value.map((item) => ({
  name: item.module.displayName,
  fixRate: item.fixRate,
  levelOneRate: item.levelOneFixRate,
  p1Rate: item.p1FixRate,
  p2Rate: item.p2FixRate,
})));

const overlay = computed<OverlayBarRow[]>(() => visibleModules.value.map((item) => ({
  name: item.module.displayName,
  total: item.totalCount,
  overlay: item.openCount,
})));

const causeCategories = computed<NamedValue[]>(() => (data.value?.causeCategories ?? []).map((item, index) => ({
  name: item.categoryName,
  value: item.count,
  color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red, BI_PALETTE.slate][index % 6],
})));
const causeSubcategories = computed(() => buildDefectCauseBreakdownData(data.value?.causeSubcategories ?? []));
const delay = computed<DelayHeatmapData>(() => buildDelayHeatmapData(data.value?.delays ?? []));

const developers = computed<DeveloperWorkloadRow[]>(() => {
  const search = developerSearch.value.trim().toLowerCase();
  const rows = (data.value?.developers ?? [])
    .filter((item) => (!search || item.assignee.displayName.toLowerCase().includes(search)) && (developerStatus.value === 'all' || item.openCount > 0))
    .slice()
    .sort((left, right) => {
      if (developerSort.value === 'totalDesc') return right.totalCount - left.totalCount || right.openCount - left.openCount;
      if (developerSort.value === 'rateAsc') return repairRate(left.fixedCount, left.totalCount) - repairRate(right.fixedCount, right.totalCount);
      if (developerSort.value === 'nameAsc') return left.assignee.displayName.localeCompare(right.assignee.displayName, 'zh-CN');
      return right.openCount - left.openCount || right.totalCount - left.totalCount;
    });
  return limitRows(rows, developerLimit.value).map((item) => ({ name: item.assignee.displayName, total: item.totalCount, open: item.openCount, fixed: item.fixedCount }));
});

function limitRows<T>(rows: T[], limit: string): T[] {
  const count = Number(limit);
  return Number.isSafeInteger(count) && count > 0 ? rows.slice(0, count) : rows;
}

function repairRate(fixed: number, total: number): number {
  return total > 0 ? fixed / total : 101;
}

function setRepairLimit(value: string | number): void { repairLimit.value = String(value); }
function setDeveloperLimit(value: string | number): void { developerLimit.value = String(value); }
</script>

<template>
  <div class="bi-stage-stack">
    <BiQualityTargetPanel v-if="targetMetrics.length" :items="targetMetrics" />
    <BiMetricStrip v-if="overviewMetrics.length" variant="supporting" :items="overviewMetrics" />

    <div class="bi-charts-grid">
      <BiChartPanel
        title="系统测试各轮次缺陷修复情况"
        :chart="roundChart"
        :data="rounds"
        :height="272"
        variant="summary"
        layout="primary"
        :status="sectionPresentation(response, 'round-quality').status"
        :status-message="sectionPresentation(response, 'round-quality').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />
      <BiChartPanel
        title="缺陷严重度分布"
        :chart="donutChart"
        :data="severity"
        :height="272"
        variant="pie"
        layout="secondary"
        :status="sectionPresentation(response, 'severity-distribution').status"
        :status-message="sectionPresentation(response, 'severity-distribution').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />

      <BiChartPanel
        title="各模块系统测试修复率达成情况"
        :chart="repairChart"
        :data="repair"
        :height="430"
        variant="analysis"
        layout="primary"
        :status="sectionPresentation(response, 'module-repair-targets').status"
        :status-message="sectionPresentation(response, 'module-repair-targets').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      >
        <template #actions>
          <BiAnalysisToolbar
            :limit="repairLimit"
            :sort="repairSort"
            :search="repairSearch"
            :limit-options="listOptions"
            :sort-options="repairSortOptions"
            search-placeholder="搜索模块"
            @update:limit="setRepairLimit"
            @update:sort="repairSort = $event"
            @update:search="repairSearch = $event"
          />
        </template>
      </BiChartPanel>
      <BiChartPanel
        title="申请延期缺陷情况"
        :chart="delayChart"
        :data="delay"
        :height="430"
        variant="analysis"
        layout="secondary"
        :status="sectionPresentation(response, 'delay-analysis').status"
        :status-message="sectionPresentation(response, 'delay-analysis').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />

      <BiChartPanel
        title="各模块缺陷级别"
        :chart="stackedChart"
        :data="moduleSeverity"
        :height="430"
        variant="analysis"
        layout="wide-only"
        :status="sectionPresentation(response, 'module-quality').status"
        :status-message="sectionPresentation(response, 'module-quality').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />
      <BiChartPanel
        title="各模块累计发现与当前未修复缺陷对比"
        :chart="overlayChart"
        :data="overlay"
        :height="430"
        variant="analysis"
        layout="wide-only"
        :status="sectionPresentation(response, 'module-quality').status"
        :status-message="sectionPresentation(response, 'module-quality').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />

      <BiChartPanel
        class="bi-cause-chart-panel"
        title="系统测试缺陷原因大类分布"
        :chart="donutChart"
        :data="causeCategories"
        :height="causeChartHeight"
        variant="pie"
        layout="compact"
        :status="sectionPresentation(response, 'cause-distribution').status"
        :status-message="sectionPresentation(response, 'cause-distribution').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />
      <BiChartPanel
        class="bi-cause-chart-panel"
        title="系统测试缺陷原因子类分布"
        :chart="causeBreakdownChart"
        :data="causeSubcategories"
        :height="causeChartHeight"
        variant="analysis"
        layout="wide"
        :status="sectionPresentation(response, 'cause-distribution').status"
        :status-message="sectionPresentation(response, 'cause-distribution').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      />

      <BiChartPanel
        title="按指派人统计缺陷数"
        :chart="developerChart"
        :data="developers"
        :height="430"
        variant="analysis"
        layout="full"
        :status="sectionPresentation(response, 'developer-workload').status"
        :status-message="sectionPresentation(response, 'developer-workload').message"
        :product-version-id="productVersionId"
        page-key="system-test"
        :source-version="response.sourceVersion"
      >
        <template #actions>
          <BiAnalysisToolbar
            :limit="developerLimit"
            :sort="developerSort"
            :search="developerSearch"
            :status="developerStatus"
            :limit-options="listOptions"
            :sort-options="developerSortOptions"
            :status-options="developerStatusOptions"
            search-placeholder="搜索指派人"
            @update:limit="setDeveloperLimit"
            @update:sort="developerSort = $event"
            @update:search="developerSearch = $event"
            @update:status="developerStatus = $event"
          />
        </template>
      </BiChartPanel>
    </div>
  </div>
</template>

<style scoped>
.bi-cause-chart-panel {
  height: 100%;
  align-self: stretch;
}
</style>
