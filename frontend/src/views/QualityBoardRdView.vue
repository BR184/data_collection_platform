<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from '../element-plus-services';
import { Download, Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import { downloadBlob } from '../utils/csv-download';
import type { OptionItemResponse, QualityBoardRdDashboardResponse } from '../types/api';
import {
  buildFixUserSeverityChartOption,
  buildQualityBoardCards,
  buildQualityBoardValueRowsChartOption,
} from './quality-board';

const router = useRouter();
const initialized = ref(false);
const loading = ref(false);
const exportLoadingKey = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const selectedProjectName = ref('');
const codeReviewSource = ref('cc');
const dashboard = ref<QualityBoardRdDashboardResponse | null>(null);

const pageReady = computed(() => initialized.value);
const codeReviewSourceOptions = computed(() => dashboard.value?.codeReviewSourceOptions ?? []);
const hasDgmSource = computed(() => codeReviewSourceOptions.value.some((option) => option.value === 'dgm'));
const cards = computed(() =>
  buildQualityBoardCards({ overview: dashboard.value?.summary ?? null })
    .filter((card) => card.key !== 'code-review-dgm' || hasDgmSource.value),
);
const sourceLabel = computed(() =>
  codeReviewSourceOptions.value.find((option) => option.value === codeReviewSource.value)?.label ?? 'CC',
);
const assigneeDefectDensityChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '按走查人统计代码走查缺陷密度',
    subtitle: `当前数据源：${sourceLabel.value}`,
    rows: dashboard.value?.assigneeDefectDensityRows,
    color: '#2f80ed',
    suffix: ' K/LOC',
  }),
);
const authorDefectDensityChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '按被走查人统计代码走查缺陷密度',
    subtitle: `当前数据源：${sourceLabel.value}`,
    rows: dashboard.value?.authorDefectDensityRows,
    color: '#19a974',
    suffix: ' K/LOC',
  }),
);
const fixUserSeverityChartOption = computed(() =>
  buildFixUserSeverityChartOption(dashboard.value?.fixUserSeverityRows),
);
const frequencyCodeSubmissionChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '代码提交频次',
    subtitle: `当前数据源：${sourceLabel.value}`,
    rows: dashboard.value?.frequencyCodeSubmissionRows,
    color: '#f59e0b',
  }),
);
const defectRepairUserChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '指派人剩余缺陷数量',
    subtitle: '按当前未关闭系统测试缺陷统计',
    rows: dashboard.value?.defectRepairUserRows,
    color: '#e05260',
  }),
);

async function loadProjectOptions() {
  const options = await api.getQualityBoardRdProjectOptions();
  projectOptions.value = options.options;
  selectedProjectName.value =
    selectedProjectName.value || options.defaultProjectName || options.options[0]?.value || 'CC2026R3';
}

async function loadDashboard() {
  dashboard.value = await api.getQualityBoardRdDashboard(selectedProjectName.value, codeReviewSource.value);
  codeReviewSource.value = dashboard.value.codeReviewSource;
}

