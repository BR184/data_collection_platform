<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useAcceptedPageView } from '../composables/useAcceptedPageView';
import { authState } from '../composables/auth-state';
import StatisticBoardFreshnessBanner from '../components/StatisticBoardFreshnessBanner.vue';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import { analyticsDashboardApi } from '../api-client/analytics-dashboard-api';
import { qualityBoardApi } from '../api-client/quality-board-api';
import PageStateShell from '../components/base/PageStateShell.vue';
import DashboardChartCard from '../components/dashboard/DashboardChartCard.vue';
import DashboardRuleDrawer from '../components/dashboard/DashboardRuleDrawer.vue';
import { navigateAnalyticsDetail } from '../components/dashboard/analytics-read-navigation';
import type { EChartPointClickEvent } from '../components/charts/echart-panel-events';
import { ElMessage } from '../element-plus-services';
import type {
  AnalyticsDashboardChart,
  AnalyticsDashboardQuery,
  AnalyticsDashboardResponse,
  AnalyticsDashboardRule,
  AnalyticsDashboardRulesResponse,
  OptionItemResponse,
  QualityBoardProjectOptionsResponse,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import { getErrorMessage } from '../utils/user-message';

const DASHBOARD_KEY = 'quality-board-other';
const router = useRouter();
const initialized = ref(false);
const loading = ref(false);
const exportingKey = ref('');
const projectOptions = ref<OptionItemResponse[]>([]);
const functionCountProjectName = ref('');
const functionDensityProjectName = ref('');
const qualityRankingProjectName = ref('');
const memberUnresolvedProjectName = ref('');
const dashboard = ref<AnalyticsDashboardResponse | null>(null);
const rules = ref<AnalyticsDashboardRulesResponse | null>(null);
const selectedRule = ref<AnalyticsDashboardRule | null>(null);
const ruleDrawerVisible = ref(false);
const pageLoadError = ref('');
interface QualityOtherFrame {
  dashboard: AnalyticsDashboardResponse;
  rules: AnalyticsDashboardRulesResponse;
  options: QualityBoardProjectOptionsResponse;
  query: AnalyticsDashboardQuery;
}
const acceptedPage = useAcceptedPageView<QualityOtherFrame>({ pageKey: DASHBOARD_KEY,
  load: () => { throw new Error('需要捕获完整质量页查询后准备'); } });
const readMetadata = computed(() => acceptedPage.accepted.value?.metadata);
acceptedPage.start();
onBeforeUnmount(acceptedPage.dispose);
watch(() => authState.currentUser?.username, acceptedPage.clearPrivateData);
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  dashboard.value = frame?.data.dashboard ?? null;
  rules.value = frame?.data.rules ?? null;
  projectOptions.value = frame?.data.options.options ?? [];
  if (frame) {
    functionCountProjectName.value = String(frame.data.query.functionCountProjectName ?? '');
    functionDensityProjectName.value = String(frame.data.query.functionDensityProjectName ?? '');
    qualityRankingProjectName.value = String(frame.data.query.qualityRankingProjectName ?? '');
    memberUnresolvedProjectName.value = String(frame.data.query.memberUnresolvedProjectName ?? '');
    if (ruleDrawerVisible.value && selectedRule.value) {
      selectedRule.value = frame.data.rules.rules.find(rule => rule.key === selectedRule.value?.key) ?? null;
    }
  } else { selectedRule.value = null; ruleDrawerVisible.value = false; }
}, { flush: 'sync' });

const pageReady = computed(() => initialized.value);
const ruleByKey = computed<Record<string, AnalyticsDashboardRule>>(() =>
  Object.fromEntries((rules.value?.rules ?? []).map((rule) => [rule.key, rule])),
);
const chartByKey = computed<Record<string, AnalyticsDashboardChart>>(() =>
  Object.fromEntries((dashboard.value?.charts ?? []).map((chart) => [chart.key, chart])),
);

