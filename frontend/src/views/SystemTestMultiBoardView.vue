<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import ExportActionMenu from '../components/base/ExportActionMenu.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import SmartSelect from '../components/base/SmartSelect.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import { buildAnalyticsDetailRoute } from '../components/dashboard/detail-view-routes';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import RuleHintIcon from '../components/dashboard/RuleHintIcon.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import { useRealtimeWorkspaceStatus, waitForRealtimeWorkspaceRefresh } from '../composables/useRealtimeWorkspaceStatus';
import { ElMessage } from '../element-plus-services';
import type {
  AnalyticsDashboardRule,
  SystemTestIssueMultiBoardChartResponse,
  SystemTestIssueMultiBoardResponse,
  SystemTestIssueMultiBoardSummaryCardResponse,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { buildMultiBoardChartOption } from './system-test-multi-board';

const route = useRoute();
const router = useRouter();
const DASHBOARD_KEY = 'system-test-multi';

const initialized = ref(false);
const loading = ref(false);
const realtimeRefreshLoading = ref(false);
const exportLoadingKey = ref('');
const board = ref<SystemTestIssueMultiBoardResponse | null>(null);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);

const selectedProjectId = computed(() => String(route.query.projectId ?? '9'));
const selectedTestingPhase = computed(() => String(route.query.testingPhase ?? ''));
const pageReady = computed(() => initialized.value);
const canRefreshLatestData = computed(() => hasPermission(authState.currentUser, 'business_data.refresh'));
const scopeLabel = computed(() => board.value?.scope.scopeLabel ?? 'CrownCAD / 全部阶段');
const ruleByKey = computed(() => new Map(
  (board.value?.rules ?? []).map((rule) => [rule.key, rule]),
));

const {
  syncStatus,
  lastSyncedText,
  loadRealtimeStatus: loadSyncStatus,
} = useRealtimeWorkspaceStatus({
  loadStatus: () => api.getStatisticBoardRealtimeStatus('system-test-defect-summary'),
  emptyText: '-',
});

async function replaceQuery(patch: Record<string, string | undefined>) {
  const nextQuery: Record<string, string> = {};
  for (const [key, value] of Object.entries({ ...route.query, ...patch })) {
    if (value == null || value === '') {
      continue;
    }
    nextQuery[key] = String(value);
  }
  await router.replace({ path: route.path, query: nextQuery, hash: route.hash });
}

