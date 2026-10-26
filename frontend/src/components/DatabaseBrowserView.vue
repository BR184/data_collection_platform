<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { ElMessage } from '../element-plus-services';
import { Refresh, WarningFilled } from '@element-plus/icons-vue';
import { api } from '../api';
import { useAcceptedPageView } from '../composables/useAcceptedPageView';
import { authState } from '../composables/auth-state';
import StatisticBoardFreshnessBanner from './StatisticBoardFreshnessBanner.vue';
import type { DatabaseTableOption, DatabaseTableRowsResponse } from '../types/api';
import type { DatabaseTableRefreshResponse } from '../api-client/database-browser-api';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import SmartSelect from './base/SmartSelect.vue';
import SmartTableHeader from './base/SmartTableHeader.vue';
import BaseSearchInput from './base/BaseSearchInput.vue';
import PageStateShell from './base/PageStateShell.vue';
import TableFunctionBar from './base/TableFunctionBar.vue';
import { useRouteTableState } from '../composables/useRouteTableState';
import { getErrorMessage } from '../utils/user-message';

const tablesLoading = ref(false);
const refreshingTable = ref(false);
const tablesResolved = ref(false);
const firstRowsResolved = ref(false);
const tableOptions = ref<DatabaseTableOption[]>([]);
const keywordDraft = ref('');
const rowsResponse = ref<DatabaseTableRowsResponse | null>(null);
interface BrowserFrame { response: DatabaseTableRowsResponse; query: Readonly<Record<string, unknown>>; published: boolean }
const displayedFrame = shallowRef<BrowserFrame | null>(null);
const loadError = ref('');
let identityRevision = 0;
let pendingTablesLoad: Promise<void> | null = null;
const acceptedPage = useAcceptedPageView<BrowserFrame>({ pageKey: 'database-browser',
  load: () => { throw new Error('数据库内容需要捕获表和完整查询'); } });
acceptedPage.start();
watch(acceptedPage.accepted, (frame, previous) => {
  if (frame && frame.data === previous?.data) return;
  displayedFrame.value = frame?.data ?? null;
  rowsResponse.value = frame?.data.response ?? null;
}, { flush: 'sync' });
watch(() => JSON.stringify({ username: authState.currentUser.username, authenticated: authState.currentUser.authenticated,
  permissions: [...authState.currentUser.permissions].sort(), roles: [...authState.currentUser.roleCodes].sort() }), (next, previous) => {
  const oldFrame = displayedFrame.value;
  identityRevision += 1; displayedFrame.value = null; rowsResponse.value = null; tableOptions.value = [];
  if (oldFrame && authState.currentUser.authenticated && JSON.parse(next).username === JSON.parse(previous).username) {
    void loadTables(); if (!oldFrame.published) void reload();
  }
}, { flush: 'sync' });
const displayedQuery = computed(() => displayedFrame.value?.query ?? route.query);
const displayedSortBy = computed(() => String(displayedQuery.value.sortBy ?? ''));
const displayedSortOrder = computed(() => String(displayedQuery.value.sortOrder ?? ''));
const viewportHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 1080);

const {
  route,
  keyword,
  isTableLoading,
  patchQuery,
  debouncedPatchQuery,
  cancelDebouncedQuery,
  reload,
  bindLoader,
} = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: '',
    sortOrder: '',
    keyword: '',
  },
  watchedQueryKeys: ['table'],
  debounceMs: 300,
});

const selectedTable = computed(() => String(route.query.table ?? ''));