const topics = computed(() => [
  {
    key: 'function-defect-count',
    chart: chartByKey.value['function-defect-count'],
    model: functionCountProjectName,
    selectorLabel: '缺陷统计版本',
  },
  {
    key: 'function-defect-density',
    chart: chartByKey.value['function-defect-density'],
    model: functionDensityProjectName,
    selectorLabel: '密度统计版本',
  },
  {
    key: 'quality-ranking',
    chart: chartByKey.value['quality-ranking'],
    model: qualityRankingProjectName,
    selectorLabel: '达人榜版本',
  },
  {
    key: 'member-unresolved-rate',
    chart: chartByKey.value['member-unresolved-rate'],
    model: memberUnresolvedProjectName,
    selectorLabel: '未修复率版本',
  },
  {
    key: 'release-leakage-rate',
    chart: chartByKey.value['release-leakage-rate'],
    model: null,
    selectorLabel: '',
  },
  {
    key: 'development-leakage-rate',
    chart: chartByKey.value['development-leakage-rate'],
    model: null,
    selectorLabel: '',
  },
]);

function dashboardQuery(): AnalyticsDashboardQuery {
  return {
    functionCountProjectName: functionCountProjectName.value,
    functionDensityProjectName: functionDensityProjectName.value,
    qualityRankingProjectName: qualityRankingProjectName.value,
    memberUnresolvedProjectName: memberUnresolvedProjectName.value,
  };
}

let loadSequence = 0;
async function loadPage() {
  const ticket = ++loadSequence;
  loading.value = true;
  pageLoadError.value = '';
  const requested = Object.freeze({ ...dashboardQuery() });
  try {
    const loaded = await acceptedPage.prepare({}, async (_scope, read) => {
      const options = await qualityBoardApi.getQualityBoardOtherProjectOptions(read);
      const fallback = options.defaultProjectName || options.options[0]?.value || '';
      const query = Object.fromEntries(Object.entries(requested).map(([key, value]) => [key, value || fallback]));
      const [dashboard, rules] = await Promise.all([
        analyticsDashboardApi.getDashboard(DASHBOARD_KEY, query, read),
        analyticsDashboardApi.getRules(DASHBOARD_KEY, query, read),
      ]);
      return { dashboard, rules, query, options };
    });
    if (ticket !== loadSequence) return;
    if (!loaded) throw acceptedPage.lastError.value ?? new Error('页面准备已取消');
  } finally {
    if (ticket === loadSequence) { loading.value = false; initialized.value = true;
    }
  }
}

async function refresh(showSuccess = true) {
  try {
    await loadPage();
    if (showSuccess) {
      ElMessage.success('其他看板已刷新');
    }
  } catch (error) {
    pageLoadError.value = getErrorMessage(error, acceptedPage.accepted.value
      ? '其他看板加载失败，已保留当前完整数据' : '尚无完整产出，请检查数据更新状态');
    ElMessage.error(pageLoadError.value);
  }
}

async function openDetail(chart: AnalyticsDashboardChart, point?: EChartPointClickEvent) {
  if (!chart.detail) {
    return;
  }
  if (point && !Object.keys(point.detailParams).length) {
    return;
  }
  const action = point
    ? {
        ...chart.detail,
        params: { ...chart.detail.params, ...point.detailParams },
      }
    : chart.detail;
  try {
    await navigateAnalyticsDetail(acceptedPage, router, DASHBOARD_KEY, action,
      frame => Object.fromEntries(Object.entries(frame.query).map(([key, value]) => [key, String(value)])));
  } catch (error) { ElMessage.error(getErrorMessage(error, '详情打开失败')); }
}

function openTopicPoint(
  chart: AnalyticsDashboardChart | undefined,
  point: EChartPointClickEvent,
) {
  if (chart) {
    openDetail(chart, point);
  }
}

function openRule(rule: AnalyticsDashboardRule | null) {
  if (!rule) {
    return;
  }
  selectedRule.value = rule;
  ruleDrawerVisible.value = true;
}

async function exportChart(chart: AnalyticsDashboardChart) {
  if (!chart.export || exportingKey.value) {
    return;
  }
  exportingKey.value = chart.export.exportKey;
  try {
    await acceptedPage.readAccepted((frame, read) => analyticsDashboardApi.export(
      DASHBOARD_KEY, chart.export!.exportKey, { ...frame.query, ...chart.detail?.params }, read),
      file => downloadBlob(file.blob, file.filename || `${chart.title}.xlsx`));
  } catch (error) {
    ElMessage.error(getErrorMessage(error, 'Excel 导出失败'));
  } finally {
    exportingKey.value = '';
  }
}

void refresh(false);
</script>

