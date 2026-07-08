<script setup lang="ts">
import { computed, ref } from 'vue';
// 系统测试多元看板聚合多个统计卡片和图表配置，页面职责是绑定看板 key 与刷新动作。
// 统计计算不在前端重复推导，所有口径都通过统一统计板接口获取。
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from '../element-plus-services';
import { Refresh, RefreshRight } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import SmartSelect from '../components/base/SmartSelect.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import SyncMetaBadge from '../components/realtime/SyncMetaBadge.vue';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import type { StatisticBoardResponse, SystemTestIssueSearchFilterOptionsResponse } from '../types/api';
import { useRealtimeWorkspaceStatus } from '../composables/useRealtimeWorkspaceStatus';
import {
  buildCauseChartOption,
  buildDelayCauseChartOption,
  buildDetailLinkMap,
  buildModuleChartOption,
  buildPhaseChartOption,
  buildProjectFilter,
  buildRepairRateChartOption,
  buildSeverityChartOption,
  buildSystemTestSummaryCards,
} from './system-test-multi-board';

const route = useRoute();
const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const realtimeRefreshLoading = ref(false);
const filterOptions = ref<SystemTestIssueSearchFilterOptionsResponse>({
  projectNames: [],
  moduleNames: [],
  functionNames: [],
  testingPhases: [],
  authorNames: [],
  assigneeNames: [],
  issueStates: [],
  severityLevels: [],
  bugStatuses: [],
  categories: [],
  milestoneTitles: [],
});
const summaryBoard = ref<StatisticBoardResponse | null>(null);
const phaseBoard = ref<StatisticBoardResponse | null>(null);
const causeBoard = ref<StatisticBoardResponse | null>(null);
const delayBoard = ref<StatisticBoardResponse | null>(null);

const detailLinks = buildDetailLinkMap();

const selectedProjectName = computed(() => String(route.query.projectName ?? ''));
const pageReady = computed(() => initialized.value);
const projectOptions = computed(() => filterOptions.value.projectNames ?? []);
const summaryCards = computed(() => buildSystemTestSummaryCards(summaryBoard.value));
const severityChartOption = computed(() => buildSeverityChartOption(summaryBoard.value));
const phaseChartOption = computed(() => buildPhaseChartOption(phaseBoard.value));
const moduleChartOption = computed(() => buildModuleChartOption(summaryBoard.value));
const repairRateChartOption = computed(() => buildRepairRateChartOption(summaryBoard.value));
const causeChartOption = computed(() => buildCauseChartOption(causeBoard.value));
const delayCauseChartOption = computed(() => buildDelayCauseChartOption(delayBoard.value));
const canRefreshLatestData = computed(() => authState.currentUser.role === 'ADMIN');

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