const columns = computed(() => rowsResponse.value?.columns ?? []);
const rows = computed(() => rowsResponse.value?.rows ?? []);
const total = computed(() => rowsResponse.value?.total ?? 0);
const currentSortSummary = computed(() => {
  if (!displayedSortBy.value || !displayedSortOrder.value) {
    return '';
  }
  const fieldLabel = columns.value.find((column) => column.key === displayedSortBy.value)?.label ?? '当前字段';
  return `${fieldLabel} / ${displayedSortOrder.value === 'asc' ? '升序' : '降序'}`;
});
const selectedOption = computed(() => tableOptions.value.find((option) => option.tableName === selectedTable.value) ?? null);
const tableKind = computed(() => rowsResponse.value?.tableKind ?? selectedOption.value?.tableKind ?? 'LOCAL');
const currentTableRefreshable = computed(() => rowsResponse.value?.refreshable ?? selectedOption.value?.refreshable ?? false);
const smartTableOptions = computed(() =>
  tableOptions.value.map((option) => ({
    label: `${tableKindLabel(option.tableKind)} ${option.label} (${option.tableName})`,
    value: option.tableName,
  })),
);
const pageReady = computed(() => {
  if (!tablesResolved.value) {
    return false;
  }
  return true;
});
const showInitialTableSkeleton = computed(
  () => tableOptions.value.length > 0 && (!selectedTable.value || !firstRowsResolved.value),
);
const syncStatusTagType = computed(() => {
  const status = rowsResponse.value?.syncStatus ?? selectedOption.value?.syncStatus;
  switch (status) {
    case 'SYNCING':
      return 'warning';
    case 'ERROR':
      return 'danger';
    default:
      return 'info';
  }
});
const syncStatusLabels: Record<string, string> = {
  IDLE: '空闲',
  SYNCING: '同步中',
  ERROR: '异常',
  SUCCESS: '成功',
  FAILED: '失败',
  PENDING: '待执行',
  RUNNING: '执行中',
  RETRYING: '重试中',
  TIMEOUT: '已超时',
  CANCELLED: '已取消',
};
const tableHeight = computed(() => Math.max(460, viewportHeight.value - 272));
function loadTables(): Promise<void> {
  if (pendingTablesLoad) return pendingTablesLoad;
  const capturedIdentity = identityRevision;
  tablesLoading.value = true;
  pendingTablesLoad = api.getDatabaseTables().then(async tables => {
    if (capturedIdentity !== identityRevision) return;
    tableOptions.value = tables;
    if (!selectedTable.value && tables.length) await patchQuery({ table: tables[0].tableName }, 'replace');
  }).catch(error => {
    if (capturedIdentity === identityRevision) loadError.value = getErrorMessage(error, '数据库表目录加载失败');
  }).finally(() => { tablesLoading.value = false; tablesResolved.value = true; pendingTablesLoad = null; });
  return pendingTablesLoad;
}

async function handleSearch() {
  cancelDebouncedQuery();
  await patchQuery({
    keyword: keywordDraft.value.trim(),
    page: 1,
  });
}

async function handleTableSelectChange(value: string | string[]) {
  await patchQuery({
    table: Array.isArray(value) ? value[0] ?? '' : value,
    page: 1,
    sortBy: '',
    sortOrder: '',
  });
}

async function handleReset() {
  cancelDebouncedQuery();
  keywordDraft.value = '';
  await patchQuery({
    keyword: '',
    page: 1,
    sortBy: '',
    sortOrder: '',
  });
}

async function handleRefresh() {
  if (!currentTableRefreshable.value) {
    ElMessage.info(tableKind.value === 'SOURCE' ? '来源表为实时只读预览，不支持在此刷新' : '当前本地表不需要触发镜像刷新');
    return;
  }
  refreshingTable.value = true;
  try {
    let refreshResult: DatabaseTableRefreshResponse | null = null;
    const visibleTable = rowsResponse.value?.tableName ?? selectedTable.value;
    if (visibleTable) {
      refreshResult = await api.refreshDatabaseTable(visibleTable);
    }
    await loadTables();
    if ((await reload()).status === 'committed') {
      ElMessage.success(refreshSubmissionMessage(refreshResult));
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '数据库表刷新失败'));
  } finally {
    refreshingTable.value = false;
  }
}

function refreshSubmissionMessage(result: DatabaseTableRefreshResponse | null) {
  if (result?.message?.trim()) {
    return result.message;
  }
  const runSuffix = result?.runId ? `，同步任务 #${result.runId}` : '';
  switch (result?.status) {
    case 'SUCCESS':
      return acceptedPage.accepted.value?.metadata.sourceState === 'CURRENT'
        ? `当前表数据已刷新${runSuffix}`
        : `镜像抓取已完成，完整数据版本正在更新${runSuffix}`;
    case 'RUNNING':
      return `刷新请求已提交，正在同步执行${runSuffix}`;
    case 'DEDUPED':
      return `刷新请求已合并到现有同步任务，完成后生效${runSuffix}`;
    case 'QUEUED':
      return `刷新请求已提交，正在排队等待同步执行${runSuffix}`;
    default:
      return result?.accepted === false ? '当前表暂无可刷新的同步任务' : `刷新请求已提交${runSuffix}`;
  }
}

async function handleSizeChange(nextSize: number) {
  await patchQuery({
    pageSize: nextSize,
    page: 1,
  });
}

async function handleCurrentChange(nextPage: number) {
  await patchQuery({
    page: nextPage,
  });
}

async function handleSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
  await patchQuery({
    sortBy: payload.prop || '',
    sortOrder: payload.order === 'ascending' ? 'asc' : payload.order === 'descending' ? 'desc' : '',
    page: 1,
  });
}

