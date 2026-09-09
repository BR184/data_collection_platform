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
import BiChartPanel from '../components/BiChartPanel.vue';
import type { BiSortOrder } from '../components/BiChartSortControl.vue';
import type { BiMetricItem } from '../components/BiMetricStrip.vue';
import type { BiQualityTargetItem } from '../components/BiQualityTargetPanel.vue';
import { formatNumber, formatPercent, metricStatus, sectionPresentation, systemTestTargetLabel } from '../data/presentation';
import { sortDeveloperWorkloadRows, sortNamedValues, sortRoundQualityRows } from '../data/sorting';
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

// 各图表排序状态
const roundSort = ref('name');
const roundSortOrder = ref<BiSortOrder>('asc');
const severitySort = ref('count');
const severitySortOrder = ref<BiSortOrder>('desc');
const repairSort = ref('status');
const repairSortOrder = ref<BiSortOrder>('asc');
const moduleSeveritySort = ref('total');
const moduleSeveritySortOrder = ref<BiSortOrder>('desc');
const overlaySort = ref('open');
const overlaySortOrder = ref<BiSortOrder>('desc');
const causeCategorySort = ref('count');
const causeCategorySortOrder = ref<BiSortOrder>('desc');
const developerSort = ref('open');
const developerSortOrder = ref<BiSortOrder>('desc');

const roundSortOptions = [
  { label: '轮次顺序', value: 'name' },
  { label: '提交缺陷总数', value: 'submitted' },
  { label: '缺陷关闭率', value: 'closeRate' },
  { label: '未关闭缺陷数', value: 'open' },
];
const countNameSortOptions = [
  { label: '数量排序', value: 'count' },
  { label: '名称排序', value: 'name' },
];
const repairSortOptions = [
  { label: '异常优先', value: 'status' },
  { label: '整体修复率', value: 'rate' },
  { label: '未修复数', value: 'open' },
  { label: '累计总数', value: 'total' },
  { label: '模块名称', value: 'name' },
];
const moduleSeveritySortOptions = [
  { label: '缺陷总数', value: 'total' },
  { label: '未修复数', value: 'open' },
  { label: '模块名称', value: 'name' },
];
const overlaySortOptions = [
  { label: '当前未修复', value: 'open' },
  { label: '累计发现总数', value: 'total' },
  { label: '模块名称', value: 'name' },
];
const developerSortOptions = [
  { label: '待修复数', value: 'open' },
  { label: '缺陷总数', value: 'total' },
  { label: '已修复数', value: 'fixed' },
  { label: '修复率', value: 'rate' },
  { label: '人员姓名', value: 'name' },
];

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

const rounds = computed<RoundQualityRow[]>(() => {
  const raw = (data.value?.rounds ?? []).map((item) => ({
    name: item.roundName,
    levelOne: item.levelOneCount,
    levelTwo: item.levelTwoCount,
    levelThree: item.levelThreeCount,
    submitted: item.submittedCount,
    closed: item.closedCount,
    open: item.openCount,
    closeRate: item.closeRate,
  }));
  return sortRoundQualityRows(raw, roundSort.value, roundSortOrder.value);
});

const severity = computed<NamedValue[]>(() => {
  const value = data.value?.severity;
  const raw = value ? [
    { name: '一级', value: value.levelOneCount, color: BI_PALETTE.red },
    { name: '二级', value: value.levelTwoCount, color: BI_PALETTE.orange },
    { name: '三级', value: value.levelThreeCount, color: BI_PALETTE.blue },
  ] : [];
  return sortNamedValues(raw, severitySort.value, severitySortOrder.value);
});

