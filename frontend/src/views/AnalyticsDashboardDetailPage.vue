<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowLeft, Download } from '@element-plus/icons-vue';
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

const route = useRoute();
const router = useRouter();
const detail = ref<AnalyticsDashboardDetailResponse | null>(null);
const loading = ref(false);
const exportingKey = ref('');
const errorMessage = ref('');
let loadSequence = 0;

const dashboardKey = computed(() => String(route.params.dashboardKey ?? '').trim());
const detailViewKey = computed(() => String(route.params.detailViewKey ?? '').trim());

function routeQuery(): AnalyticsDashboardQuery {
  return Object.fromEntries(
    Object.entries(route.query).flatMap(([key, value]) => {
      const normalized = Array.isArray(value) ? value[0] : value;
      return normalized == null ? [] : [[key, String(normalized)]];
    }),
  );
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
      <div v-if="detail?.exports.length" class="analytics-detail-page__actions">
        <el-button
          v-for="action in detail.exports"
          :key="action.exportKey"
          :icon="Download"
          :loading="exportingKey === action.exportKey"
          @click.stop="exportDetail(action)"
        >
          {{ action.label }}
        </el-button>
      </div>
    </header>

    <el-alert v-if="errorMessage" type="error" :closable="false" :title="errorMessage" />
    <el-skeleton v-if="loading && !detail" :rows="8" animated />
    <el-empty v-else-if="!detail" description="当前范围暂无详情数据" />
    <template v-else>
      <el-empty v-if="!detail.records.length" description="当前页暂无详情数据" />
      <el-table v-else :data="detail.records" border stripe table-layout="fixed">
        <el-table-column
          v-for="column in detail.columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :width="column.width || undefined"
          min-width="120"
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
        layout="total, prev, pager, next"
        :current-page="detail.page"
        :page-size="detail.size"
        :total="detail.total"
        @current-change="changePage"
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

.analytics-detail-page__pagination {
  justify-self: end;
}
</style>
