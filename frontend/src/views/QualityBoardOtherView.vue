<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from '../element-plus-services';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type { OptionItemResponse, QualityBoardOtherOverviewResponse } from '../types/api';
import {
  buildFixUserSeverityChartOption,
  buildQualityBoardCards,
  buildQualityBoardValueRowsChartOption,
} from './quality-board';

const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const selectedProjectName = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const overview = ref<QualityBoardOtherOverviewResponse | null>(null);

const pageReady = computed(() => initialized.value);
const cards = computed(() => buildQualityBoardCards({ overview: overview.value?.summary ?? null }));
const assigneeDefectDensityChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '按走查人统计代码走查缺陷密度',
    subtitle: '单位：K/LOC',
    rows: overview.value?.assigneeDefectDensityRows,
    color: '#409eff',
  }),
);
const authorDefectDensityChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '按被走查人统计代码走查缺陷密度',
    subtitle: '单位：K/LOC',
    rows: overview.value?.authorDefectDensityRows,
    color: '#67c23a',
  }),
);
const fixUserSeverityChartOption = computed(() => buildFixUserSeverityChartOption(overview.value?.fixUserSeverityRows));
const frequencyCodeSubmissionChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '代码提交频次',
    subtitle: '按提交人统计合并请求数量',
    rows: overview.value?.frequencyCodeSubmissionRows,
    color: '#e6a23c',
  }),
);
const defectRepairUserChartOption = computed(() =>
  buildQualityBoardValueRowsChartOption({
    title: '指派人剩余缺陷数量',
    subtitle: '按当前打开缺陷统计',
    rows: overview.value?.defectRepairUserRows,
    color: '#f56c6c',
  }),
);

async function loadProjectOptions() {
  const options = await api.getQualityBoardRdProjectOptions();
  projectOptions.value = options.options;
  selectedProjectName.value = selectedProjectName.value || options.defaultProjectName;
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

function goTo(path: string) {
  void router.push(path);
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
      <section class="quality-board-other__toolbar">
        <div class="quality-board-other__title">
          <div class="quality-board-other__eyebrow">质量看板 / 其他看板</div>
          <h2>其他看板</h2>
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
            <el-option
              v-for="option in projectOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
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
      </section>

      <section class="quality-board-other__summary">
        <article
          v-for="card in cards"
          :key="card.key"
          class="quality-board-other__metric"
          :data-tone="card.tone ?? 'default'"
        >
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
          <small v-if="card.hint">{{ card.hint }}</small>
        </article>
      </section>

      <section class="quality-board-other__grid">
        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <h3>按走查人统计代码走查缺陷密度</h3>
            <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看详情</el-link>
          </div>
          <EChartPanel :option="assigneeDefectDensityChartOption" :loading="loading" :height="340" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <h3>按被走查人统计代码走查缺陷密度</h3>
            <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看详情</el-link>
          </div>
          <EChartPanel :option="authorDefectDensityChartOption" :loading="loading" :height="340" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <h3>按修复人统计缺陷数</h3>
            <el-link underline="never" type="primary" @click="goTo('/question-metrics/issue-search')">查看详情</el-link>
          </div>
          <EChartPanel :option="fixUserSeverityChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <h3>代码提交频次</h3>
            <el-link underline="never" type="primary" @click="goTo('/code-review/illegal-records')">查看详情</el-link>
          </div>
          <EChartPanel :option="frequencyCodeSubmissionChartOption" :loading="loading" :height="360" />
        </article>

        <article class="quality-board-other__panel quality-board-other__panel--wide">
          <div class="quality-board-other__panel-head">
            <h3>指派人剩余缺陷数量</h3>
            <el-link underline="never" type="primary" @click="goTo('/question-metrics/issue-search')">查看详情</el-link>
          </div>
          <EChartPanel :option="defectRepairUserChartOption" :loading="loading" :height="360" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.quality-board-other {
  display: grid;
  gap: 16px;
}

.quality-board-other__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
}

.quality-board-other__title {
  min-width: 0;
}

.quality-board-other__eyebrow {
  color: #606266;
  font-size: 12px;
  font-weight: 700;
}

.quality-board-other__toolbar h2 {
  margin: 6px 0 0;
  font-size: 24px;
  color: #303133;
}

.quality-board-other__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quality-board-other__project-select {
  width: 220px;
}

.quality-board-other__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.quality-board-other__metric {
  display: grid;
  gap: 8px;
  min-width: 0;
  padding: 14px 16px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
}

.quality-board-other__metric span {
  color: #606266;
  font-size: 13px;
}

.quality-board-other__metric strong {
  color: #303133;
  font-size: 24px;
  line-height: 1.1;
}

.quality-board-other__metric small {
  color: #909399;
}

.quality-board-other__metric[data-tone='success'] strong {
  color: var(--el-color-success);
}

.quality-board-other__metric[data-tone='warning'] strong {
  color: var(--el-color-warning);
}

.quality-board-other__metric[data-tone='danger'] strong {
  color: var(--el-color-danger);
}

.quality-board-other__grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quality-board-other__panel {
  min-width: 0;
  padding: 16px 18px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  background: #fff;
}

.quality-board-other__panel--wide {
  grid-column: 1 / -1;
}

.quality-board-other__panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.quality-board-other__panel-head h3 {
  margin: 0;
  color: #303133;
  font-size: 16px;
}

@media (max-width: 1280px) {
  .quality-board-other__summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .quality-board-other__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .quality-board-other__toolbar,
  .quality-board-other__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .quality-board-other__summary {
    grid-template-columns: 1fr;
  }

  .quality-board-other__project-select {
    width: 100%;
  }
}
</style>