async function loadPage() {
  loading.value = true;
  try {
    await loadProjectOptions();
    await loadDashboard();
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
  ElMessage.warning('研发质量看板加载失败');
}

async function handleFilterChange() {
  loading.value = true;
  try {
    await loadDashboard();
  } catch (error) {
    console.warn('研发质量看板筛选失败', error);
    ElMessage.error('研发质量看板加载失败');
  } finally {
    loading.value = false;
  }
}

function goTo(path: string) {
  void router.push(path);
}

function codeReviewRecordSource(cardKey: string) {
  if (cardKey === 'code-review-cc') {
    return 'cc';
  }
  if (cardKey === 'code-review-dgm' && hasDgmSource.value) {
    return 'dgm';
  }
  return '';
}

async function handleCodeReviewRecordExport(source: string) {
  if (!source) {
    return;
  }
  exportLoadingKey.value = `records-${source}`;
  try {
    const workbook = await api.exportQualityBoardRdCodeReviewRecords(selectedProjectName.value, source);
    downloadBlob(workbook, `${source === 'dgm' ? 'DGM库' : 'CC库'}-${selectedProjectName.value}-代码走查数据.xlsx`);
    ElMessage.success('代码走查数据已导出');
  } catch (error) {
    console.warn('代码走查数据导出失败', error);
    ElMessage.error('代码走查数据导出失败');
  } finally {
    exportLoadingKey.value = '';
  }
}

async function handleChartExport(chartKey: string, filename: string) {
  exportLoadingKey.value = `chart-${chartKey}`;
  try {
    const workbook = await api.exportQualityBoardRdChart(selectedProjectName.value, codeReviewSource.value, chartKey);
    downloadBlob(workbook, filename);
    ElMessage.success('图表数据已导出');
  } catch (error) {
    console.warn('图表数据导出失败', error);
    ElMessage.error('图表数据导出失败');
  } finally {
    exportLoadingKey.value = '';
  }
}

void loadPage().then((success) => {
  if (!success) {
    ElMessage.warning('研发质量看板加载失败');
  }
});
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-rd">
      <header class="quality-board-rd__hero">
        <div>
          <div class="quality-board-rd__eyebrow">QUALITY SIGNALS · 研发质量</div>
          <h2>研发质量一屏概览</h2>
          <p>汇总评审、代码走查、集成测试与系统测试质量指标。</p>
        </div>
        <div class="quality-board-rd__actions">
          <el-select
            v-model="selectedProjectName"
            class="quality-board-rd__project-select"
            filterable
            placeholder="项目名称"
            :disabled="loading"
            @change="handleFilterChange"
          >
            <el-option v-for="item in projectOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-button class="app-action-button app-action-button--refresh" :icon="Refresh" :loading="loading" @click="handleRefresh">
            刷新
          </el-button>
        </div>
      </header>

      <section class="quality-board-rd__summary">
        <article v-for="card in cards" :key="card.key" class="quality-board-rd__summary-card" :data-tone="card.tone ?? 'default'">
          <div class="quality-board-rd__summary-card-head">
            <span>{{ card.label }}</span>
            <el-button
              v-if="codeReviewRecordSource(card.key)"
              class="quality-board-rd__icon-button"
              :icon="Download"
              text
              circle
              :loading="exportLoadingKey === `records-${codeReviewRecordSource(card.key)}`"
              @click="handleCodeReviewRecordExport(codeReviewRecordSource(card.key))"
            />
          </div>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="quality-board-rd__section-head">
        <div>
          <span>CODE REVIEW</span>
          <h3>代码走查质量</h3>
        </div>
        <el-radio-group
          v-if="codeReviewSourceOptions.length > 1"
          v-model="codeReviewSource"
          size="small"
          :disabled="loading"
          @change="handleFilterChange"
        >
          <el-radio-button v-for="option in codeReviewSourceOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
      </section>

      <section class="quality-board-rd__grid">
        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <h3>按走查人统计代码走查缺陷密度</h3>
            <div class="quality-board-rd__panel-actions">
              <el-button
                :icon="Download"
                text
                :loading="exportLoadingKey === 'chart-assignee-defect-density'"
                @click="handleChartExport('assignee-defect-density', '按走查人统计代码走查缺陷密度.xlsx')"
              >
                导出
              </el-button>
              <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看明细</el-link>
            </div>
          </div>
          <EChartPanel :option="assigneeDefectDensityChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <h3>按被走查人统计代码走查缺陷密度</h3>
            <div class="quality-board-rd__panel-actions">
              <el-button
                :icon="Download"
                text
                :loading="exportLoadingKey === 'chart-author-defect-density'"
                @click="handleChartExport('author-defect-density', '按被走查人统计代码走查缺陷密度.xlsx')"
              >
                导出
              </el-button>
              <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看明细</el-link>
            </div>
          </div>
          <EChartPanel :option="authorDefectDensityChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <h3>按修复人统计缺陷数</h3>
            <div class="quality-board-rd__panel-actions">
              <el-button
                :icon="Download"
                text
                :loading="exportLoadingKey === 'chart-fix-user-severity'"
                @click="handleChartExport('fix-user-severity', '按修复人统计缺陷数.xlsx')"
              >
                导出
              </el-button>
              <el-link underline="never" type="primary" @click="goTo('/question-metrics/issue-search')">查看议题</el-link>
            </div>
          </div>
          <EChartPanel :option="fixUserSeverityChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <h3>代码提交频次</h3>
            <div class="quality-board-rd__panel-actions">
              <el-button
                :icon="Download"
                text
                :loading="exportLoadingKey === 'chart-frequency-code-submission'"
                @click="handleChartExport('frequency-code-submission', '代码提交频次.xlsx')"
              >
                导出
              </el-button>
              <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看明细</el-link>
            </div>
          </div>
          <EChartPanel :option="frequencyCodeSubmissionChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-rd__panel">
          <div class="quality-board-rd__panel-head">
            <h3>指派人剩余缺陷数量</h3>
            <div class="quality-board-rd__panel-actions">
              <el-button
                :icon="Download"
                text
                :loading="exportLoadingKey === 'chart-defect-repair-user'"
                @click="handleChartExport('defect-repair-user', '指派人剩余缺陷数量.xlsx')"
              >
                导出
              </el-button>
              <el-link underline="never" type="primary" @click="goTo('/question-metrics/issue-search')">查看议题</el-link>
            </div>
          </div>
          <EChartPanel :option="defectRepairUserChartOption" :loading="loading" :height="360" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.quality-board-rd {
  --board-ink: #172033;
  --board-muted: #677289;
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
    radial-gradient(circle at 92% 18%, rgb(47 128 237 / 14%), transparent 30%),
    linear-gradient(135deg, #fbfdff 0%, #f3f8ff 100%);
}

.quality-board-rd__hero::after {
  position: absolute;
  right: 28%;
  bottom: -42px;
  width: 180px;
  height: 90px;
  border: 18px solid rgb(25 169 116 / 8%);
  border-radius: 50%;
  content: '';
}

.quality-board-rd__eyebrow,
.quality-board-rd__section-head span {
  color: #2f80ed;
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
  position: relative;
  z-index: 1;
  display: flex;
  gap: 10px;
}

.quality-board-rd__project-select {
  width: 220px;
}

.quality-board-rd__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.quality-board-rd__summary-card {
  --card-accent: #8a97aa;
  position: relative;
  display: grid;
  gap: 9px;
  min-height: 88px;
  padding: 16px 18px;
  border: 1px solid #e3e9f2;
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgb(255 255 255 / 96%), rgb(255 255 255 / 100%)),
    radial-gradient(circle at 92% 18%, color-mix(in srgb, var(--card-accent) 14%, transparent), transparent 34%);
  box-shadow: 0 8px 24px rgb(30 55 90 / 5%);
}

.quality-board-rd__summary-card span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--board-muted);
  font-size: 13px;
}

