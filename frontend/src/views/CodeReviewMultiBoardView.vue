<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import DashboardChartCard from '../components/dashboard/DashboardChartCard.vue';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { analyticsDashboardApi } from '../api-client/analytics-dashboard-api';
import { api } from '../api';
import { useAcceptedPageView } from '../composables/useAcceptedPageView';
import StatisticBoardFreshnessBanner from '../components/StatisticBoardFreshnessBanner.vue';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import { navigateAnalyticsDetail } from '../components/dashboard/analytics-read-navigation';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { useRealtimeRefreshLifecycle } from '../composables/useRealtimeRefreshLifecycle';
import { ElMessage } from '../element-plus-services';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import type {
  AnalyticsDashboardChart,
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
const exportingKey = ref('');
const sourceOptions = ref<OptionItemResponse[]>([]);
const projectOptions = ref<OptionItemResponse[]>([]);
const source = ref(String(route.query.source ?? '').trim().toLowerCase() || 'cc');
const projectName = ref(String(route.query.projectName ?? '').trim());
const dashboard = ref<AnalyticsDashboardResponse | null>(null);
const rules = ref<AnalyticsDashboardRule[]>([]);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);

interface MultiFrame {
  dashboard: AnalyticsDashboardResponse;
  rules: AnalyticsDashboardRule[];
  sources: OptionItemResponse[];
  projects: OptionItemResponse[];
  query: Record<string, string>;
}
const acceptedPage = useAcceptedPageView<MultiFrame>({ pageKey: DASHBOARD_KEY,
  load: () => { throw new Error('多元看板需要整批查询'); } });
const loadError = ref('');
acceptedPage.start();
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  dashboard.value = frame?.data.dashboard ?? null;
  rules.value = frame?.data.rules ?? [];
  sourceOptions.value = frame?.data.sources ?? [];
  projectOptions.value = frame?.data.projects ?? [];
  if (frame) {
    source.value = frame.data.query.source;
    projectName.value = frame.data.query.projectName;
    if (selectedRule.value) selectedRule.value = frame.data.rules.find(rule => rule.key === selectedRule.value?.key) ?? null;
  } else { selectedRule.value = null; ruleDrawerVisible.value = false; }
}, { flush: 'sync' });
const pageReady = computed(() => Boolean(acceptedPage.accepted.value) || initialized.value);
const canRefreshLatestData = computed(() => hasPermission(authState.currentUser, 'business_data.refresh'));
const ruleByKey = computed(() => new Map(rules.value.map((rule) => [rule.key, rule])));


const {
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getCodeReviewMultiBoardRealtimeStatus(),
  emptyText: '-',
});

const {
  refreshButtonBusy,
  submitRefresh: submitRefreshLatestData,
  invalidateRefreshTracking,
} = useRealtimeRefreshLifecycle({
  submitRefresh: () => api.refreshCodeReviewMultiBoardRealtime(),
  loadStatus: loadSyncStatus,
  loadData: loadDashboard,
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyError: (message) => ElMessage.error(message),
});

let loadSequence = 0;
async function loadDashboard() {
  const ticket = ++loadSequence;
  const requested = { source: source.value || 'cc', projectName: projectName.value };
  loading.value = true;
  try {
    const loaded = await acceptedPage.prepare({ source: requested.source }, async (_scope, read) => {
      const [sources, projects] = await Promise.all([
        api.getCodeReviewMultiBoardSourceOptions(read),
        api.getCodeReviewMultiBoardProjectOptions(requested.source, read),
      ]);
      const query = { source: requested.source, projectName: requested.projectName || projects[0]?.value || '' };
      const [dashboard, rules] = await Promise.all([
        analyticsDashboardApi.getDashboard(DASHBOARD_KEY, query, read),
        analyticsDashboardApi.getRules(DASHBOARD_KEY, query, read),
      ]);
      return { sources, projects, query, dashboard, rules: rules.rules };
    });
    if (ticket !== loadSequence) return;
    if (!loaded) throw acceptedPage.lastError.value ?? new Error('看板准备已取消');
    loadError.value = '';
    await router.replace({ query: acceptedPage.accepted.value!.data.query });
  } catch (error) { if (ticket !== loadSequence) return; loadError.value = getErrorMessage(error, '看板更新失败，已保留当前完整数据'); }
  finally {
    if (ticket === loadSequence) { loading.value = false; initialized.value = true;
    }
  }
}

async function changeSource() {
  invalidateRefreshTracking(); projectName.value = ''; await loadDashboard();
}

async function refreshPage() {
  invalidateRefreshTracking(); await Promise.all([loadDashboard(), loadSyncStatus()]);
}

async function refreshLatestData() {
  await submitRefreshLatestData();
}

async function openDetail(chart: AnalyticsDashboardChart, point?: EChartPointClickEvent) {
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
  try { await navigateAnalyticsDetail(acceptedPage, router, DASHBOARD_KEY, action, frame => frame.query); }
  catch (error) { ElMessage.error(getErrorMessage(error, '详情打开失败')); }
}

async function exportChart(chart: AnalyticsDashboardChart) {
  if (!chart.export || exportingKey.value) {
    return;
  }
  exportingKey.value = chart.export.exportKey;
  try {
    await acceptedPage.readAccepted((frame, read) => analyticsDashboardApi.export(
      DASHBOARD_KEY, chart.export!.exportKey, frame.query, read),
      file => downloadBlob(file.blob, file.filename || `${chart.title}.xlsx`));
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
  await Promise.all([loadDashboard(), loadSyncStatus()]);
}

onBeforeUnmount(() => {
  invalidateRefreshTracking();
  acceptedPage.dispose();
});

void initializePage();
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="code-review-multi-board">
      <StatisticBoardFreshnessBanner v-bind="acceptedPage.accepted.value?.metadata" />
      <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" />
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
            :loading="refreshButtonBusy"
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
          :loading="loading && !acceptedPage.accepted.value"
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
