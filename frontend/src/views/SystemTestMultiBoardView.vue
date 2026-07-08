<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Download, Refresh, RefreshRight } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import SmartSelect from '../components/base/SmartSelect.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import { ElMessage } from '../element-plus-services';
import type { SystemTestIssueMultiBoardChartResponse, SystemTestIssueMultiBoardResponse } from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { buildMultiBoardChartOption } from './system-test-multi-board';

const route = useRoute();
const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const realtimeRefreshLoading = ref(false);
const exportLoadingKey = ref('');
const board = ref<SystemTestIssueMultiBoardResponse | null>(null);

const selectedProjectId = computed(() => String(route.query.projectId ?? '9'));
const selectedTestingPhase = computed(() => String(route.query.testingPhase ?? ''));
const pageReady = computed(() => initialized.value);
const canRefreshLatestData = computed(() => authState.currentUser.role === 'ADMIN');
const scopeLabel = computed(() => board.value?.scope.scopeLabel ?? 'CrownCAD / 全部阶段');

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
    let status = await api.refreshStatisticBoardRealtime('system-test-defect-summary');
    ElMessage.success(status.message || '已开始刷新最新数据');
    for (let attempt = 0; attempt < 8 && status.refreshing; attempt++) {
      await sleep(1000);
      status = (await loadSyncStatus()) ?? status;
    }
    await loadBoard();
    await loadSyncStatus();
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

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
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
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="system-test-multi-board__grid">
        <article v-for="chart in board?.charts ?? []" :key="chart.key" class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>{{ chart.title }}</h3>
              <p>{{ chart.description }}</p>
              <span>{{ chart.metadata.scope }}</span>
            </div>
            <div class="system-test-multi-board__panel-actions">
              <el-link
                v-if="chart.detailPath"
                underline="never"
                type="primary"
                :href="router.resolve({ path: chart.detailPath }).href"
              >
                查看详情
              </el-link>
              <el-tooltip content="下载图表数据" placement="top">
                <el-button
                  class="system-test-multi-board__icon-button"
                  :icon="Download"
                  :loading="exportLoadingKey === chart.key"
                  :disabled="!chartHasData(chart)"
                  circle
                  @click="handleExport(chart)"
                />
              </el-tooltip>
            </div>
          </div>
          <EChartPanel :option="chartOption(chart)" :loading="loading" :height="340" />
        </article>
      </section>
    </section>
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

.system-test-multi-board__panel-head h3 {
  margin: 0;
  color: #111827;
  font-size: 16px;
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

.system-test-multi-board__icon-button {
  color: #059669;
  border-color: rgba(5, 150, 105, 0.28);
  background: #fff;
}

.system-test-multi-board__icon-button:hover,
.system-test-multi-board__icon-button:focus {
  color: #047857;
  border-color: rgba(5, 150, 105, 0.44);
  background: rgba(236, 253, 245, 0.9);
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
