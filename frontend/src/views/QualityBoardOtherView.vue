<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import PageStateShell from '../components/base/PageStateShell.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';
import { api } from '../api';
import type { CodeReviewMultiBoardOverviewResponse, StatisticBoardResponse } from '../types/api';
import {
  buildCodeReviewOwnerChartOption,
  buildCustomerFunctionChartOption,
  buildCustomerResponseChartOption,
} from './quality-board';
import { buildDelayCauseChartOption } from './system-test-multi-board';

const router = useRouter();

const initialized = ref(false);
const loading = ref(false);
const codeReviewOverview = ref<CodeReviewMultiBoardOverviewResponse | null>(null);
const responseBoard = ref<StatisticBoardResponse | null>(null);
const functionBoard = ref<StatisticBoardResponse | null>(null);
const delayBoard = ref<StatisticBoardResponse | null>(null);

const pageReady = computed(() => initialized.value);
const codeReviewOwnerChartOption = computed(() => buildCodeReviewOwnerChartOption(codeReviewOverview.value));
const customerResponseChartOption = computed(() => buildCustomerResponseChartOption(responseBoard.value));
const customerFunctionChartOption = computed(() => buildCustomerFunctionChartOption(functionBoard.value));
const delayCauseChartOption = computed(() => buildDelayCauseChartOption(delayBoard.value));

async function loadPage() {
  loading.value = true;
  try {
    const sources = await api.getCodeReviewMultiBoardSourceOptions();
    const preferredSource = sources.find((item) => item.value === 'cc')?.value ?? sources[0]?.value;
    const [codeReview, response, byFunction, delay] = await Promise.all([
      preferredSource ? api.getCodeReviewMultiBoardOverview(preferredSource) : Promise.resolve(null),
      api.getStatisticBoard('customer-issue-response-efficiency'),
      api.getStatisticBoard('customer-issue-by-function'),
      api.getStatisticBoard('system-test-delay-analysis'),
    ]);
    codeReviewOverview.value = codeReview;
    responseBoard.value = response;
    functionBoard.value = byFunction;
    delayBoard.value = delay;
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
      <section class="quality-board-other__hero">
        <div>
          <div class="quality-board-other__eyebrow">质量看板 / 其他看板</div>
          <h2>专题辅助视图</h2>
          <p>这里放跨域的辅助分析，不再和首页 KPI 混排。目标是让每张图都有清晰问题指向，而不是把能画的都画出来。</p>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="handleRefresh">刷新</el-button>
      </section>

      <section class="quality-board-other__grid">
        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <div>
              <h3>客户问题响应率</h3>
              <p>按模块快速判断哪里最容易拖慢首响效率。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/customer-issues/response-efficiency')">
              正式页
            </el-link>
          </div>
          <EChartPanel :option="customerResponseChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <div>
              <h3>客户问题功能热点</h3>
              <p>把问题最多的功能组合拉到前台，而不是先看长表。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/customer-issues/issue-by-function')">
              正式页
            </el-link>
          </div>
          <EChartPanel :option="customerFunctionChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <div>
              <h3>代码走查责任人密度</h3>
              <p>优先判断责任人维度的质量负载是否过于集中。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/code-review/multi-board')">
              正式页
            </el-link>
          </div>
          <EChartPanel :option="codeReviewOwnerChartOption" :loading="loading" :height="320" />
        </article>

        <article class="quality-board-other__panel">
          <div class="quality-board-other__panel-head">
            <div>
              <h3>系统测试延期原因</h3>
              <p>观察延期最常见的阻塞类型，适合周会快速扫一遍。</p>
            </div>
            <el-link underline="never" type="primary" @click="goTo('/question-metrics/delay-analysis')">
              正式页
            </el-link>
          </div>
          <EChartPanel :option="delayCauseChartOption" :loading="loading" :height="320" />
        </article>
      </section>
    </section>
  </PageStateShell>
</template>

<style scoped>
.quality-board-other {
  display: grid;
  gap: 20px;
}

.quality-board-other__hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.quality-board-other__hero > *,
.quality-board-other__panel {
  min-width: 0;
}

.quality-board-other__eyebrow {
  color: #4b5563;
  font-size: 12px;
  font-weight: 700;
}

.quality-board-other__hero h2 {
  margin: 8px 0 10px;
  font-size: 24px;
  color: #111827;
}

.quality-board-other__hero p {
  margin: 0;
  max-width: 760px;
  color: #667085;
  line-height: 1.7;
}

.quality-board-other__grid {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.quality-board-other__panel {
  padding: 18px 20px;
  border: 1px solid #e4e7ec;
  border-radius: 8px;
  background: #fff;
}

.quality-board-other__panel-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.quality-board-other__panel-head h3 {
  margin: 0;
  font-size: 16px;
  color: #111827;
}

.quality-board-other__panel-head p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1180px) {
  .quality-board-other__grid {
    grid-template-columns: 1fr;
  }

  .quality-board-other__hero {
    flex-direction: column;
  }
}
</style>
