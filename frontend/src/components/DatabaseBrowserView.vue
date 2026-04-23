<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Edit, Refresh, WarningFilled } from '@element-plus/icons-vue';
import { api, type DatabaseTableOption, type DatabaseTableRowsResponse } from '../api';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import SmartSelect from './base/SmartSelect.vue';
import BaseSearchInput from './base/BaseSearchInput.vue';
import PageStateShell from './base/PageStateShell.vue';
import { useRouteTableState } from '../composables/useRouteTableState';

const tablesLoading = ref(false);
const tablesResolved = ref(false);
const firstRowsResolved = ref(false);
const tableOptions = ref<DatabaseTableOption[]>([]);
const keywordDraft = ref('');
const rowsResponse = ref<DatabaseTableRowsResponse | null>(null);
const viewportHeight = ref(typeof window !== 'undefined' ? window.innerHeight : 1080);

const {
  route,
  page,
  pageSize,
  sortBy,
  sortOrder,
  keyword,
  isTableLoading,
  patchQuery,
  debouncedPatchQuery,
  cancelDebouncedQuery,
  bindLoader,
} = useRouteTableState({
  defaults: {
    page: 1,
    pageSize: 20,
    sortBy: '',
    sortOrder: '',
    keyword: '',
  },
  debounceMs: 300,
});

const selectedTable = computed(() => String(route.query.table ?? ''));

