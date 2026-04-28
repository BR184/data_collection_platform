<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type {
  CodeReviewMultiBoardOverviewResponse,
  IntegrationTestSummaryResponse,
  ReviewDataSummaryResponse,
  ReviewDataFilterOptionsResponse,
  StatisticBoardResponse,
} from '../types/api';
import {
  buildCodeReviewDensityChartOption,
  buildIntegrationPassChartOption,
  buildQualityBoardCards,
  buildReviewDensityChartOption,
  buildSystemTestRepairChartOption,
  computeIntegrationPassRate,
  computeReviewDensity,
  computeSystemTestOpenRate,
} from './quality-board';

const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const reviewFilters = ref<ReviewDataFilterOptionsResponse | null>(null);
const demandReviewSummary = ref<ReviewDataSummaryResponse | null>(null);
const designReviewSummary = ref<ReviewDataSummaryResponse | null>(null);
const codeReviewCcOverview = ref<CodeReviewMultiBoardOverviewResponse | null>(null);
const codeReviewDgmOverview = ref<CodeReviewMultiBoardOverviewResponse | null>(null);
const integrationSummary = ref<IntegrationTestSummaryResponse | null>(null);
const systemTestSummaryBoard = ref<StatisticBoardResponse | null>(null);

const demandDensity = computed(() => computeReviewDensity(demandReviewSummary.value));
const designDensity = computed(() => computeReviewDensity(designReviewSummary.value));
const integrationPassRate = computed(() => computeIntegrationPassRate(integrationSummary.value));
const systemTestOpenRate = computed(() => computeSystemTestOpenRate(systemTestSummaryBoard.value));
const cards = computed(() =>
  buildQualityBoardCards({
    demandDensity: demandDensity.value,
    designDensity: designDensity.value,
    codeReviewCcDensity: codeReviewCcOverview.value?.defectDensityPerKloc ?? null,
    codeReviewDgmDensity: codeReviewDgmOverview.value?.defectDensityPerKloc ?? null,
    integrationPassRate: integrationPassRate.value,
    systemTestOpenRate: systemTestOpenRate.value,
  }),
);
const reviewDensityChartOption = computed(() =>
  buildReviewDensityChartOption({
    demandDensity: demandDensity.value,
    designDensity: designDensity.value,
  }),
);
const codeReviewDensityChartOption = computed(() =>
  buildCodeReviewDensityChartOption({
    ccDensity: codeReviewCcOverview.value?.defectDensityPerKloc ?? null,
    dgmDensity: codeReviewDgmOverview.value?.defectDensityPerKloc ?? null,
  }),
);
const integrationPassChartOption = computed(() => buildIntegrationPassChartOption(integrationSummary.value));
const systemTestRepairChartOption = computed(() => buildSystemTestRepairChartOption(systemTestSummaryBoard.value));

const pageReady = computed(() => initialized.value);

function pickReviewType(types: ReviewDataFilterOptionsResponse['reviewTypes'] | undefined, keyword: string) {
  return types?.find((item) => item.label.includes(keyword) || item.value.includes(keyword))?.value ?? '';
}

async function loadReviewSummaries() {
  reviewFilters.value = await api.getReviewDataFilterOptions();
  const demandType = pickReviewType(reviewFilters.value.reviewTypes, '需求');
  const designType = pickReviewType(reviewFilters.value.reviewTypes, '设计');
  const [demand, design] = await Promise.all([
    demandType
      ? api.getReviewDataRecords({ reviewType: demandType, page: 1, size: 1 })
      : Promise.resolve({ summary: null } as { summary: ReviewDataSummaryResponse | null }),
    designType
      ? api.getReviewDataRecords({ reviewType: designType, page: 1, size: 1 })
      : Promise.resolve({ summary: null } as { summary: ReviewDataSummaryResponse | null }),
  ]);
  demandReviewSummary.value = demand.summary;
  designReviewSummary.value = design.summary;
}

async function loadCodeReviewSummaries() {
  const sourceOptions = await api.getCodeReviewMultiBoardSourceOptions();
  const hasCc = sourceOptions.some((item) => item.value === 'cc');
  const hasDgm = sourceOptions.some((item) => item.value === 'dgm');
  const [cc, dgm] = await Promise.all([
    hasCc ? api.getCodeReviewMultiBoardOverview('cc') : Promise.resolve(null),
    hasDgm ? api.getCodeReviewMultiBoardOverview('dgm') : Promise.resolve(null),
  ]);
  codeReviewCcOverview.value = cc;
  codeReviewDgmOverview.value = dgm;
}

