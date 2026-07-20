<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowLeft } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import { analyticsDashboardApi } from '../api-client/analytics-dashboard-api';
import type {
  AnalyticsDashboardDetailResponse,
  AnalyticsDashboardExportAction,
  AnalyticsDashboardQuery,
} from '../types/api';
import { downloadBlob } from '../utils/csv-download';
import AnalyticsDashboardDetailCell from '../components/dashboard/AnalyticsDashboardDetailCell.vue';
import { shouldShowAnalyticsDetailPagination } from '../components/dashboard/analytics-dashboard-detail-cell';
import ExportActionMenu from '../components/base/ExportActionMenu.vue';
import EChartPanel from '../components/charts/EChartPanel.vue';

const route = useRoute();
const router = useRouter();
const detail = ref<AnalyticsDashboardDetailResponse | null>(null);
const loading = ref(false);
const exportingKey = ref('');
const errorMessage = ref('');
let loadSequence = 0;
const DEFAULT_DETAIL_PAGE_SIZE = 10;

const dashboardKey = computed(() => String(route.meta.analyticsDashboardKey ?? '').trim());
const detailViewKey = computed(() => String(route.params.detailViewKey ?? '').trim());
const testingPhaseFilter = computed(() => {
  if (detailViewKey.value !== 'assignee-remaining-defects') {
    return null;
  }
  return (detail.value?.filters ?? []).find((filter) => filter.key === 'projectName') ?? null;
});
const exportActions = computed(() =>
  (detail.value?.exports ?? []).map((action) => ({
    key: action.exportKey,
    label: action.label,
  })),
);
const exportLoading = computed(() => Boolean(exportingKey.value));

function routeQuery(): AnalyticsDashboardQuery {
  const query = Object.fromEntries(
    Object.entries(route.query).flatMap(([key, value]) => {
      const normalized = Array.isArray(value) ? value[0] : value;
      return normalized == null ? [] : [[key, String(normalized)]];
    }),
  );
  if (!query.page) {
    query.page = '1';
  }
  if (!query.size) {
    query.size = String(DEFAULT_DETAIL_PAGE_SIZE);
  }
  return query;
}

async function loadDetail() {
  const sequence = ++loadSequence;
  errorMessage.value = '';
  if (!dashboardKey.value || !detailViewKey.value) {
    detail.value = null;
    errorMessage.value = '详情地址缺少看板或视图标识。';
    return;
  }
  loading.value = true;
  try {
    const response = await analyticsDashboardApi.getDetails(
      dashboardKey.value,
      detailViewKey.value,
      routeQuery(),
    );
    if (sequence === loadSequence) {
      detail.value = response;
    }
  } catch (error) {
    if (sequence === loadSequence) {
      detail.value = null;
      errorMessage.value = error instanceof Error ? error.message : '详情数据加载失败';
    }
  } finally {
    if (sequence === loadSequence) {
      loading.value = false;
    }
  }
}

function changePage(page: number) {
  void router.replace({
    query: { ...route.query, page: String(page) },
  });
}

function changePageSize(size: number) {
  void router.replace({
    query: { ...route.query, page: '1', size: String(size) },
  });
}

function changeTestingPhase(value: unknown) {
  const projectName = String(value ?? '').trim();
  if (!projectName) {
    return;
  }
  const query = { ...route.query, projectName };
  delete query.page;
  delete query.assigneeName;
  void router.replace({ query });
}

async function exportDetail(action: AnalyticsDashboardExportAction) {
  if (exportingKey.value) {
    return;
  }
  exportingKey.value = action.exportKey;
  try {
    const file = await analyticsDashboardApi.export(
      dashboardKey.value,
      action.exportKey,
      routeQuery(),
    );
    downloadBlob(file.blob, file.filename || `${detail.value?.title || action.label}.xlsx`);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '导出失败';
  } finally {
    exportingKey.value = '';
  }
}

function handleExportSelection(exportKey: string) {
  const action = detail.value?.exports.find((item) => item.exportKey === exportKey);
  if (action) {
    void exportDetail(action);
  }
}

watch(() => route.fullPath, () => void loadDetail(), { immediate: true });
</script>