function formatCellValue(value: unknown) {
  if (value == null || value === '') {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function formatTime(value?: string | null) {
  return value || '-';
}

function syncStatusText() {
  const status = rowsResponse.value?.syncStatus || selectedOption.value?.syncStatus || 'IDLE';
  return syncStatusLabels[status] ?? '未知状态';
}

function tableKindLabel(kind?: string | null) {
  switch (kind) {
    case 'SOURCE':
      return '来源表';
    case 'MIRROR':
      return '镜像表';
    case 'LOCAL':
      return '本地表';
    default:
      return '数据表';
  }
}

function tableKindTagType(kind?: string | null) {
  switch (kind) {
    case 'SOURCE':
      return 'warning';
    case 'MIRROR':
      return 'success';
    default:
      return 'info';
  }
}

function handleResize() {
  viewportHeight.value = window.innerHeight;
}

watch(keyword, (nextKeyword) => {
  keywordDraft.value = nextKeyword;
}, { immediate: true });

watch(keywordDraft, (nextKeyword, previousKeyword) => {
  if (nextKeyword === previousKeyword || nextKeyword === keyword.value) {
    return;
  }
  debouncedPatchQuery({
    keyword: nextKeyword.trim(),
    page: 1,
  });
});

bindLoader(async ({ query, isCurrent }) => {
  const capturedIdentity = identityRevision;
  const tableName = String(query.table ?? '');
  if (!tableName) return null;
  if (!tableOptions.value.some(option => option.tableName === tableName)) await loadTables();
  if (!isCurrent() || capturedIdentity !== identityRevision) return null;
  const option = tableOptions.value.find(option => option.tableName === tableName);
  if (!option || typeof option.publishedReadRequired !== 'boolean') throw new Error('表目录缺少有效的内容读取类型');
  const request = { tableName, page: parseQueryPositiveInteger(query.page, 1), size: parseQueryPositiveInteger(query.pageSize, 20),
    keyword: String(query.keyword ?? '') || undefined, sortField: String(query.sortBy ?? '') || undefined,
    sortOrder: (String(query.sortOrder ?? '') || undefined) as 'asc' | 'desc' | undefined };
  if (option.publishedReadRequired) {
    const loaded = await acceptedPage.prepare({ tableName }, async (_scope, read) => ({ query, published: true,
      response: await api.getDatabaseTableRows(request, read) }));
    if (!loaded && isCurrent()) throw acceptedPage.lastError.value ?? new Error('表内容准备已取消');
    return { published: true, frame: acceptedPage.accepted.value?.data ?? null, capturedIdentity };
  }
  // 当前管理/源库预览不受同步资格门控；不能伪造完整发布身份或把失败当空表。
  const response = await api.getDatabaseTableRows(request);
  return { published: false, frame: { response, query, published: false } as BrowserFrame, capturedIdentity };
}, result => {
  if (!result || result.capturedIdentity !== identityRevision) return;
  if (!result.published) {
    acceptedPage.clearPrivateData(); displayedFrame.value = result.frame; rowsResponse.value = result.frame?.response ?? null;
  }
  firstRowsResolved.value = true; loadError.value = '';
}, (error, { query }) => {
  if (String(query.table ?? '')) firstRowsResolved.value = true;
  loadError.value = getErrorMessage(error, '表数据更新失败，已保留当前完整数据');
});

function parseQueryPositiveInteger(value: unknown, fallback: number) {
  const parsed = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

onMounted(async () => {
  window.addEventListener('resize', handleResize);
  await loadTables();
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  acceptedPage.dispose();
});
</script>

<template>
  <PageStateShell :ready="pageReady">
    <template #skeleton>
      <div class="db-browser-card">
        <div class="db-toolbar db-toolbar--skeleton">
          <el-card shadow="never" class="db-table-card page-skeleton-card">
            <el-skeleton animated>
              <template #template>
                <div class="page-skeleton-stack">
                  <el-skeleton-item variant="rect" style="width: 42%; height: 40px" />
                  <el-skeleton-item variant="rect" style="width: 28%; height: 40px" />
                  <el-skeleton-item variant="rect" style="width: 100%; height: 420px" />
                </div>
              </template>
            </el-skeleton>
          </el-card>
        </div>
      </div>
    </template>

    <div class="db-browser-card">
      <StatisticBoardFreshnessBanner v-if="displayedFrame?.published" v-bind="acceptedPage.accepted.value?.metadata" />
      <el-alert v-if="loadError" :title="loadError" type="warning" :closable="false" />
      <TableFunctionBar>
        <template #status>
          <div class="db-toolbar-filters">
            <SmartSelect
              :model-value="selectedTable"
              class="db-table-select"
              placeholder="请选择要查看的本地表"
              :loading="tablesLoading"
              :options="smartTableOptions"
              @change="handleTableSelectChange"
            />

            <BaseSearchInput
              :model-value="keywordDraft"
              class="db-search-input"
              placeholder="输入关键字搜索当前表"
              @update:model-value="keywordDraft = $event"
              @search="handleSearch"
              @clear="handleReset"
            />
          </div>
        </template>

        <template #actions>
          <div class="db-toolbar-actions">
            <el-button type="primary" class="app-action-button app-action-button--query" @click="handleSearch">搜索</el-button>
            <el-button class="app-action-button app-action-button--reset" @click="handleReset">重置</el-button>
            <el-button
              class="app-action-button app-action-button--refresh"
              :icon="Refresh"
              :loading="refreshingTable"
              :disabled="!currentTableRefreshable"
              @click="handleRefresh"
            >
              重新加载表数据
            </el-button>
            <span class="db-refresh-note">
              {{ currentTableRefreshable ? '刷新会触发当前镜像表重新抓取源端数据' : '来源表为管理员只读预览，本地表无需刷新' }}
            </span>
          </div>
        </template>
      </TableFunctionBar>

      <el-card shadow="never" class="db-table-card">
        <template #header>
          <div class="db-table-header">
            <div class="db-table-context">
              <el-tag v-if="rowsResponse?.label || selectedOption?.label" effect="plain" round>
                {{ rowsResponse?.label || selectedOption?.label }}
              </el-tag>
              <el-tag :type="tableKindTagType(tableKind)" effect="plain" round>{{ tableKindLabel(tableKind) }}</el-tag>
              <span class="db-table-name">{{ rowsResponse?.tableName || selectedTable || '-' }}</span>
            </div>

            <div class="db-table-meta">
              <template v-if="currentSortSummary">
                <span class="db-sort-label">当前排序</span>
                <el-tag effect="plain" type="info" round>{{ currentSortSummary }}</el-tag>
              </template>
              <el-tag :type="syncStatusTagType" round>{{ syncStatusText() }}</el-tag>
              <SyncMetaBadge :value="formatTime(rowsResponse?.lastSyncTime || selectedOption?.lastSyncTime)" />
              <span class="db-table-meta-text">总数：{{ total }}</span>
            </div>
          </div>
        </template>

        <div v-if="showInitialTableSkeleton" class="db-initial-loading">
          <el-skeleton animated>
            <template #template>
              <div class="page-skeleton-stack">
                <el-skeleton-item variant="text" style="width: 24%" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 360px" />
                <el-skeleton-item variant="text" style="width: 18%" />
              </div>
            </template>
          </el-skeleton>
        </div>

        <el-empty v-else-if="!selectedTable" description="当前没有可查看的本地表" />

        <template v-else-if="rowsResponse">
          <el-alert
            v-if="rowsResponse?.statusMessage"
            class="db-status-alert"
            :icon="WarningFilled"
            type="warning"
            :closable="false"
            :title="rowsResponse.statusMessage"
          />

          <el-table
            v-loading="isTableLoading && !rowsResponse"
            :data="rows"
            border
            stripe
            class="db-table"
            :height="tableHeight"
            @sort-change="handleSortChange"
          >
            <el-table-column
              v-for="column in columns"
              :key="column.key"
              :prop="column.key"
              :label="column.label"
              :sortable="column.sortable ? 'custom' : false"
              min-width="150"
              show-overflow-tooltip
            >
              <template #header>
                <SmartTableHeader :label="column.label" />
              </template>
              <template #default="{ row }">
                <span class="db-cell-text">{{ formatCellValue(row[column.key]) }}</span>
              </template>
            </el-table-column>

          </el-table>

          <div class="db-pagination">
            <el-pagination
              background
              layout="total, sizes, prev, pager, next"
              :current-page="rowsResponse?.page ?? parseQueryPositiveInteger(displayedQuery.page, 1)"
              :page-size="rowsResponse?.size ?? parseQueryPositiveInteger(displayedQuery.pageSize, 20)"
              :page-sizes="[10, 20, 50, 100]"
              :total="total"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </template>
      </el-card>
    </div>
  </PageStateShell>

</template>

<style scoped>
.db-browser-card {
  display: grid;
  gap: 12px;
}

.db-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.db-toolbar-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1 1 720px;
  flex-wrap: wrap;
}

.db-table-select {
  width: 360px;
}

.db-search-input {
  width: 320px;
}

.db-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.db-refresh-note {
  color: rgba(15, 23, 42, 0.48);
  font-size: 12px;
  line-height: 1.4;
}

.db-table-card {
  border-radius: 12px;
}

.db-table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.db-table-context {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.db-table-name {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}

.db-table-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.db-sort-label {
  color: rgba(15, 23, 42, 0.62);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.db-table-meta-text {
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
  line-height: 1.4;
}

.db-status-alert {
  margin-bottom: 12px;
}

.db-initial-loading {
  min-height: 420px;
  display: grid;
  align-content: start;
}

.db-table {
  width: 100%;
}

.db-cell-text {
  display: inline-block;
  max-width: 100%;
}

.db-pagination {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

</style>
