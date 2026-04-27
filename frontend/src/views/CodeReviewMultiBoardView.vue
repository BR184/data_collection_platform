<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import { api } from '../api';
import type {
  CodeReviewMultiBoardBreakdownRowResponse,
  CodeReviewMultiBoardOverviewResponse,
  OptionItemResponse,
} from '../types/api';
import { CODE_REVIEW_SOURCE_SCOPE_PROVIDER, buildScopeOptions } from '../composables/data-scope-providers';
import { useDataScope } from '../composables/useDataScope';

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
    ? `当前展示 ${overview.value.sourceLabel} 数据源下的代码走查统计摘要。`
    : '当前暂无可展示的数据源。',
);

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

function formatMetric(value?: number | null, suffix = '') {
  if (value == null) {
    return '-';
  }
  return `${value.toFixed(2)}${suffix}`;
}

function formatPercent(value?: number | null) {
  return formatMetric(value, '%');
}

function formatMinutes(value?: number | null) {
  return formatMetric(value, ' 分钟');
}

function formatLines(value?: number | null) {
  return formatMetric(value, ' 行');
}

function completionRate(row: CodeReviewMultiBoardBreakdownRowResponse) {
  if (!row.mergeRequestCount) {
    return '0.00%';
  }
  return `${((row.completedCount / row.mergeRequestCount) * 100).toFixed(2)}%`;
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
      <el-card shadow="never" class="code-review-multi-board__hero">
        <div class="code-review-multi-board__hero-main">
          <div>
            <div class="code-review-multi-board__eyebrow">代码走查 / 多元看板</div>
            <h2>代码走查质量概览</h2>
            <p>{{ sourceDescription }}</p>
          </div>
          <el-button :icon="Refresh" :loading="loading" @click="refreshPage">刷新</el-button>
        </div>
      </el-card>

      <div class="code-review-multi-board__stats">
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>数据源</span>
          <strong>{{ overview.sourceLabel || '-' }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>合并请求数</span>
          <strong>{{ overview.mergeRequestCount }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>已完成走查</span>
          <strong>{{ overview.completedCount }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>待处理</span>
          <strong>{{ overview.pendingCount }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>平均注释率</span>
          <strong>{{ formatPercent(overview.averageCommentRate) }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>缺陷总数</span>
          <strong>{{ overview.totalDefectCount }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>平均走查时长</span>
          <strong>{{ formatMinutes(overview.averageReviewDurationMinutes) }}</strong>
        </el-card>
        <el-card shadow="never" class="code-review-multi-board__stat-card">
          <span>平均新增代码</span>
          <strong>{{ formatLines(overview.averageAddedLines) }}</strong>
        </el-card>
      </div>

      <div class="code-review-multi-board__grid">
        <el-card shadow="never" class="code-review-multi-board__table-card">
          <template #header>
            <div class="code-review-multi-board__table-head">
              <div>
                <div class="code-review-multi-board__table-title">模块分布</div>
                <div class="code-review-multi-board__table-desc">按模块查看走查体量、缺陷和效率分布。</div>
              </div>
            </div>
          </template>
          <el-table :data="overview.moduleRows" v-loading="loading" stripe border empty-text="当前暂无模块统计">
            <el-table-column prop="rowLabel" label="模块" min-width="180" />
            <el-table-column prop="mergeRequestCount" label="合并请求数" width="120" align="right" />
            <el-table-column prop="completedCount" label="已完成" width="100" align="right" />
            <el-table-column label="完成率" width="110" align="right">
              <template #default="{ row }">{{ completionRate(row) }}</template>
            </el-table-column>
            <el-table-column label="平均注释率" width="120" align="right">
              <template #default="{ row }">{{ formatPercent(row.averageCommentRate) }}</template>
            </el-table-column>
            <el-table-column prop="totalDefectCount" label="缺陷数" width="100" align="right" />
            <el-table-column label="平均走查时长" width="140" align="right">
              <template #default="{ row }">{{ formatMinutes(row.averageReviewDurationMinutes) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" class="code-review-multi-board__table-card">
          <template #header>
            <div class="code-review-multi-board__table-head">
              <div>
                <div class="code-review-multi-board__table-title">责任人分布</div>
                <div class="code-review-multi-board__table-desc">按标注责任人查看当前数据源中的走查质量负载。</div>
              </div>
            </div>
          </template>
          <el-table :data="overview.ownerRows" v-loading="loading" stripe border empty-text="当前暂无责任人统计">
            <el-table-column prop="rowLabel" label="责任人" min-width="160" />
            <el-table-column prop="mergeRequestCount" label="合并请求数" width="120" align="right" />
            <el-table-column prop="completedCount" label="已完成" width="100" align="right" />
            <el-table-column label="完成率" width="110" align="right">
              <template #default="{ row }">{{ completionRate(row) }}</template>
            </el-table-column>
            <el-table-column label="平均注释率" width="120" align="right">
              <template #default="{ row }">{{ formatPercent(row.averageCommentRate) }}</template>
            </el-table-column>
            <el-table-column prop="totalDefectCount" label="缺陷数" width="100" align="right" />
            <el-table-column label="平均新增代码" width="130" align="right">
              <template #default="{ row }">{{ formatLines(row.averageAddedLines) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>
    </section>
  </PageStateShell>
</template>

<style scoped>
.code-review-multi-board {
  display: grid;
  gap: 16px;
}

.code-review-multi-board__hero,
.code-review-multi-board__table-card,
.code-review-multi-board__stat-card {
  border: 1px solid #e5e7eb;
}

.code-review-multi-board__hero-main {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.code-review-multi-board__hero-main h2 {
  margin: 6px 0 8px;
  font-size: 20px;
  color: #111827;
}

.code-review-multi-board__hero-main p {
  margin: 0;
  color: #6b7280;
  line-height: 1.7;
}

.code-review-multi-board__eyebrow {
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.code-review-multi-board__stats {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.code-review-multi-board__stat-card :deep(.el-card__body) {
  display: grid;
  gap: 8px;
}

.code-review-multi-board__stat-card span {
  font-size: 13px;
  color: #6b7280;
}

.code-review-multi-board__stat-card strong {
  font-size: 18px;
  line-height: 1.3;
  color: #111827;
}

.code-review-multi-board__grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.code-review-multi-board__table-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.code-review-multi-board__table-title {
  font-size: 15px;
  font-weight: 700;
  color: #111827;
}

.code-review-multi-board__table-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
}

@media (max-width: 1280px) {
  .code-review-multi-board__grid {
    grid-template-columns: 1fr;
  }
}
</style>