const columns = computed(() => rowsResponse.value?.columns ?? []);
const rows = computed(() => rowsResponse.value?.rows ?? []);
const total = computed(() => rowsResponse.value?.total ?? 0);
const selectedOption = computed(() => tableOptions.value.find((option) => option.tableName === selectedTable.value) ?? null);
const smartTableOptions = computed(() =>
  tableOptions.value.map((option) => ({
    label: `${option.label} (${option.tableName})`,
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
const isCollectFormTable = computed(() => selectedTable.value === 'collect_form_records');
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
const tableHeight = computed(() => Math.max(460, viewportHeight.value - 272));
const editDialogVisible = ref(false);
const editSaving = ref(false);
const editingContext = reactive({
  gitlabBaseUrl: '',
  projectId: '',
  requestIid: '',
  resourceType: '',
  resourceId: '',
  templateCode: '',
  createdAt: '',
  updatedAt: '',
});
const editForm = reactive({
  id: 0,
  formTitle: '',
  reviewer: '',
  reviewDurationMinutes: 0,
  specificationScore: 0,
  logicScore: 0,
  performanceScore: 0,
  designScore: 0,
  otherScore: 0,
  remark: '',
  deleted: false,
});

async function loadTables() {
  tablesLoading.value = true;
  try {
    const tables = await api.getDatabaseTables();
    tableOptions.value = tables;
    if (!selectedTable.value && tables.length > 0) {
      await patchQuery({ table: tables[0].tableName }, 'replace');
    }
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    tablesLoading.value = false;
    tablesResolved.value = true;
  }
}

async function loadRows(showError = true) {
  if (!selectedTable.value) {
    rowsResponse.value = null;
    return;
  }
  try {
    rowsResponse.value = await api.getDatabaseTableRows({
      tableName: selectedTable.value,
      page: page.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      sortField: sortBy.value || undefined,
      sortOrder: sortOrder.value || undefined,
    });
  } catch (error) {
    if (showError) {
      ElMessage.error((error as Error).message);
    }
  } finally {
    if (selectedTable.value) {
      firstRowsResolved.value = true;
    }
  }
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
  await loadTables();
  await loadRows();
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

function normalizeNumber(value: unknown) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function openCollectFormEditor(row: Record<string, unknown>) {
  editForm.id = normalizeNumber(row.id);
  editForm.formTitle = String(row.form_title ?? '');
  editForm.reviewer = String(row.reviewer ?? '');
  editForm.reviewDurationMinutes = normalizeNumber(row.review_duration_minutes);
  editForm.specificationScore = normalizeNumber(row.specification_score);
  editForm.logicScore = normalizeNumber(row.logic_score);
  editForm.performanceScore = normalizeNumber(row.performance_score);
  editForm.designScore = normalizeNumber(row.design_score);
  editForm.otherScore = normalizeNumber(row.other_score);
  editForm.remark = String(row.remark ?? '');
  editForm.deleted = Boolean(row.deleted);

  editingContext.gitlabBaseUrl = String(row.gitlab_base_url ?? '-');
  editingContext.projectId = String(row.project_id ?? '-');
  editingContext.requestIid = String(row.request_iid ?? '-');
  editingContext.resourceType = String(row.resource_type ?? '-');
  editingContext.resourceId = String(row.resource_id ?? '-');
  editingContext.templateCode = String(row.template_code ?? '-');
  editingContext.createdAt = String(row.created_at ?? '-');
  editingContext.updatedAt = String(row.updated_at ?? '-');
  editDialogVisible.value = true;
}

async function saveCollectFormEdit() {
  if (!editForm.id) {
    ElMessage.warning('缺少记录 ID，无法更新');
    return;
  }
  editSaving.value = true;
  try {
    await api.updateCollectFormRecord({
      id: editForm.id,
      formTitle: editForm.formTitle,
      reviewer: editForm.reviewer,
      reviewDurationMinutes: editForm.reviewDurationMinutes,
      specificationScore: editForm.specificationScore,
      logicScore: editForm.logicScore,
      performanceScore: editForm.performanceScore,
      designScore: editForm.designScore,
      otherScore: editForm.otherScore,
      remark: editForm.remark,
      deleted: editForm.deleted,
    });
    ElMessage.success('collect_form_records 已更新');
    editDialogVisible.value = false;
    await loadRows();
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    editSaving.value = false;
  }
}

function syncStatusText() {
  return rowsResponse.value?.syncStatus || selectedOption.value?.syncStatus || 'IDLE';
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

bindLoader(async () => {
  await loadRows(false);
});

onMounted(async () => {
  window.addEventListener('resize', handleResize);
  await loadTables();
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
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
      <div class="db-toolbar">
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

        <div class="db-toolbar-actions">
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="handleReset">重置</el-button>
          <el-button :icon="Refresh" @click="handleRefresh">刷新</el-button>
        </div>
      </div>

      <el-card shadow="never" class="db-table-card">
        <template #header>
          <div class="db-table-header">
            <div class="db-table-context">
              <el-tag v-if="rowsResponse?.label || selectedOption?.label" effect="plain" round>
                {{ rowsResponse?.label || selectedOption?.label }}
              </el-tag>
              <span class="db-table-name">{{ rowsResponse?.tableName || selectedTable || '-' }}</span>
            </div>

            <div class="db-table-meta">
              <el-tag :type="syncStatusTagType" round>{{ syncStatusText() }}</el-tag>
              <SyncMetaBadge :value="formatTime(rowsResponse?.lastSyncTime || selectedOption?.lastSyncTime)" />
              <span class="db-table-meta-text">Total: {{ total }}</span>
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

        <template v-else>
          <el-alert
            v-if="rowsResponse?.statusMessage"
            class="db-status-alert"
            :icon="WarningFilled"
            type="warning"
            :closable="false"
            :title="rowsResponse.statusMessage"
          />

          <el-table
            v-loading="isTableLoading"
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
              <template #default="{ row }">
                <span class="db-cell-text">{{ formatCellValue(row[column.key]) }}</span>
              </template>
            </el-table-column>

            <el-table-column v-if="isCollectFormTable" label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link :icon="Edit" @click="openCollectFormEditor(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="db-pagination">
            <el-pagination
              background
              layout="total, sizes, prev, pager, next"
              :current-page="page"
              :page-size="pageSize"
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

  <el-dialog
    v-model="editDialogVisible"
    title="编辑 collect_form_records"
    width="820px"
    destroy-on-close
  >
    <div class="db-edit-context">
      <div class="db-edit-context-item"><span>ID</span><strong>{{ editForm.id || '-' }}</strong></div>
      <div class="db-edit-context-item"><span>GitLab 来源地址</span><strong>{{ editingContext.gitlabBaseUrl }}</strong></div>
      <div class="db-edit-context-item"><span>Project ID</span><strong>{{ editingContext.projectId }}</strong></div>
      <div class="db-edit-context-item"><span>请求类型 IID</span><strong>{{ editingContext.requestIid }}</strong></div>
      <div class="db-edit-context-item"><span>资源类型</span><strong>{{ editingContext.resourceType }}</strong></div>
      <div class="db-edit-context-item"><span>资源编号</span><strong>{{ editingContext.resourceId }}</strong></div>
      <div class="db-edit-context-item"><span>模板编码</span><strong>{{ editingContext.templateCode }}</strong></div>
      <div class="db-edit-context-item"><span>更新时间</span><strong>{{ editingContext.updatedAt }}</strong></div>
      <div class="db-edit-context-item"><span>创建时间</span><strong>{{ editingContext.createdAt }}</strong></div>
    </div>

    <el-form label-position="top" class="db-edit-form">
      <div class="db-edit-grid">
        <el-form-item label="表单标题">
          <el-input v-model="editForm.formTitle" />
        </el-form-item>
        <el-form-item label="走查人">
          <el-input v-model="editForm.reviewer" />
        </el-form-item>
        <el-form-item label="走查时间(分钟)">
          <el-input-number v-model="editForm.reviewDurationMinutes" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="是否作废">
          <el-switch v-model="editForm.deleted" />
        </el-form-item>
        <el-form-item label="规范">
          <el-input-number v-model="editForm.specificationScore" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="逻辑">
          <el-input-number v-model="editForm.logicScore" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="性能">
          <el-input-number v-model="editForm.performanceScore" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="设计">
          <el-input-number v-model="editForm.designScore" :min="0" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="其他">
          <el-input-number v-model="editForm.otherScore" :min="0" :step="1" controls-position="right" />
        </el-form-item>
      </div>
      <el-form-item label="备注">
        <el-input v-model="editForm.remark" type="textarea" :rows="4" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="db-edit-footer">
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveCollectFormEdit">保存修改</el-button>
      </div>
    </template>
  </el-dialog>
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

.db-edit-context {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid rgba(5, 5, 5, 0.08);
  border-radius: 10px;
  background: #fafafa;
}

.db-edit-context-item {
  display: grid;
  gap: 4px;
}

.db-edit-context-item span {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
}

.db-edit-context-item strong {
  font-size: 13px;
  color: rgba(0, 0, 0, 0.88);
  word-break: break-all;
}

.db-edit-form {
  display: grid;
  gap: 8px;
}

.db-edit-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.db-edit-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