async function loadBoards() {
  loading.value = true;
  try {
    filterOptions.value = await api.getSystemTestIssueSearchFilterOptions();
    const filters = buildProjectFilter(selectedProjectName.value);
    const [summary, phase, cause, delay] = await Promise.all([
      api.getStatisticBoard('system-test-defect-summary', { filters }),
      api.getStatisticBoard('system-test-phase-statistics', { filters }),
      api.getStatisticBoard('system-test-defect-cause', { filters }),
      api.getStatisticBoard('system-test-delay-analysis', { filters }),
    ]);
    summaryBoard.value = summary;
    phaseBoard.value = phase;
    causeBoard.value = cause;
    delayBoard.value = delay;
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleProjectChange(value: string) {
  await replaceQuery({ projectName: value || undefined });
  await loadBoards();
}

async function handleRefresh() {
  try {
    await loadBoards();
    ElMessage.success('系统测试多元看板已刷新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '系统测试多元看板刷新失败');
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
    await loadBoards();
    await loadSyncStatus();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '刷新最新数据失败');
  } finally {
    realtimeRefreshLoading.value = false;
  }
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function buildFilterLink(path: string) {
  if (!selectedProjectName.value) {
    return { path };
  }
  return {
    path,
    query: {
      filterGroup: JSON.stringify({
        logic: 'AND',
        conditions: [
          {
            fieldKey: 'projectName',
            operator: 'eq',
            value: selectedProjectName.value,
          },
        ],
      }),
    },
  };
}

void Promise.all([loadBoards(), loadSyncStatus()]).catch((error) => {
  initialized.value = true;
  loading.value = false;
  ElMessage.error(error instanceof Error ? error.message : '系统测试多元看板加载失败');
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="system-test-multi-board">
      <section class="system-test-multi-board__hero">
        <div>
          <div class="system-test-multi-board__eyebrow">系统测试 / 多元看板</div>
          <h2>系统测试质量概览</h2>
        </div>
        <div class="system-test-multi-board__hero-actions">
          <SyncMetaBadge :value="lastSyncedText" />
          <el-button
            v-if="canRefreshLatestData"
            class="app-action-button app-action-button--refresh"
            :icon="RefreshRight"
            :loading="realtimeRefreshLoading || Boolean(syncStatus?.refreshing)"
            @click="handleRefreshLatestData"
          >
            刷新最新数据
          </el-button>
          <SmartSelect
            :model-value="selectedProjectName"
            placeholder="全部项目"
            clearable
            class="system-test-multi-board__project-select"
            :options="projectOptions"
            dropdown-mode="adaptive-tags"
            @change="handleProjectChange"
          />
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
          v-for="card in summaryCards"
          :key="card.key"
          class="system-test-multi-board__summary-card"
          :data-tone="card.tone ?? 'default'"
        >
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="system-test-multi-board__grid">
        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>严重程度分布</h3>
              <p>先看结构，再决定往哪个正式页下钻。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve(buildFilterLink(detailLinks.summaryPath)).href">
              正式统计页
            </el-link>
          </div>
          <EChartPanel :option="severityChartOption" :loading="loading" :height="340" />
        </article>

        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>阶段分布</h3>
              <p>看不同测试轮次的缺陷落点。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve({ path: detailLinks.phasePath }).href">
              阶段统计页
            </el-link>
          </div>
          <EChartPanel :option="phaseChartOption" :loading="loading" :height="340" />
        </article>

        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>模块缺陷 Top 8</h3>
              <p>优先暴露当前缺陷压力最高的模块。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve(buildFilterLink(detailLinks.summaryPath)).href">
              缺陷汇总页
            </el-link>
          </div>
          <EChartPanel :option="moduleChartOption" :loading="loading" :height="340" />
        </article>

        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>模块修复率</h3>
              <p>只保留高缺陷模块，避免长尾噪音。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve(buildFilterLink(detailLinks.summaryPath)).href">
              缺陷汇总页
            </el-link>
          </div>
          <EChartPanel :option="repairRateChartOption" :loading="loading" :height="340" />
        </article>

        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>缺陷原因分析</h3>
              <p>按缺陷原因拆分一级、二级、三级和建议数量。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve({ path: detailLinks.causePath }).href">
              原因分析页
            </el-link>
          </div>
          <EChartPanel :option="causeChartOption" :loading="loading" :height="340" />
        </article>

        <article class="system-test-multi-board__panel">
          <div class="system-test-multi-board__panel-head">
            <div>
              <h3>延期原因分布</h3>
              <p>帮助判断延期主要集中在哪些阻塞类型。</p>
            </div>
            <el-link underline="never" type="primary" :href="router.resolve({ path: detailLinks.delayPath }).href">
              延期分析页
            </el-link>
          </div>
          <EChartPanel :option="delayCauseChartOption" :loading="loading" :height="340" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.system-test-multi-board {
  display: grid;
  gap: 20px;
}

.system-test-multi-board__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.system-test-multi-board__hero > * ,
.system-test-multi-board__panel,
.system-test-multi-board__summary-card {
  min-width: 0;
}

.system-test-multi-board__eyebrow {
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.system-test-multi-board__hero h2 {
  margin: 8px 0 10px;
  font-size: 24px;
  color: #111827;
}

.system-test-multi-board__hero p {
  margin: 0;
  max-width: 760px;
  color: #667085;
  line-height: 1.7;
}

.system-test-multi-board__hero-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
}

.system-test-multi-board__project-select {
  width: 220px;
}

.system-test-multi-board__summary {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.system-test-multi-board__summary-card {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
  display: grid;
  gap: 8px;
}

.system-test-multi-board__summary-card span {
  font-size: 13px;
  color: #667085;
}

.system-test-multi-board__summary-card strong {
  font-size: clamp(20px, 2vw, 24px);
  line-height: 1.2;
  color: #111827;
  overflow-wrap: anywhere;
}

.system-test-multi-board__summary-card[data-tone='success'] strong {
  color: #039855;
}

.system-test-multi-board__summary-card[data-tone='warning'] strong {
  color: #dc6803;
}

.system-test-multi-board__summary-card[data-tone='danger'] strong {
  color: #d92d20;
}

.system-test-multi-board__grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.system-test-multi-board__panel {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.system-test-multi-board__panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.system-test-multi-board__panel-head h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.system-test-multi-board__panel-head p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1180px) {
  .system-test-multi-board__summary,
  .system-test-multi-board__grid {
    grid-template-columns: 1fr;
  }

  .system-test-multi-board__hero {
    flex-direction: column;
  }

  .system-test-multi-board__hero-actions {
    width: 100%;
    flex-wrap: wrap;
  }
}
</style>
