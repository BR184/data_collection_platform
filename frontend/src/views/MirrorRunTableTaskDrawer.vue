<script setup lang="ts">
import { computed } from 'vue';
import type { SyncRunDiagnosticsResponse, SyncRunTableDiagnostics } from '../types/api';
import { formatDateTime, syncStatusTagType, tableDiagnosticNote, tableTaskStatusText } from './mirror-settings-helpers';

const props = defineProps<{
  modelValue: boolean;
  diagnostics: SyncRunDiagnosticsResponse | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const rows = computed(() => props.diagnostics?.tables ?? []);

function tableNote(row: SyncRunTableDiagnostics) {
  return tableDiagnosticNote(row);
}

function tableTime(row: SyncRunTableDiagnostics) {
  return formatDateTime(row.latestTaskHeartbeatAt || row.latestTaskRunAfter || row.lastWatermarkAt || row.lastAppliedAt);
}
</script>

<template>
  <el-drawer v-model="visible" title="镜像表任务" size="720px" class="mirror-table-task-drawer">
    <div class="drawer-summary">
      <div>
        <span>镜像表数</span>
        <strong>{{ diagnostics?.tableCount ?? 0 }}</strong>
      </div>
      <div>
        <span>待修复</span>
        <strong>{{ diagnostics?.dirtyTableCount ?? 0 }}</strong>
      </div>
      <div>
        <span>失败/超时</span>
        <strong>{{ (diagnostics?.failedTaskCount ?? 0) + (diagnostics?.timedOutTaskCount ?? 0) }}</strong>
      </div>
    </div>

    <el-table :data="rows" size="small" border height="calc(100vh - 220px)" class="drawer-table">
      <el-table-column prop="sourceTable" label="源表" min-width="150" show-overflow-tooltip />
      <el-table-column prop="mirrorTable" label="镜像表" min-width="170" show-overflow-tooltip />
      <el-table-column label="状态" width="104">
        <template #default="{ row }">
          <el-tag v-if="row.latestTaskStatus" size="small" :type="syncStatusTagType(row.latestTaskStatus)">
            {{ tableTaskStatusText(row.latestTaskStatus) }}
          </el-tag>
          <el-tag v-else size="small" type="info">空闲</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="待修复" width="84">
        <template #default="{ row }">
          <el-tag size="small" :type="row.dirty || row.blockingRunId ? 'warning' : 'success'" effect="plain">
            {{ row.dirty || row.blockingRunId ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="行数" width="118">
        <template #default="{ row }">{{ row.mirrorRows ?? row.mirrorRowCount ?? '-' }} / {{ row.sourceRows ?? row.sourceRowCount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="水位时间" width="160">
        <template #default="{ row }">{{ tableTime(row) }}</template>
      </el-table-column>
      <el-table-column label="说明" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ tableNote(row) }}</template>
      </el-table-column>
    </el-table>
  </el-drawer>
</template>

<style scoped>
.drawer-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.drawer-summary > div {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.drawer-summary span {
  font-size: 12px;
  color: #64748b;
}

.drawer-summary strong {
  font-size: 18px;
  color: #111827;
}

.drawer-table {
  width: 100%;
}
</style>
