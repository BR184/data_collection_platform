<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useAcceptedPageView } from '../composables/useAcceptedPageView';
import { authState } from '../composables/auth-state';
import StatisticBoardFreshnessBanner from '../components/StatisticBoardFreshnessBanner.vue';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import { ElMessage } from '../element-plus-services';
import { isUnauthorizedError } from '../api-client/request';
import { api } from '../api';
import PageStateShell from '../components/base/PageStateShell.vue';
import DashboardChartCard from '../components/dashboard/DashboardChartCard.vue';
import DashboardMetricCard from '../components/dashboard/DashboardMetricCard.vue';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import { navigateAnalyticsDetail } from '../components/dashboard/analytics-read-navigation';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import type {
  AnalyticsDashboardChart,
  AnalyticsDashboardDetailAction,
  AnalyticsDashboardMetric,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRule,
  AnalyticsDashboardRulesResponse,
  OptionItemResponse,
  QualityBoardRdFilterOptionsResponse,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';

const DASHBOARD_KEY = 'quality-rd';
const router = useRouter();
const initialized = ref(false);
const loading = ref(false);
const pageLoadError = ref('');
const exportLoadingKey = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const codeReviewSourceOptions = ref<OptionItemResponse[]>([]);
const selectedProjectName = ref('');
const codeReviewSource = ref('cc');
const dashboard = ref<AnalyticsDashboardResponse | null>(null);
const rulesResponse = ref<AnalyticsDashboardRulesResponse | null>(null);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);
interface QualityRdFrame {
  dashboard: AnalyticsDashboardResponse;
  rules: AnalyticsDashboardRulesResponse;
  options: QualityBoardRdFilterOptionsResponse;
  query: ReturnType<typeof dashboardQuery>;
}
const acceptedPage = useAcceptedPageView<QualityRdFrame>({ pageKey: DASHBOARD_KEY,
  load: () => { throw new Error('需要捕获质量看板查询后准备'); } });
const readMetadata = computed(() => acceptedPage.accepted.value?.metadata);
acceptedPage.start();
onBeforeUnmount(acceptedPage.dispose);
watch(() => authState.currentUser?.username, acceptedPage.clearPrivateData);
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  dashboard.value = frame?.data.dashboard ?? null;
  rulesResponse.value = frame?.data.rules ?? null;
  projectOptions.value = frame?.data.options.projectOptions ?? [];
  codeReviewSourceOptions.value = frame?.data.options.codeReviewSourceOptions ?? [];
  if (frame) {
    selectedProjectName.value = frame.data.query.projectName;
    codeReviewSource.value = frame.data.query.codeReviewSource;
    if (ruleDrawerVisible.value && selectedRule.value) {
      selectedRule.value = frame.data.rules.rules.find(rule => rule.key === selectedRule.value?.key) ?? null;
    }
  } else { selectedRule.value = null; ruleDrawerVisible.value = false; }
}, { flush: 'sync' });

type PageLoadResult = 'success' | 'unauthorized' | 'failed' | 'superseded';

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

let loadSequence = 0;
async function loadPage(): Promise<PageLoadResult> {
  const ticket = ++loadSequence;
  loading.value = true;
  const requested = Object.freeze({ ...dashboardQuery() });
  pageLoadError.value = '';
  try {
    const loaded = await acceptedPage.prepare({}, async (_scope, read) => {
      const options = await api.getQualityBoardRdFilterOptions(read);
      const query = {
        projectName: requested.projectName || options.defaultProjectName || options.projectOptions[0]?.value || '',
        codeReviewSource: options.codeReviewSourceOptions.some(option => option.value === requested.codeReviewSource)
          ? requested.codeReviewSource : options.codeReviewSourceOptions[0]?.value || 'cc',
      };
      const [dashboard, rules] = await Promise.all([
        api.getDashboard(DASHBOARD_KEY, query, read), api.getRules(DASHBOARD_KEY, query, read),
      ]);
      return { options, query, dashboard, rules };
    });
    if (ticket !== loadSequence) return 'superseded';
    if (!loaded) throw acceptedPage.lastError.value ?? new Error('页面准备已取消');
    return 'success';
  } catch (error) { if (ticket !== loadSequence) return 'superseded';
    pageLoadError.value = getErrorMessage(error, acceptedPage.accepted.value
      ? '看板加载失败，已保留当前完整数据' : '尚无完整产出，请检查数据更新状态');
    return isUnauthorizedError(error) ? 'unauthorized' : 'failed';
  } finally {
    if (ticket === loadSequence) { loading.value = false; initialized.value = true;
    }
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
  const result = await loadPage();
  if (result === 'failed') ElMessage.error('研发质量看板加载失败，已保留当前完整数据');
}

async function openDetail(action: AnalyticsDashboardDetailAction | null | undefined) {
  if (!action) {
    return;
  }
  try {
    await navigateAnalyticsDetail(acceptedPage, router, DASHBOARD_KEY, action,
      frame => Object.fromEntries(Object.entries(frame.query).map(([key, value]) => [key, String(value)])));
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
    await acceptedPage.readAccepted((frame, read) => api.export(DASHBOARD_KEY, exportKey, frame.query, read),
      response => downloadBlob(response.blob, response.filename || fallbackFilename));
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
      <el-alert v-if="pageLoadError" :title="pageLoadError" type="error" :closable="false" show-icon />
      <StatisticBoardFreshnessBanner v-if="readMetadata" :data-as-of="readMetadata.dataAsOf"
        :pending-updates="readMetadata.pendingUpdates" :source-state="readMetadata.sourceState"
        :source-state-detail="readMetadata.sourceStateDetail" :escalated="readMetadata.escalated" />
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
