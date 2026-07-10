<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from '../element-plus-services';
import { Refresh } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type { OptionItemResponse, QualityBoardOtherOverviewResponse } from '../types/api';
import {
  buildQualityBoardTrendRowsChartOption,
  buildQualityBoardValueRowsChartOption,
} from './quality-board';

const initialized = ref(false);
const loading = ref(false);
const selectedProjectName = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const overview = ref<QualityBoardOtherOverviewResponse | null>(null);

const pageReady = computed(() => initialized.value);
const functionDefectCountOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '功能缺陷数量',
    subtitle: '按功能统计系统测试缺陷数量',
    rows: overview.value?.functionDefectCountRows,
    color: '#2f80ed',
  }),
);
const functionDefectDensityOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '功能缺陷密度',
    subtitle: '功能缺陷数 / 对应新增代码行数',
    rows: overview.value?.functionDefectDensityRows,
    color: '#16a085',
    suffix: '%',
    includeZero: true,
  }),
);
const qualityRankingOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '质量达人榜',
    subtitle: '按成员代码千行缺陷密度排序，数值越低越好',
    rows: overview.value?.qualityRankingRows,
    color: '#7c5ce7',
    suffix: ' KLOC',
    includeZero: true,
  }),
);
const memberUnresolvedRateOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '成员未修复缺陷率',
    subtitle: '个人未修复缺陷数 / 个人缺陷总数',
    rows: overview.value?.memberUnresolvedRateRows,
    color: '#e05260',
    suffix: '%',
    includeZero: true,
  }),
);
const releaseLeakageRateOption = computed(() =>
  buildQualityBoardTrendRowsChartOption({
    title: '发布缺陷遗留率',
    subtitle: '各发布版本未关闭缺陷占比',
    rows: overview.value?.releaseLeakageRateRows,
    color: '#f59e0b',
    suffix: '%',
  }),
);
const developmentLeakageRateOption = computed(() =>
  buildQualityBoardTrendRowsChartOption({
    title: '开发缺陷遗留率',
    subtitle: '各发布版本集成测试缺陷占比',
    rows: overview.value?.developmentLeakageRateRows,
    color: '#2f80ed',
    suffix: '%',
  }),
);

async function loadProjectOptions() {
  const options = await api.getQualityBoardRdProjectOptions();
  projectOptions.value = options.options;
  selectedProjectName.value =
    selectedProjectName.value || options.defaultProjectName || options.options[0]?.value || 'CC2026R3';
}

async function loadOverview() {
  overview.value = await api.getQualityBoardOtherOverview(selectedProjectName.value);
}

async function loadPage() {
  loading.value = true;
  try {
    await loadProjectOptions();
    await loadOverview();
  } finally {
    loading.value = false;
    initialized.value = true;
  }
}

async function handleRefresh() {
  try {
    await loadPage();
    ElMessage.success('其他看板已刷新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '其他看板刷新失败');
  }
}

async function handleProjectChange() {
  loading.value = true;
  try {
    await loadOverview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '其他看板加载失败');
  } finally {
    loading.value = false;
  }
}

void loadPage().catch((error) => {
  initialized.value = true;
  loading.value = false;
  ElMessage.error(error instanceof Error ? error.message : '其他看板加载失败');
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-other">
      <header class="quality-board-other__hero">
        <div>
          <div class="quality-board-other__eyebrow">QUALITY ANALYTICS · 其他看板</div>
          <h2>质量专题分析</h2>
          <p>按功能、成员和版本维度分析缺陷数量、密度与遗留情况。</p>
        </div>
        <div class="quality-board-other__actions">
          <el-select
            v-model="selectedProjectName"
            class="quality-board-other__project-select"
            filterable
            placeholder="项目名称"
            :disabled="loading"
            @change="handleProjectChange"
          >
            <el-option v-for="option in projectOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button class="app-action-button app-action-button--refresh" :icon="Refresh" :loading="loading" @click="handleRefresh">
            刷新
          </el-button>
        </div>
      </header>

      <section class="quality-board-other__grid">
        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <span>FUNCTION · COUNT</span>
            <h3>功能缺陷数量</h3>
          </div>
          <EChartPanel :option="functionDefectCountOption" :loading="loading" :height="380" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <span>FUNCTION · DENSITY</span>
            <h3>功能缺陷密度</h3>
          </div>
          <EChartPanel :option="functionDefectDensityOption" :loading="loading" :height="380" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <span>PEOPLE · QUALITY</span>
            <h3>质量达人榜</h3>
          </div>
          <EChartPanel :option="qualityRankingOption" :loading="loading" :height="400" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <span>PEOPLE · OPEN DEFECTS</span>
            <h3>成员未修复缺陷率</h3>
          </div>
          <EChartPanel :option="memberUnresolvedRateOption" :loading="loading" :height="400" />
        </article>

        <article class="quality-board-other__panel quality-board-other__panel--trend">
          <div class="quality-board-other__panel-head">
            <span>RELEASE · LEAKAGE</span>
            <h3>发布缺陷遗留率</h3>
          </div>
          <EChartPanel :option="releaseLeakageRateOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-other__panel quality-board-other__panel--trend">
          <div class="quality-board-other__panel-head">
            <span>DEVELOPMENT · LEAKAGE</span>
            <h3>开发缺陷遗留率</h3>
          </div>
          <EChartPanel :option="developmentLeakageRateOption" :loading="loading" :height="360" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.quality-board-other {
  --other-ink: #182235;
  --other-muted: #68758a;
  display: grid;
  gap: 18px;
}

.quality-board-other__hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 26px 28px;
  border: 1px solid #e2e8f1;
  border-radius: 18px;
  background:
    linear-gradient(120deg, rgb(255 255 255 / 96%), rgb(246 250 255 / 96%)),
    repeating-linear-gradient(90deg, transparent 0 28px, rgb(47 128 237 / 5%) 28px 29px);
}

.quality-board-other__eyebrow,
.quality-board-other__panel-head span {
  color: #7c5ce7;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.quality-board-other__hero h2 {
  margin: 7px 0 5px;
  color: var(--other-ink);
  font-size: 27px;
  letter-spacing: -0.02em;
}

.quality-board-other__hero p {
  margin: 0;
  color: var(--other-muted);
  line-height: 1.7;
}

.quality-board-other__actions {
  display: flex;
  gap: 10px;
}

.quality-board-other__project-select {
  width: 220px;
}

.quality-board-other__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.quality-board-other__panel {
  min-width: 0;
  padding: 18px;
  border: 1px solid #e1e8f1;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 10px 28px rgb(30 55 90 / 5%);
}

.quality-board-other__panel--trend {
  background: linear-gradient(180deg, #fff, #fbfdff);
}

.quality-board-other__panel-head {
  display: grid;
  gap: 4px;
  margin-bottom: 10px;
}

.quality-board-other__panel-head h3 {
  margin: 0;
  color: var(--other-ink);
  font-size: 17px;
}

@media (max-width: 1100px) {
  .quality-board-other__grid { grid-template-columns: 1fr; }
}

@media (max-width: 760px) {
  .quality-board-other__hero { align-items: stretch; flex-direction: column; }
  .quality-board-other__actions { flex-wrap: wrap; }
  .quality-board-other__project-select { width: 100%; }
}
</style>