.quality-board-rd__summary-card span::before {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--card-accent);
  content: '';
}

.quality-board-rd__summary-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 28px;
}

.quality-board-rd__icon-button {
  flex: 0 0 auto;
}

.quality-board-rd__summary-card strong {
  color: var(--board-ink);
  font-size: 23px;
  font-variant-numeric: tabular-nums;
}

.quality-board-rd__summary-card[data-tone='success'] { --card-accent: #17a673; }
.quality-board-rd__summary-card[data-tone='warning'] { --card-accent: #d98a00; }
.quality-board-rd__summary-card[data-tone='danger'] { --card-accent: #d94b59; }

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

.quality-board-rd__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.quality-board-rd__panel {
  min-width: 0;
  padding: 18px;
  border: 1px solid #e1e8f1;
  border-radius: 15px;
  background: #fff;
  box-shadow: 0 10px 28px rgb(30 55 90 / 5%);
}

.quality-board-rd__panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.quality-board-rd__panel-head h3 {
  margin: 0;
  color: var(--board-ink);
  font-size: 16px;
}

.quality-board-rd__panel-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
}

@media (max-width: 1200px) {
  .quality-board-rd__summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-board-rd__grid { grid-template-columns: 1fr; }
}

@media (max-width: 760px) {
  .quality-board-rd__hero { align-items: stretch; flex-direction: column; }
  .quality-board-rd__actions { flex-wrap: wrap; }
  .quality-board-rd__project-select { width: 100%; }
  .quality-board-rd__summary { grid-template-columns: 1fr; }
}
</style>
