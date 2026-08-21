<script setup lang="ts">
import { computed, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import { ElMessage } from '../element-plus-services';
import { isUnauthorizedError } from '../api-client/request';
import { api } from '../api';
import PageStateShell from '../components/base/PageStateShell.vue';
import DashboardChartCard from '../components/dashboard/DashboardChartCard.vue';
import DashboardMetricCard from '../components/dashboard/DashboardMetricCard.vue';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import { buildAnalyticsDetailRoute } from '../components/dashboard/detail-view-routes';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import type {
  AnalyticsDashboardChart,
  AnalyticsDashboardDetailAction,
  AnalyticsDashboardMetric,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRule,
  AnalyticsDashboardRulesResponse,
  OptionItemResponse,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';

const DASHBOARD_KEY = 'quality-rd';
const router = useRouter();
const initialized = ref(false);
const loading = ref(false);
const exportLoadingKey = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const codeReviewSourceOptions = ref<OptionItemResponse[]>([]);
const selectedProjectName = ref('');
const codeReviewSource = ref('cc');
const dashboard = ref<AnalyticsDashboardResponse | null>(null);
const rulesResponse = ref<AnalyticsDashboardRulesResponse | null>(null);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);

type PageLoadResult = 'success' | 'unauthorized' | 'failed';

const pageReady = computed(() => initialized.value);
const rulesByKey = computed(() => new Map(
  (rulesResponse.value?.rules ?? []).map((rule) => [rule.key, rule]),
));

function dashboardQuery() {
  return {
    projectName: selectedProjectName.value,
    codeReviewSource: codeReviewSource.value,
  };
}

function inheritedRouteParameters() {
  return {
    projectName: selectedProjectName.value,
    codeReviewSource: codeReviewSource.value,
  };
}

async function loadFilterOptions() {
  const options = await api.getQualityBoardRdFilterOptions();
  projectOptions.value = options.projectOptions;
  codeReviewSourceOptions.value = options.codeReviewSourceOptions;
  selectedProjectName.value = selectedProjectName.value
    || options.defaultProjectName
    || options.projectOptions[0]?.value
    || '';
  if (!codeReviewSourceOptions.value.some((option) => option.value === codeReviewSource.value)) {
    codeReviewSource.value = codeReviewSourceOptions.value[0]?.value || 'cc';
  }
}

async function loadDashboardData() {
  const [nextDashboard, nextRules] = await Promise.all([
    api.getDashboard(DASHBOARD_KEY, dashboardQuery()),
    api.getRules(DASHBOARD_KEY, dashboardQuery()),
  ]);
  dashboard.value = nextDashboard;
  rulesResponse.value = nextRules;
}

async function loadPage(): Promise<PageLoadResult> {
  loading.value = true;
  try {
    await loadFilterOptions();
    await loadDashboardData();
    return 'success';
  } catch (error) {
    console.warn('研发质量看板加载失败', error);
    return isUnauthorizedError(error) ? 'unauthorized' : 'failed';
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleRefresh() {
  const result = await loadPage();
  if (result === 'success') {
    ElMessage.success('研发质量看板已刷新');
    return;
  }
  if (result === 'failed') {
    ElMessage.warning('研发质量看板加载失败');
  }
}

async function handleFilterChange() {
  loading.value = true;
  try {
    await loadDashboardData();
  } catch (error) {
    console.warn('研发质量看板筛选失败', error);
    if (!isUnauthorizedError(error)) {
      ElMessage.error('研发质量看板加载失败');
    }
  } finally {
    loading.value = false;
  }
}

function openDetail(action: AnalyticsDashboardDetailAction | null | undefined) {
  if (!action) {
    return;
  }
  try {
    void router.push(buildAnalyticsDetailRoute(
      DASHBOARD_KEY,
      action,
      inheritedRouteParameters(),
    ));
  } catch (error) {
    console.warn('看板详情入口不可用', error);
    ElMessage.warning(getErrorMessage(error, '看板详情入口不可用'));
  }
}

function handleMetricTitleClick(metric: AnalyticsDashboardMetric) {
  openDetail(metric.detail);
}

function handleChartTitleClick(chart: AnalyticsDashboardChart) {
  openDetail(chart.detail);
}

function handleChartPointClick(point: EChartPointClickEvent, chart: AnalyticsDashboardChart) {
  if (!chart.detail || !Object.keys(point.detailParams).length) {
    return;
  }
  openDetail({
    ...chart.detail,
    params: {
      ...chart.detail.params,
      ...point.detailParams,
    },
  });
}

function handleRuleClick(rule: AnalyticsDashboardRule | null) {
  if (!rule) {
    return;
  }
  selectedRule.value = rule;
  ruleDrawerVisible.value = true;
}

async function handleExport(
  exportKey: string | undefined,
  fallbackFilename: string,
) {
  if (!exportKey) {
    return;
  }
  exportLoadingKey.value = exportKey;
  try {
    const response = await api.export(DASHBOARD_KEY, exportKey, dashboardQuery());
    downloadBlob(response.blob, response.filename || fallbackFilename);
    ElMessage.success('数据已导出');
  } catch (error) {
    console.warn('研发质量看板导出失败', error);
    ElMessage.error('数据导出失败');
  } finally {
    exportLoadingKey.value = '';
  }
}

function handleMetricExport(metric: AnalyticsDashboardMetric) {
  void handleExport(metric.export?.exportKey, `${metric.title}.xlsx`);
}

function handleChartExport(chart: AnalyticsDashboardChart) {
  void handleExport(chart.export?.exportKey, `${chart.title}.xlsx`);
}

void loadPage().then((result) => {
  if (result === 'failed') {
    ElMessage.warning('研发质量看板加载失败');
  }
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-rd">
      <header class="quality-board-rd__hero">
        <div class="quality-board-rd__heading">
          <div class="quality-board-rd__eyebrow">QUALITY SIGNALS · 研发质量</div>
          <h2>{{ dashboard?.title || '研发质量看板' }}</h2>
          <p>{{ dashboard?.subtitle || '汇总评审、代码走查、集成测试与系统测试质量指标。' }}</p>
        </div>
        <div class="quality-board-rd__actions">
          <el-select
            v-model="selectedProjectName"
            class="quality-board-rd__project-select"
            filterable
            placeholder="选择项目"
            :disabled="loading"
            @change="handleFilterChange"
          >
            <el-option
              v-for="item in projectOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-button
            class="app-action-button app-action-button--refresh"
            :icon="Refresh"
            :loading="loading"
            @click="handleRefresh"
          >
            刷新
          </el-button>
        </div>
      </header>

      <section class="quality-board-rd__metrics" aria-label="研发质量指标">
        <DashboardMetricCard
          v-for="metric in dashboard?.metrics ?? []"
          :key="metric.key"
          :metric="metric"
          :rule="metric.ruleKey ? rulesByKey.get(metric.ruleKey) : null"
          @title-click="handleMetricTitleClick"
          @rule-click="handleRuleClick"
          @export="handleMetricExport"
        />
      </section>

      <section class="quality-board-rd__section-head">
        <div>
          <span>QUALITY ANALYTICS</span>
          <h3>质量专题分析</h3>
        </div>
        <el-radio-group
          v-if="codeReviewSourceOptions.length > 1"
          v-model="codeReviewSource"
          size="small"
          :disabled="loading"
          @change="handleFilterChange"
        >
          <el-radio-button
            v-for="option in codeReviewSourceOptions"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section class="quality-board-rd__charts" aria-label="研发质量图表">
        <DashboardChartCard
          v-for="chart in dashboard?.charts ?? []"
          :key="chart.key"
          :chart="chart"
          :rule="chart.ruleKey ? rulesByKey.get(chart.ruleKey) : null"
          :loading="loading"
          :height="360"
          @title-click="handleChartTitleClick"
          @point-click="handleChartPointClick"
          @rule-click="handleRuleClick"
          @export="handleChartExport"
        />
      </section>
    </section>

    <DashboardRuleDrawer v-model="ruleDrawerVisible" :rule="selectedRule" />
  </PageStateShell>
</template>

<style scoped>
.quality-board-rd {
  --board-ink: #172033;
  --board-muted: #64748b;
  display: grid;
  gap: 20px;
}

.quality-board-rd__hero {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  overflow: hidden;
  padding: 26px 28px;
  border: 1px solid #dfe7f1;
  border-radius: 18px;
  background:
    radial-gradient(circle at 92% 18%, rgb(37 99 235 / 14%), transparent 30%),
    linear-gradient(135deg, #fbfdff 0%, #f2f7ff 100%);
}

.quality-board-rd__heading,
.quality-board-rd__actions {
  position: relative;
  z-index: 1;
}

.quality-board-rd__eyebrow,
.quality-board-rd__section-head span {
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.quality-board-rd__hero h2 {
  margin: 7px 0 5px;
  color: var(--board-ink);
  font-size: 27px;
  letter-spacing: -0.02em;
}

.quality-board-rd__hero p {
  margin: 0;
  color: var(--board-muted);
  line-height: 1.7;
}

.quality-board-rd__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quality-board-rd__project-select {
  width: 220px;
}

.quality-board-rd__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.quality-board-rd__section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 2px 2px 0;
}

.quality-board-rd__section-head h3 {
  margin: 4px 0 0;
  color: var(--board-ink);
  font-size: 20px;
}

.quality-board-rd__charts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 1200px) {
  .quality-board-rd__metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .quality-board-rd__charts {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .quality-board-rd__hero {
    align-items: stretch;
    flex-direction: column;
  }

  .quality-board-rd__actions {
    flex-wrap: wrap;
  }

  .quality-board-rd__project-select {
    width: 100%;
  }

  .quality-board-rd__metrics {
    grid-template-columns: 1fr;
  }
}
</style>
