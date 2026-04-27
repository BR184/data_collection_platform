<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type {
  CodeReviewMultiBoardBreakdownRowResponse,
  CodeReviewMultiBoardOverviewResponse,
  OptionItemResponse,
} from '../types/api';
import { CODE_REVIEW_SOURCE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';
import { useDataScope } from '../composables/useDataScope';
import {
  buildCodeReviewSummaryCards,
  buildModuleDensityChartOption,
  buildModuleVolumeChartOption,
  buildOwnerCompletionChartOption,
  buildOwnerDensityChartOption,
  completionRate,
  formatLines,
  formatMinutes,
  formatPercent,
} from './code-review-multi-board';

const initialized = ref(false);
const loading = ref(false);
const sourceOptions = ref<OptionItemResponse[]>([]);
const overview = ref<CodeReviewMultiBoardOverviewResponse>({
  source: '',
  sourceLabel: '',
  mergeRequestCount: 0,
  completedCount: 0,
  pendingCount: 0,
  averageCommentRate: null,
  totalDefectCount: 0,
  totalAddedLines: 0,
  defectDensityPerKloc: null,
  averageReviewDurationMinutes: null,
  averageAddedLines: null,
  moduleRows: [],
  ownerRows: [],
});

const sourceScope = useDataScope({
  provider: CODE_REVIEW_SOURCE_SCOPE_PROVIDER,
  options: computed(() => buildScopeOptions(sourceOptions.value)),
  mountToShell: true,
  loading,
});

const pageReady = computed(() => initialized.value);
const sourceDescription = computed(() =>
  overview.value.sourceLabel
    ? `当前展示 ${overview.value.sourceLabel} 数据源下的代码走查体量、缺陷密度和责任人分布。`
    : '当前暂无可展示的数据源。',
);
const summaryCards = computed(() => buildCodeReviewSummaryCards(overview.value));
const moduleDensityChartOption = computed(() => buildModuleDensityChartOption(overview.value));
const moduleVolumeChartOption = computed(() => buildModuleVolumeChartOption(overview.value));
const ownerDensityChartOption = computed(() => buildOwnerDensityChartOption(overview.value));
const ownerCompletionChartOption = computed(() => buildOwnerCompletionChartOption(overview.value));

watch(
  () => sourceScope.value.value,
  async () => {
    if (!initialized.value) {
      return;
    }
    await loadOverview();
  },
);

async function loadSourceOptions() {
  sourceOptions.value = await api.getCodeReviewMultiBoardSourceOptions();
}

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await api.getCodeReviewMultiBoardOverview(sourceScope.value.value || undefined);
  } finally {
    loading.value = false;
  }
}

async function refreshPage() {
  try {
    await Promise.all([loadSourceOptions(), loadOverview()]);
    ElMessage.success('代码走查多元看板已刷新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代码走查多元看板刷新失败');
  }
}

async function initializePage() {
  try {
    await loadSourceOptions();
    await loadOverview();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代码走查多元看板加载失败');
  } finally {
    initialized.value = true;
  }
}

