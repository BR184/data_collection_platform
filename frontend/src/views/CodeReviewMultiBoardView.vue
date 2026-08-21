<script setup lang="ts">
import { computed, ref } from 'vue';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import DashboardChartCard from '../components/dashboard/DashboardChartCard.vue';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { analyticsDashboardApi } from '../api-client/analytics-dashboard-api';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import { buildAnalyticsDetailRoute } from '../components/dashboard/detail-view-routes';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { ElMessage } from '../element-plus-services';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import type {
  AnalyticsDashboardChart,
  AnalyticsDashboardQuery,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRule,
  OptionItemResponse,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';

const DASHBOARD_KEY = 'code-review-multi';
const route = useRoute();
const router = useRouter();
const initialized = ref(false);
const loading = ref(false);
const realtimeRefreshLoading = ref(false);
const exportingKey = ref('');
const sourceOptions = ref<OptionItemResponse[]>([]);
const projectOptions = ref<OptionItemResponse[]>([]);
const source = ref('');
const projectName = ref('');
const dashboard = ref<AnalyticsDashboardResponse | null>(null);
const rules = ref<AnalyticsDashboardRule[]>([]);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);

const pageReady = computed(() => initialized.value);
const canRefreshLatestData = computed(() => hasPermission(authState.currentUser, 'business_data.refresh'));
const ruleByKey = computed(() => new Map(rules.value.map((rule) => [rule.key, rule])));
const scopeParameters = computed<Record<string, string>>(() => ({
  ...(source.value ? { source: source.value } : {}),
  ...(projectName.value ? { projectName: projectName.value } : {}),
}));

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getCodeReviewMultiBoardRealtimeStatus(),
  emptyText: '-',
});

function queryParameters(): AnalyticsDashboardQuery {
  return scopeParameters.value;
}

async function loadSourceOptions() {
  sourceOptions.value = await api.getCodeReviewMultiBoardSourceOptions();
  const requested = String(route.query.source ?? '').trim().toLowerCase();
  source.value = sourceOptions.value.some((option) => option.value === requested)
    ? requested
    : sourceOptions.value[0]?.value ?? '';
}

async function loadProjectOptions(preserveRouteValue = false) {
  projectOptions.value = await api.getCodeReviewMultiBoardProjectOptions(source.value || undefined);
  const requested = preserveRouteValue ? String(route.query.projectName ?? '').trim() : '';
  projectName.value = projectOptions.value.some((option) => option.value === requested)
    ? requested
    : projectOptions.value[0]?.value ?? '';
}

async function loadDashboard() {
  if (!source.value) {
    dashboard.value = null;
    rules.value = [];
    return;
  }
  loading.value = true;
  try {
    const [dashboardResponse, rulesResponse] = await Promise.all([
      analyticsDashboardApi.getDashboard(DASHBOARD_KEY, queryParameters()),
      analyticsDashboardApi.getRules(DASHBOARD_KEY, queryParameters()),
    ]);
    dashboard.value = dashboardResponse;
    rules.value = rulesResponse.rules;
    await router.replace({ query: scopeParameters.value });
  } finally {
    loading.value = false;
  }
}

async function changeSource() {
  await loadProjectOptions();
  await loadDashboard();
}

async function refreshPage() {
  try {
    const selectedSource = source.value;
    const selectedProject = projectName.value;
    await loadSourceOptions();
    if (sourceOptions.value.some((option) => option.value === selectedSource)) {
      source.value = selectedSource;
    }
    await loadProjectOptions();
    if (projectOptions.value.some((option) => option.value === selectedProject)) {
      projectName.value = selectedProject;
    }
    await Promise.all([loadDashboard(), loadSyncStatus()]);
    ElMessage.success('代码走查多元看板已刷新');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '代码走查多元看板刷新失败'));
  }
}

async function refreshLatestData() {
  realtimeRefreshLoading.value = true;
  try {
    const status = await api.refreshCodeReviewMultiBoardRealtime();
    ElMessage.success(status.message || '已开始刷新最新数据');
    await Promise.all([loadDashboard(), loadSyncStatus()]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '刷新最新数据失败'));
  } finally {
    realtimeRefreshLoading.value = false;
  }
}

function openDetail(chart: AnalyticsDashboardChart, point?: EChartPointClickEvent) {
  if (!chart.detail) {
    return;
  }
  if (point && !Object.keys(point.detailParams).length) {
    return;
  }
  const action = {
    ...chart.detail,
    params: { ...chart.detail.params, ...(point?.detailParams ?? {}) },
  };
  void router.push(buildAnalyticsDetailRoute(DASHBOARD_KEY, action, scopeParameters.value));
}