async function loadBoard() {
  loading.value = true;
  try {
    const nextBoard = await api.getSystemTestIssueMultiBoard({
      projectId: selectedProjectId.value,
      testingPhase: selectedTestingPhase.value,
    });
    board.value = nextBoard;
    if (
      (!route.query.projectId || !route.query.testingPhase)
      && nextBoard.scope.projectId
      && nextBoard.scope.testingPhase
    ) {
      await replaceQuery({
        projectId: String(nextBoard.scope.projectId),
        testingPhase: nextBoard.scope.testingPhase,
      });
    }
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleProjectChange(value: string | string[]) {
  await replaceQuery({ projectId: String(value || '9'), testingPhase: undefined });
  await loadBoard();
}

async function handleTestingPhaseChange(value: string | string[]) {
  await replaceQuery({ testingPhase: String(value || '') || undefined });
  await loadBoard();
}

async function handleRefresh() {
  try {
    await loadBoard();
    ElMessage.success('议题多元看板已刷新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '议题多元看板刷新失败');
  }
}

async function handleRefreshLatestData() {
  realtimeRefreshLoading.value = true;
  try {
    const status = await api.refreshStatisticBoardRealtime('system-test-defect-summary');
    ElMessage.success(status.message || '已开始刷新最新数据');
    await waitForRealtimeWorkspaceRefresh(status, loadSyncStatus);
    await loadBoard();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新最新数据失败');
  } finally {
    realtimeRefreshLoading.value = false;
  }
}

async function handleExport(chart: SystemTestIssueMultiBoardChartResponse) {
  exportLoadingKey.value = chart.key;
  try {
    const file = await api.exportSystemTestIssueMultiBoardChart({
      chartKey: chart.key,
      projectId: selectedProjectId.value,
      testingPhase: selectedTestingPhase.value,
    });
    downloadBlob(file.blob, file.filename || `${chart.exportName}.xlsx`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '图表数据导出失败');
  } finally {
    exportLoadingKey.value = '';
  }
}

function chartOption(chart: SystemTestIssueMultiBoardChartResponse) {
  return buildMultiBoardChartOption(chart);
}

function chartHasData(chart: SystemTestIssueMultiBoardChartResponse) {
  return chart.points.length > 0 || chart.categories.length > 0;
}

function chartRule(chart: SystemTestIssueMultiBoardChartResponse) {
  return ruleByKey.value.get(chart.ruleKey) ?? null;
}

function summaryRule(card: SystemTestIssueMultiBoardSummaryCardResponse) {
  return ruleByKey.value.get(card.ruleKey) ?? null;
}

function inheritedDetailParameters() {
  return {
    projectId: String(board.value?.scope.projectId ?? selectedProjectId.value),
    projectName: board.value?.scope.projectName || '',
    testingPhase: board.value?.scope.testingPhase || selectedTestingPhase.value || '',
  };
}

function chartDetailLocation(
  chart: SystemTestIssueMultiBoardChartResponse,
  point?: EChartPointClickEvent,
) {
  const viewKey = point?.detailViewKey || chart.detailViewKey;
  if (!viewKey) {
    return null;
  }
  return buildAnalyticsDetailRoute(
    DASHBOARD_KEY,
    {
      viewKey,
      params: {
        ...chart.detailParams,
        ...(point?.detailParams ?? {}),
      },
    },
    inheritedDetailParameters(),
  );
}

async function handleChartTitleClick(chart: SystemTestIssueMultiBoardChartResponse) {
  const location = chartDetailLocation(chart);
  if (location) {
    await router.push(location);
  }
}

async function handleChartPointClick(
  point: EChartPointClickEvent,
  chart: SystemTestIssueMultiBoardChartResponse,
) {
  if (!point.detailViewKey || !Object.keys(point.detailParams).length) {
    return;
  }
  const location = chartDetailLocation(chart, point);
  if (location) {
    await router.push(location);
  }
}

function openRule(rule: AnalyticsDashboardRule | null) {
  if (!rule) {
    return;
  }
  selectedRule.value = rule;
  ruleDrawerVisible.value = true;
}

void Promise.all([loadBoard(), loadSyncStatus()]).catch((error) => {
  initialized.value = true;
  loading.value = false;
  ElMessage.error(error instanceof Error ? error.message : '议题多元看板加载失败');
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="system-test-multi-board">
      <section class="system-test-multi-board__toolbar">
        <div class="system-test-multi-board__title">
          <span>系统测试 / 多元看板</span>
          <h2>议题多元看板</h2>
          <p>{{ scopeLabel }}</p>
        </div>
        <div class="system-test-multi-board__actions">
          <SyncMetaBadge :value="lastSyncedText" />
          <SmartSelect
            :model-value="selectedProjectId"
            placeholder="选择项目"
            class="system-test-multi-board__project-select"
            :clearable="false"
            :options="board?.projectOptions ?? []"
            dropdown-mode="adaptive-tags"
            @change="handleProjectChange"
          />
          <SmartSelect
            :model-value="selectedTestingPhase"
            placeholder="全部阶段"
            class="system-test-multi-board__phase-select"
            :options="board?.testingPhaseOptions ?? []"
            dropdown-mode="adaptive-tags"
            @change="handleTestingPhaseChange"
          />
          <el-button
            v-if="canRefreshLatestData"
            class="app-action-button app-action-button--refresh"
            :icon="RefreshRight"
            :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
            @click="handleRefreshLatestData"
          >
            刷新最新数据
          </el-button>
          <el-button
            class="app-action-button app-action-button--refresh"
            :icon="Refresh"
            :loading="loading"
            @click="handleRefresh"
          >
            刷新
          </el-button>
        </div>
      </section>

      <section class="system-test-multi-board__summary">
        <article
          v-for="card in board?.summaryCards ?? []"
          :key="card.key"
          class="system-test-multi-board__summary-card"
          :data-tone="card.tone ?? 'default'"
        >
          <div class="system-test-multi-board__summary-title">
            <span>{{ card.label }}</span>
            <RuleHintIcon :rule="summaryRule(card)" @click="openRule" />
          </div>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="system-test-multi-board__grid">
        <article v-for="chart in board?.charts ?? []" :key="chart.key" class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <div class="system-test-multi-board__panel-title-line">
                <button
                  type="button"
                  class="system-test-multi-board__panel-title"
                  :class="{ 'is-clickable': Boolean(chart.detailViewKey) }"
                  :disabled="!chart.detailViewKey"
                  @click="handleChartTitleClick(chart)"
                >
                  {{ chart.title }}
                </button>
                <RuleHintIcon :rule="chartRule(chart)" @click="openRule" />
              </div>
              <p>{{ chart.description }}</p>
              <span>{{ chart.metadata.scope }}</span>
            </div>
            <div class="system-test-multi-board__panel-actions">
              <ExportActionMenu
                :actions="[{ key: chart.key, label: '导出', disabled: !chartHasData(chart) }]"
                :loading="exportLoadingKey === chart.key"
                @select="() => handleExport(chart)"
              />
            </div>
          </div>
          <EChartPanel
            :option="chartOption(chart)"
            :loading="loading"
            :height="340"
            @point-click="(point) => handleChartPointClick(point, chart)"
          />
        </article>
      </section>
    </section>
    <DashboardRuleDrawer v-model="ruleDrawerVisible" :rule="selectedRule" />
  </PageStateShell>
</template>

<style scoped>
.system-test-multi-board {
  display: grid;
  gap: 18px;
}

.system-test-multi-board__toolbar,
.system-test-multi-board__panel,
.system-test-multi-board__summary-card {
  min-width: 0;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 8px;
  background: #fff;
}

.system-test-multi-board__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 18px 20px;
}

.system-test-multi-board__title {
  min-width: 220px;
}

.system-test-multi-board__title span {
  color: #64748b;
  font-size: 12px;
  font-weight: 700;
}

.system-test-multi-board__title h2 {
  margin: 6px 0 4px;
  color: #111827;
  font-size: 22px;
  line-height: 1.25;
}

.system-test-multi-board__title p {
  margin: 0;
  color: #475467;
  font-size: 13px;
}

.system-test-multi-board__actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.system-test-multi-board__project-select {
  width: 180px;
}

.system-test-multi-board__phase-select {
  width: 220px;
}

.system-test-multi-board__summary {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.system-test-multi-board__summary-card {
  display: grid;
  gap: 8px;
  padding: 16px 18px;
}

.system-test-multi-board__summary-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.system-test-multi-board__summary-card span {
  color: #667085;
  font-size: 13px;
}

.system-test-multi-board__summary-card strong {
  color: #111827;
  font-size: 24px;
  line-height: 1.15;
}

.system-test-multi-board__summary-card[data-tone='success'] strong {
  color: #059669;
}

.system-test-multi-board__summary-card[data-tone='warning'] strong {
  color: #d97706;
}

.system-test-multi-board__summary-card[data-tone='danger'] strong {
  color: #dc2626;
}

.system-test-multi-board__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.system-test-multi-board__panel {
  padding: 16px 18px 14px;
}

.system-test-multi-board__panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 8px;
}

.system-test-multi-board__panel-title-line {
  display: flex;
  align-items: center;
  gap: 6px;
}

.system-test-multi-board__panel-title {
  padding: 0;
  margin: 0;
  color: #111827;
  font: inherit;
  font-size: 16px;
  font-weight: 600;
  text-align: left;
  background: transparent;
  border: 0;
}

.system-test-multi-board__panel-title.is-clickable {
  color: #1d4ed8;
  cursor: pointer;
}

.system-test-multi-board__panel-head p {
  margin: 6px 0 4px;
  color: #667085;
  font-size: 13px;
  line-height: 1.5;
}

.system-test-multi-board__panel-head span {
  color: #475467;
  font-size: 12px;
}

.system-test-multi-board__panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

@media (max-width: 1180px) {
  .system-test-multi-board__grid {
    grid-template-columns: 1fr;
  }

  .system-test-multi-board__toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .system-test-multi-board__actions {
    justify-content: flex-start;
    width: 100%;
  }
}
</style>