const visibleModules = computed(() => {
  const rows = (data.value?.modules ?? []).slice().sort((left, right) => {
    const order = repairSortOrder.value;
    if (repairSort.value === 'open') {
      const cmp = left.openCount - right.openCount;
      return order === 'asc' ? cmp : -cmp || left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
    }
    if (repairSort.value === 'total') {
      const cmp = left.totalCount - right.totalCount;
      return order === 'asc' ? cmp : -cmp || left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
    }
    if (repairSort.value === 'rate') {
      const cmp = (left.fixRate ?? 101) - (right.fixRate ?? 101);
      return order === 'asc' ? cmp : -cmp || left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
    }
    if (repairSort.value === 'name') {
      const cmp = left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    // 默认 status 异常优先
    const achievedA = left.fixRate != null && left.fixRate >= 0.95;
    const achievedB = right.fixRate != null && right.fixRate >= 0.95;
    const statusCmp = Number(achievedA) - Number(achievedB);
    if (statusCmp !== 0) return statusCmp;
    const cmp = (left.fixRate ?? 101) - (right.fixRate ?? 101);
    return order === 'asc' ? cmp : -cmp;
  });
  return rows;
});

const moduleSeverity = computed<CategorySeriesData>(() => {
  const rows = (data.value?.modules ?? []).slice().sort((left, right) => {
    const order = moduleSeveritySortOrder.value;
    if (moduleSeveritySort.value === 'open') {
      const cmp = left.openCount - right.openCount;
      return order === 'asc' ? cmp : -cmp;
    }
    if (moduleSeveritySort.value === 'name') {
      const cmp = left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const cmp = left.totalCount - right.totalCount;
    return order === 'asc' ? cmp : -cmp;
  });
  return {
    categories: rows.map((item) => item.module.displayName),
    series: [
      { name: '一级缺陷', values: rows.map((item) => item.levelOneCount), color: BI_PALETTE.red },
      { name: '二级缺陷', values: rows.map((item) => item.levelTwoCount), color: BI_PALETTE.orange },
      { name: '三级缺陷', values: rows.map((item) => item.levelThreeCount), color: BI_PALETTE.blue },
    ],
  };
});

const repair = computed<ModuleRepairRow[]>(() => visibleModules.value.map((item) => ({
  name: item.module.displayName,
  fixRate: item.fixRate,
  levelOneRate: item.levelOneFixRate,
  p1Rate: item.p1FixRate,
  p2Rate: item.p2FixRate,
  openCount: item.openCount,
  totalCount: item.totalCount,
})));

const overlay = computed<OverlayBarRow[]>(() => {
  const rows = (data.value?.modules ?? []).slice().sort((left, right) => {
    const order = overlaySortOrder.value;
    if (overlaySort.value === 'total') {
      const cmp = left.totalCount - right.totalCount;
      return order === 'asc' ? cmp : -cmp;
    }
    if (overlaySort.value === 'name') {
      const cmp = left.module.displayName.localeCompare(right.module.displayName, 'zh-CN');
      return order === 'asc' ? cmp : -cmp;
    }
    const cmp = left.openCount - right.openCount;
    return order === 'asc' ? cmp : -cmp;
  });
  return rows.map((item) => ({
    name: item.module.displayName,
    total: item.totalCount,
    overlay: item.openCount,
  }));
});

const causeCategories = computed<NamedValue[]>(() => {
  const raw = (data.value?.causeCategories ?? []).map((item, index) => ({
    name: item.categoryName,
    value: item.count,
    color: [BI_PALETTE.blue, BI_PALETTE.teal, BI_PALETTE.green, BI_PALETTE.orange, BI_PALETTE.red, BI_PALETTE.slate][index % 6],
  }));
  return sortNamedValues(raw, causeCategorySort.value, causeCategorySortOrder.value);
});
const causeSubcategories = computed(() => buildDefectCauseBreakdownData(data.value?.causeSubcategories ?? []));
const delay = computed<DelayHeatmapData>(() => buildDelayHeatmapData(data.value?.delays ?? []));

const developers = computed<DeveloperWorkloadRow[]>(() => {
  const raw = (data.value?.developers ?? []).map((item) => ({
    name: item.assignee.displayName,
    total: item.totalCount,
    open: item.openCount,
    fixed: item.fixedCount,
  }));
  return sortDeveloperWorkloadRows(raw, developerSort.value, developerSortOrder.value);
});
</script>

<template>
  <div class="bi-stage-stack">
    <!-- 顶部 7 个指标单行化横向整合：左侧 3 项质量目标，右侧 4 项缺陷概览 -->
    <section v-if="targetMetrics.length || overviewMetrics.length" class="bi-system-test-metric-bar" aria-label="系统测试质量指标及概览">
      <!-- 左侧：质量目标 (3 项) -->
      <div v-if="targetMetrics.length" class="bi-metric-group bi-metric-group--targets">
        <article
          v-for="item in targetMetrics"
          :key="item.key"
          class="bi-strip-cell bi-strip-cell--target"
          :class="`is-${item.status}`"
        >
          <div class="bi-cell-head">
            <span class="bi-cell-label">{{ item.label }}</span>
            <b class="bi-target-badge" :class="`is-${item.status}`">{{ item.statusLabel }}</b>
          </div>
          <strong class="bi-cell-value">{{ item.value }}</strong>
          <span class="bi-target-subtext">{{ item.target }}</span>
        </article>
      </div>

      <!-- 垂直细分割线 -->
      <div v-if="targetMetrics.length && overviewMetrics.length" class="bi-metric-bar-divider" role="separator" />

      <!-- 右侧：测试概览 (4 项) -->
      <div v-if="overviewMetrics.length" class="bi-metric-group bi-metric-group--overview">
        <article
          v-for="item in overviewMetrics"
          :key="item.label"
          class="bi-strip-cell bi-strip-cell--overview"
          :class="`is-${item.status ?? 'neutral'}`"
        >
          <span class="bi-cell-label">{{ item.label }}</span>
          <strong class="bi-cell-value">{{ item.value }}</strong>
          <span class="bi-cell-placeholder">&nbsp;</span>
        </article>
      </div>
    </section>

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
        v-model:sort="roundSort"
        v-model:order="roundSortOrder"
        :sort-options="roundSortOptions"
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
        v-model:sort="severitySort"
        v-model:order="severitySortOrder"
        :sort-options="countNameSortOptions"
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
        v-model:sort="repairSort"
        v-model:order="repairSortOrder"
        :sort-options="repairSortOptions"
      />
      <BiChartPanel
        title="系统测试缺陷延期情况"
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
        v-model:sort="moduleSeveritySort"
        v-model:order="moduleSeveritySortOrder"
        :sort-options="moduleSeveritySortOptions"
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
        v-model:sort="overlaySort"
        v-model:order="overlaySortOrder"
        :sort-options="overlaySortOptions"
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
        v-model:sort="causeCategorySort"
        v-model:order="causeCategorySortOrder"
        :sort-options="countNameSortOptions"
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
        v-model:sort="developerSort"
        v-model:order="developerSortOrder"
        :sort-options="developerSortOptions"
      />
    </div>
  </div>
</template>

<style scoped>
.bi-system-test-metric-bar {
  display: flex;
  align-items: stretch;
  border: 1px solid #e8edf4;
  border-radius: 6px;
  background: #ffffff;
  overflow: hidden;
  margin-bottom: 2px;
}

.bi-metric-group {
  display: flex;
  align-items: stretch;
  min-width: 0;
}

.bi-metric-group--targets {
  flex: 3 1 0;
}

.bi-metric-group--overview {
  flex: 4 1 0;
}

.bi-metric-bar-divider {
  width: 1px;
  background: #e2e8f0;
  margin: 8px 0;
  flex-shrink: 0;
}

.bi-strip-cell {
  flex: 1 1 0;
  min-width: 0;
  padding: 10px 14px 9px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  border-right: 1px solid #f0f4f9;
  position: relative;
}

.bi-strip-cell:last-child {
  border-right: 0;
}

.bi-strip-cell.is-success {
  background: rgba(145, 204, 117, 0.06);
}

.bi-strip-cell.is-danger {
  background: rgba(238, 102, 102, 0.05);
}

.bi-cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}

.bi-cell-label {
  color: #475467;
  font-size: 12px;
  line-height: 18px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bi-target-badge {
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 650;
  line-height: 18px;
  white-space: nowrap;
}

.bi-target-badge.is-success {
  background: rgba(145, 204, 117, 0.18);
  color: #47724f;
}

.bi-target-badge.is-danger {
  background: rgba(238, 102, 102, 0.16);
  color: #993e45;
}

.bi-target-badge.is-neutral {
  background: rgba(154, 159, 176, 0.16);
  color: #5b6570;
}

.bi-cell-value {
  display: block;
  margin-top: 3px;
  font-size: 23px;
  font-weight: 700;
  line-height: 28px;
  color: #344054;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bi-strip-cell--overview .bi-cell-value {
  color: #5470c6;
}

.bi-strip-cell.is-success .bi-cell-value {
  color: #47724f;
}

.bi-strip-cell.is-danger .bi-cell-value {
  color: #993e45;
}

.bi-target-subtext {
  margin-top: 2px;
  font-size: 11px;
  color: #667085;
  line-height: 16px;
  white-space: nowrap;
}

.bi-cell-placeholder {
  margin-top: 2px;
  font-size: 11px;
  line-height: 16px;
}

.bi-cause-chart-panel {
  height: 100%;
  align-self: stretch;
}

@media (max-width: 900px) {
  .bi-system-test-metric-bar {
    flex-direction: column;
  }
  .bi-metric-bar-divider {
    width: 100%;
    height: 1px;
    margin: 0;
  }
}
</style>