<template>
  <section class="analytics-detail-page">
    <header class="analytics-detail-page__header">
      <div class="analytics-detail-page__title-group">
        <el-button text :icon="ArrowLeft" @click="router.back()">返回看板</el-button>
        <h2>{{ detail?.title || '看板数据详情' }}</h2>
        <p v-if="detail?.description">{{ detail.description }}</p>
      </div>
      <div
        v-if="testingPhaseFilter || exportActions.length"
        class="analytics-detail-page__toolbar"
      >
        <label v-if="testingPhaseFilter" class="analytics-detail-page__phase-filter">
          <span>{{ testingPhaseFilter.label }}</span>
          <el-select
            :model-value="testingPhaseFilter.value"
            filterable
            :disabled="loading"
            placeholder="选择测试阶段"
            @change="changeTestingPhase"
          >
            <el-option
              v-for="option in testingPhaseFilter.options"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </label>
        <div v-if="exportActions.length" class="analytics-detail-page__actions">
          <ExportActionMenu
            :actions="exportActions"
            :loading="exportLoading"
            @select="handleExportSelection"
          />
        </div>
      </div>
    </header>

    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" />
    <el-skeleton v-if="loading && !detail" :rows="8" animated />
    <el-empty v-else-if="!detail" description="当前范围暂无详情数据" />
    <template v-else>
      <section v-if="detail.chart" class="analytics-detail-page__chart">
        <header>
          <h3>{{ detail.chart.title }}</h3>
          <p v-if="detail.chart.subtitle">{{ detail.chart.subtitle }}</p>
        </header>
        <EChartPanel
          :option="detail.chart.option"
          :loading="loading"
          :height="detail.chart.height || 420"
        />
      </section>
      <el-empty v-if="!detail.records.length" description="当前页暂无详情数据" />
      <el-table
        v-else
        class="analytics-detail-page__table"
        :data="detail.records"
        border
        stripe
        table-layout="fixed"
      >
        <el-table-column
          v-for="column in detail.columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :min-width="column.width || 120"
          show-overflow-tooltip
        >
          <template #default="scope">
            <AnalyticsDashboardDetailCell :column="column" :value="scope.row[column.key]" />
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="shouldShowAnalyticsDetailPagination(detail)"
        class="analytics-detail-page__pagination"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :current-page="detail.page"
        :page-size="detail.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="detail.total"
        @current-change="changePage"
        @size-change="changePageSize"
      />
    </template>
  </section>
</template>

<style scoped>
.analytics-detail-page {
  display: grid;
  gap: 18px;
  padding: 22px;
  background: #fff;
  border: 1px solid #e5eaf1;
  border-radius: 14px;
  box-shadow: 0 12px 32px rgb(15 23 42 / 5%);
}

.analytics-detail-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.analytics-detail-page__title-group h2 {
  margin: 8px 0 0;
  color: #0f172a;
  font-size: 22px;
}

.analytics-detail-page__title-group p {
  margin: 6px 0 0;
  color: #64748b;
}

.analytics-detail-page__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.analytics-detail-page__chart {
  min-width: 0;
  padding: 18px 20px 14px;
  border: 1px solid #e5eaf1;
  border-radius: 12px;
  background: #fbfdff;
}

.analytics-detail-page__chart header {
  margin-bottom: 8px;
}

.analytics-detail-page__chart h3 {
  margin: 0;
  color: #172033;
  font-size: 17px;
}

.analytics-detail-page__chart p {
  margin: 5px 0 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
}

.analytics-detail-page__toolbar {
  display: grid;
  justify-items: end;
  gap: 10px;
}

.analytics-detail-page__phase-filter {
  display: grid;
  grid-template-columns: auto minmax(180px, 240px);
  align-items: center;
  gap: 10px;
  color: #475569;
  font-size: 14px;
  font-weight: 600;
}

.analytics-detail-page__pagination {
  justify-self: end;
}

.analytics-detail-page__table {
  width: 100%;
}

@media (max-width: 760px) {
  .analytics-detail-page__header {
    flex-direction: column;
  }

  .analytics-detail-page__toolbar,
  .analytics-detail-page__actions {
    width: 100%;
    justify-items: stretch;
    justify-content: flex-start;
  }

  .analytics-detail-page__phase-filter {
    grid-template-columns: 1fr;
    width: 100%;
  }
}
</style>