async function loadIntegrationSummary() {
  const projects = await api.getIntegrationTestProjectOptions();
  const firstProject = projects[0];
  if (!firstProject) {
    integrationSummary.value = null;
    return;
  }
  const phases = await api.getIntegrationTestPhaseOptions(firstProject.projectId);
  const firstPhase = phases[0];
  if (!firstPhase) {
    integrationSummary.value = null;
    return;
  }
  integrationSummary.value = await api.getIntegrationTestSummary({
    projectId: firstProject.projectId,
    testingPhase: firstPhase.testingPhase,
  });
}

async function loadSystemTestSummary() {
  systemTestSummaryBoard.value = await api.getStatisticBoard('system-test-defect-summary');
}

async function loadPage() {
  loading.value = true;
  try {
    await Promise.all([
      loadReviewSummaries(),
      loadCodeReviewSummaries(),
      loadIntegrationSummary(),
      loadSystemTestSummary(),
    ]);
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleRefresh() {
  try {
    await loadPage();
    ElMessage.success('研发质量看板已刷新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '研发质量看板刷新失败');
  }
}

function goTo(path: string) {
  void router.push(path);
}

void loadPage().catch((error) => {
  initialized.value = true;
  loading.value = false;
  ElMessage.error(error instanceof Error ? error.message : '研发质量看板加载失败');
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-rd">
      <section class="quality-board-rd__hero">
        <div>
          <div class="quality-board-rd__eyebrow">质量看板 / 研发质量</div>
          <h2>研发质量一屏概览</h2>
          <p>把评审、代码走查、集成测试和系统测试里最有判断力的信号拉到同一页，不再要求用户自己在多张表之间来回拼。</p>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="handleRefresh">刷新</el-button>
      </section>

      <section class="quality-board-rd__summary">
        <article v-for="card in cards" :key="card.key" class="quality-board-rd__summary-card" :data-tone="card.tone ?? 'default'">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="quality-board-rd__grid">
        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>评审密度对比</h3>
              <p>先看需求评审和设计评审的密度区间是否失衡。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/review-data/home')">评审数据管理</el-link>
          </div>
          <EChartPanel :option="reviewDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>代码走查密度</h3>
              <p>用统一量纲比较 CC 与 DGM 两类代码源的整体风险密度。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/code-review/multi-board')">代码走查看板</el-link>
          </div>
          <EChartPanel :option="codeReviewDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>集成测试模块通过率</h3>
              <p>只展示最需要关注的模块，不把长尾模块都堆进一张图。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/integration-test/home')">集成测试分析</el-link>
          </div>
          <EChartPanel :option="integrationPassChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>系统测试模块修复率</h3>
              <p>只保留高缺陷模块，让修复进度比较更直接。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/question-metrics/home')">系统测试汇总</el-link>
          </div>
          <EChartPanel :option="systemTestRepairChartOption" :loading="loading" :height="320" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.quality-board-rd {
  display: grid;
  gap: 20px;
}

.quality-board-rd__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.quality-board-rd__hero > *,
.quality-board-rd__panel,
.quality-board-rd__summary-card {
  min-width: 0;
}

.quality-board-rd__eyebrow {
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.quality-board-rd__hero h2 {
  margin: 8px 0 10px;
  font-size: 24px;
  color: #111827;
}

.quality-board-rd__hero p {
  margin: 0;
  max-width: 760px;
  color: #667085;
  line-height: 1.7;
}

.quality-board-rd__summary {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.quality-board-rd__summary-card {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
  display: grid;
  gap: 8px;
}

.quality-board-rd__summary-card span {
  font-size: 13px;
  color: #667085;
}

.quality-board-rd__summary-card strong {
  font-size: clamp(20px, 2vw, 24px);
  line-height: 1.2;
  color: #111827;
  overflow-wrap: anywhere;
}

.quality-board-rd__summary-card[data-tone='success'] strong {
  color: #039855;
}

.quality-board-rd__summary-card[data-tone='warning'] strong {
  color: #dc6803;
}

.quality-board-rd__summary-card[data-tone='danger'] strong {
  color: #d92d20;
}

.quality-board-rd__grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quality-board-rd__panel {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.quality-board-rd__panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.quality-board-rd__panel-head h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.quality-board-rd__panel-head p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1180px) {
  .quality-board-rd__summary,
  .quality-board-rd__grid {
    grid-template-columns: 1fr;
  }

  .quality-board-rd__hero {
    flex-direction: column;
  }
}
</style>