<template>
  <PageStateShell :ready="pageReady" min-height="calc(100vh - 160px)">
    <section class="quality-board-other">
      <el-alert v-if="pageLoadError" :title="pageLoadError" type="error" :closable="false" show-icon />
      <StatisticBoardFreshnessBanner v-if="readMetadata" :data-as-of="readMetadata.dataAsOf"
        :pending-updates="readMetadata.pendingUpdates" :source-state="readMetadata.sourceState"
        :source-state-detail="readMetadata.sourceStateDetail" :escalated="readMetadata.escalated" />
      <header class="quality-board-other__hero">
        <div>
          <div class="quality-board-other__eyebrow">QUALITY ANALYTICS · 其他看板</div>
          <h2>{{ dashboard?.title || '质量专题分析' }}</h2>
          <p>{{ dashboard?.subtitle || '按功能、成员和版本维度分析缺陷数量、密度与遗留情况。' }}</p>
        </div>
        <el-button
          class="app-action-button app-action-button--refresh"
          :icon="Refresh"
          :loading="loading"
          @click="refresh(true)"
        >
          刷新全部
        </el-button>
      </header>

      <section class="quality-board-other__scope-note">
        <strong>统计范围</strong>
        <span>前四个专题按所选版本统计；发布与开发遗留率统计全部启用版本。</span>
      </section>

      <section class="quality-board-other__grid">
        <article v-for="topic in topics" :key="topic.key" class="quality-board-other__topic">
          <div v-if="topic.model" class="quality-board-other__topic-filter">
            <span>{{ topic.selectorLabel }}</span>
            <el-select
              v-model="topic.model.value"
              filterable
              placeholder="请选择版本"
              @change="refresh(false)"
            >
              <el-option
                v-for="option in projectOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </div>
          <DashboardChartCard
            v-if="topic.chart"
            :chart="topic.chart"
            :rule="topic.chart.ruleKey ? ruleByKey[topic.chart.ruleKey] : null"
            :loading="(loading && !dashboard) || exportingKey === topic.chart.export?.exportKey"
            :height="topic.key.includes('rate') ? 360 : 400"
            @title-click="openDetail"
            @point-click="(point) => openTopicPoint(topic.chart, point)"
            @rule-click="openRule"
            @export="exportChart"
          />
          <el-skeleton v-else :rows="8" animated />
        </article>
      </section>
    </section>
    <DashboardRuleDrawer v-model="ruleDrawerVisible" :rule="selectedRule" />
  </PageStateShell>
</template>

<style scoped>
.quality-board-other {
  --other-ink: #172033;
  --other-muted: #64748b;
  display: grid;
  gap: 18px;
}

.quality-board-other__hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 27px 30px;
  overflow: hidden;
  border: 1px solid #dfe7f2;
  border-radius: 18px;
  background:
    radial-gradient(circle at 85% 10%, rgb(37 99 235 / 12%), transparent 34%),
    linear-gradient(128deg, #fff 0%, #f7faff 58%, #eef5ff 100%);
  box-shadow: 0 14px 34px rgb(30 64 175 / 6%);
}

.quality-board-other__eyebrow {
  color: #6d5ce7;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.quality-board-other__hero h2 {
  margin: 7px 0 5px;
  color: var(--other-ink);
  font-size: 28px;
  letter-spacing: -0.02em;
}

.quality-board-other__hero p,
.quality-board-other__scope-note span {
  margin: 0;
  color: var(--other-muted);
  line-height: 1.7;
}

.quality-board-other__scope-note {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 17px;
  border: 1px solid #dce8f8;
  border-radius: 12px;
  background: #f7faff;
  font-size: 13px;
}

.quality-board-other__scope-note strong {
  flex: none;
  color: #1d4ed8;
}

.quality-board-other__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 17px;
}

.quality-board-other__topic {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.quality-board-other__topic-filter {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  min-height: 34px;
  padding: 0 4px;
  color: #526176;
  font-size: 13px;
}

.quality-board-other__topic-filter :deep(.el-select) {
  width: 210px;
}

.quality-board-other__topic :deep(.dashboard-chart-card) {
  height: 100%;
}

@media (max-width: 1120px) {
  .quality-board-other__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .quality-board-other__hero {
    align-items: stretch;
    flex-direction: column;
  }

  .quality-board-other__scope-note {
    align-items: flex-start;
    flex-direction: column;
  }

  .quality-board-other__topic-filter {
    align-items: stretch;
    flex-direction: column;
  }

  .quality-board-other__topic-filter :deep(.el-select) {
    width: 100%;
  }
}
</style>
