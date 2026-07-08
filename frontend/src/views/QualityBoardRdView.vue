<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from '../element-plus-services';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type { OptionItemResponse, QualityBoardRdOverviewResponse } from '../types/api';
import {
  buildCodeReviewDensityChartOption,
  buildQualityBoardCards,
  buildQualityRateChartOption,
  buildReviewDensityChartOption,
} from './quality-board';

const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const projectOptions = ref<OptionItemResponse[]>([]);
const selectedProjectName = ref('');
const overview = ref<QualityBoardRdOverviewResponse | null>(null);

const cards = computed(() => buildQualityBoardCards({ overview: overview.value }));
const reviewDensityChartOption = computed(() =>
  buildReviewDensityChartOption({
    demandDensity: overview.value?.demandReviewReportDensity ?? null,
    designDensity: overview.value?.designReviewReportDensity ?? null,
  }),
);
const codeReviewDensityChartOption = computed(() =>
  buildCodeReviewDensityChartOption({
    ccDensity: overview.value?.codeWalkThroughDefectDensityCc ?? null,
    dgmDensity: overview.value?.codeWalkThroughDefectDensityDgm ?? null,
  }),
);
const qualityRateChartOption = computed(() => buildQualityRateChartOption(overview.value));

const pageReady = computed(() => initialized.value);

async function loadPage() {
  loading.value = true;
  try {
    const options = await api.getQualityBoardRdProjectOptions();
    projectOptions.value = options.options;
    selectedProjectName.value = selectedProjectName.value || options.defaultProjectName || options.options[0]?.value || 'CC2026R3';
    overview.value = await api.getQualityBoardRdOverview(selectedProjectName.value);
    return true;
  } catch (error) {
    console.warn('研发质量看板加载失败', error);
    return false;
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleRefresh() {
  const success = await loadPage();
  if (success) {
    ElMessage.success('研发质量看板已刷新');
    return;
  }
  ElMessage.warning('部分看板加载失败，已展示可用数据');
}

async function handleProjectChange() {
  loading.value = true;
  try {
    overview.value = await api.getQualityBoardRdOverview(selectedProjectName.value);
  } catch (error) {
    console.warn('研发质量看板项目切换失败', error);
    ElMessage.error('研发质量看板加载失败');
  } finally {
    loading.value = false;
  }
}

function goTo(path: string) {
  void router.push(path);
}

void loadPage().then((success) => {
  if (!success) {
    ElMessage.warning('部分看板加载失败，已展示可用数据');
  }
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-rd">
      <section class="quality-board-rd__hero">
        <div>
          <div class="quality-board-rd__eyebrow">质量看板 / 研发质量</div>
          <h2>研发质量一屏概览</h2>
        </div>
        <div class="quality-board-rd__actions">
          <el-select
            v-model="selectedProjectName"
            class="quality-board-rd__project-select"
            filterable
            placeholder="项目名称"
            :disabled="loading"
            @change="handleProjectChange"
          >
            <el-option v-for="item in projectOptions" :key="item.value" :label="item.label" :value="item.value" />
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
              <p>需求评审与设计评审缺陷密度对比。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/review-data/home')">评审数据管理</el-link>
          </div>
          <EChartPanel :option="reviewDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>代码走查密度</h3>
              <p>CC 与 DGM 代码走查缺陷密度对比。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/code-review/multi-board')">代码走查看板</el-link>
          </div>
          <EChartPanel :option="codeReviewDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <div>
              <h3>测试与缺陷闭环</h3>
              <p>展示集成测试通过率、发布遗留率、开发遗留率和新发修复率。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/question-metrics/home')">系统测试汇总</el-link>
          </div>
          <EChartPanel :option="qualityRateChartOption" :loading="loading" :height="320" />
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
  align-items: center;
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

.quality-board-rd__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}

.quality-board-rd__project-select {
  width: 220px;
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
    align-items: flex-start;
    flex-direction: column;
  }

  .quality-board-rd__actions,
  .quality-board-rd__project-select {
    width: 100%;
  }
}
</style>