async function exportChart(chart: AnalyticsDashboardChart) {
  if (!chart.export || exportingKey.value) {
    return;
  }
  exportingKey.value = chart.export.exportKey;
  try {
    const file = await analyticsDashboardApi.export(
      DASHBOARD_KEY,
      chart.export.exportKey,
      queryParameters(),
    );
    downloadBlob(file.blob, file.filename || `${chart.title}.xlsx`);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, 'Excel 导出失败'));
  } finally {
    exportingKey.value = '';
  }
}

function openRule(rule: AnalyticsDashboardRule | null) {
  if (!rule) {
    return;
  }
  selectedRule.value = rule;
  ruleDrawerVisible.value = true;
}

async function initializePage() {
  try {
    await loadSourceOptions();
    await loadProjectOptions(true);
    await Promise.all([loadDashboard(), loadSyncStatus()]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '代码走查多元看板加载失败'));
  } finally {
    initialized.value = true;
  }
}

void initializePage();
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="code-review-multi-board">
      <header class="code-review-multi-board__hero">
        <div>
          <div class="code-review-multi-board__eyebrow">代码走查 / 多元看板</div>
          <h2>{{ dashboard?.title || '代码走查多元看板' }}</h2>
          <p>{{ dashboard?.subtitle || '按项目维度展示八类代码走查质量指标。' }}</p>
        </div>
        <div class="code-review-multi-board__actions">
          <SyncMetaBadge :value="lastSyncedText" />
          <el-button
            v-if="canRefreshLatestData"
            :icon="RefreshRight"
            :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
            @click="refreshLatestData"
          >
            刷新最新数据
          </el-button>
          <el-button :icon="Refresh" :loading="loading" @click="refreshPage">刷新</el-button>
        </div>
      </header>

      <section class="code-review-multi-board__filters">
        <label>
          <span>代码库</span>
          <el-select v-model="source" filterable :disabled="loading" @change="changeSource">
            <el-option
              v-for="option in sourceOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </label>
        <label>
          <span>项目</span>
          <el-select
            v-model="projectName"
            filterable
            :disabled="loading || !projectOptions.length"
            placeholder="当前代码库暂无项目"
            @change="loadDashboard"
          >
            <el-option
              v-for="option in projectOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </label>
      </section>

      <el-empty
        v-if="!loading && !dashboard?.charts.length"
        description="当前数据源和项目暂无代码走查统计"
      />
      <section v-else class="code-review-multi-board__grid">
        <DashboardChartCard
          v-for="chart in dashboard?.charts ?? []"
          :key="chart.key"
          :chart="chart"
          :rule="chart.ruleKey ? ruleByKey.get(chart.ruleKey) : null"
          :loading="loading"
          :height="360"
          @title-click="openDetail"
          @point-click="(point, selectedChart) => openDetail(selectedChart, point)"
          @rule-click="openRule"
          @export="exportChart"
        />
      </section>
    </section>
    <DashboardRuleDrawer v-model="ruleDrawerVisible" :rule="selectedRule" />
  </PageStateShell>
</template>

<style scoped>
.code-review-multi-board {
  display: grid;
  gap: 18px;
}

.code-review-multi-board__hero,
.code-review-multi-board__filters {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 20px 24px;
  background: #fff;
  border: 1px solid #e5eaf1;
  border-radius: 14px;
  box-shadow: 0 10px 28px rgb(15 23 42 / 5%);
}

.code-review-multi-board__eyebrow {
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.code-review-multi-board__hero h2 {
  margin: 7px 0 8px;
  color: #0f172a;
  font-size: 24px;
}

.code-review-multi-board__hero p {
  margin: 0;
  color: #64748b;
  line-height: 1.6;
}

.code-review-multi-board__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.code-review-multi-board__filters {
  align-items: center;
  justify-content: flex-start;
  padding: 16px 20px;
}

.code-review-multi-board__filters label {
  display: grid;
  min-width: 240px;
  gap: 7px;
}

.code-review-multi-board__filters label > span {
  color: #475569;
  font-size: 13px;
  font-weight: 600;
}

.code-review-multi-board__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 1080px) {
  .code-review-multi-board__grid {
    grid-template-columns: 1fr;
  }

  .code-review-multi-board__hero,
  .code-review-multi-board__filters {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