void initializePage();
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="code-review-multi-board">
      <section class="code-review-multi-board__hero">
        <div>
          <div class="code-review-multi-board__eyebrow">代码走查 / 多元看板</div>
          <h2>代码走查质量概览</h2>
          <p>{{ sourceDescription }}</p>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="refreshPage">刷新</el-button>
      </section>

      <section class="code-review-multi-board__summary">
        <article v-for="card in summaryCards" :key="card.key" class="code-review-multi-board__summary-card">
          <span>{{ card.label }}</span>
          <strong>{{ card.value }}</strong>
        </article>
      </section>

      <section class="code-review-multi-board__grid">
        <article class="code-review-multi-board__panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>模块缺陷密度</h3>
              <p>优先判断当前数据源下最容易出现高密度缺陷的模块。</p>
            </div>
          </div>
          <EChartPanel :option="moduleDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="code-review-multi-board__panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>模块走查体量</h3>
              <p>用体量视角补足单纯看密度可能带来的误判。</p>
            </div>
          </div>
          <EChartPanel :option="moduleVolumeChartOption" :loading="loading" :height="320" />
        </article>

        <article class="code-review-multi-board__panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>责任人缺陷密度</h3>
              <p>更适合快速识别责任人维度上的质量风险。</p>
            </div>
          </div>
          <EChartPanel :option="ownerDensityChartOption" :loading="loading" :height="320" />
        </article>

        <article class="code-review-multi-board__panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>责任人完成率</h3>
              <p>避免只看处理量，不看进度和完成度。</p>
            </div>
          </div>
          <EChartPanel :option="ownerCompletionChartOption" :loading="loading" :height="320" />
        </article>
      </section>

      <section class="code-review-multi-board__table-grid">
        <article class="code-review-multi-board__table-panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>模块明细</h3>
              <p>保留表格是为了让图表能继续下沉到可验证的业务数据。</p>
            </div>
          </div>
          <el-table :data="overview.moduleRows" v-loading="loading" stripe border empty-text="当前暂无模块统计">
            <el-table-column prop="rowLabel" label="模块" min-width="180" />
            <el-table-column prop="mergeRequestCount" label="合并请求数" width="120" align="right" />
            <el-table-column prop="completedCount" label="已完成" width="100" align="right" />
            <el-table-column label="完成率" width="110" align="right">
              <template #default="{ row }">{{ formatPercent(completionRate(row.mergeRequestCount, row.completedCount)) }}</template>
            </el-table-column>
            <el-table-column label="注释率" width="120" align="right">
              <template #default="{ row }">{{ formatPercent(row.averageCommentRate) }}</template>
            </el-table-column>
            <el-table-column label="缺陷密度" width="120" align="right">
              <template #default="{ row }">{{ row.defectDensityPerKloc?.toFixed(2) ?? '-' }}</template>
            </el-table-column>
            <el-table-column prop="totalDefectCount" label="缺陷数" width="100" align="right" />
            <el-table-column label="总新增代码" width="120" align="right">
              <template #default="{ row }">{{ row.totalAddedLines }}</template>
            </el-table-column>
          </el-table>
        </article>

        <article class="code-review-multi-board__table-panel">
          <div class="code-review-multi-board__panel-head">
            <div>
              <h3>责任人明细</h3>
              <p>结合完成率、密度和体量看人，不再只给一个数量榜单。</p>
            </div>
          </div>
          <el-table :data="overview.ownerRows" v-loading="loading" stripe border empty-text="当前暂无责任人统计">
            <el-table-column prop="rowLabel" label="责任人" min-width="160" />
            <el-table-column prop="mergeRequestCount" label="合并请求数" width="120" align="right" />
            <el-table-column prop="completedCount" label="已完成" width="100" align="right" />
            <el-table-column label="完成率" width="110" align="right">
              <template #default="{ row }">{{ formatPercent(completionRate(row.mergeRequestCount, row.completedCount)) }}</template>
            </el-table-column>
            <el-table-column label="缺陷密度" width="120" align="right">
              <template #default="{ row }">{{ row.defectDensityPerKloc?.toFixed(2) ?? '-' }}</template>
            </el-table-column>
            <el-table-column label="平均走查时长" width="140" align="right">
              <template #default="{ row }">{{ formatMinutes(row.averageReviewDurationMinutes) }}</template>
            </el-table-column>
            <el-table-column label="平均新增代码" width="130" align="right">
              <template #default="{ row }">{{ formatLines(row.averageAddedLines) }}</template>
            </el-table-column>
          </el-table>
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.code-review-multi-board {
  display: grid;
  gap: 20px;
}

.code-review-multi-board__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.code-review-multi-board__eyebrow {
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.code-review-multi-board__hero h2 {
  margin: 8px 0 10px;
  font-size: 24px;
  color: #111827;
}

.code-review-multi-board__hero p {
  margin: 0;
  max-width: 720px;
  color: #667085;
  line-height: 1.7;
}

.code-review-multi-board__summary {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.code-review-multi-board__summary-card {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
  display: grid;
  gap: 8px;
}

.code-review-multi-board__summary-card span {
  font-size: 13px;
  color: #667085;
}

.code-review-multi-board__summary-card strong {
  font-size: 24px;
  line-height: 1.2;
  color: #111827;
}

.code-review-multi-board__grid,
.code-review-multi-board__table-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.code-review-multi-board__panel,
.code-review-multi-board__table-panel {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.code-review-multi-board__panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.code-review-multi-board__panel-head h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.code-review-multi-board__panel-head p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1180px) {
  .code-review-multi-board__summary,
  .code-review-multi-board__grid,
  .code-review-multi-board__table-grid {
    grid-template-columns: 1fr;
  }

  .code-review-multi-board__hero {
    flex-direction: column;
  }
}
</style>
