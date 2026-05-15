<script setup lang="ts">
import { computed } from 'vue';
import type { SyncRunDiagnosticsResponse, SyncRunTableDiagnostics } from '../types/api';
import { formatDateTime, syncStatusTagType, syncStatusText } from './mirror-settings-helpers';

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
  return row.latestTaskError || row.lastError || row.driftSummary || row.dirtyReason || row.rowStrategy;
}

function tableTime(row: SyncRunTableDiagnostics) {
  return formatDateTime(row.latestTaskHeartbeatAt || row.latestTaskRunAfter || row.lastWatermarkAt || row.lastAppliedAt);
}
</script>

<template>
  <el-drawer v-model="visible" title="Mirror table tasks" size="720px" class="mirror-table-task-drawer">
    <div class="drawer-summary">
      <div>
        <span>Total</span>
        <strong>{{ diagnostics?.tableCount ?? 0 }}</strong>
      </div>
      <div>
        <span>Dirty</span>
        <strong>{{ diagnostics?.dirtyTableCount ?? 0 }}</strong>
      </div>
      <div>
        <span>Failed</span>
        <strong>{{ (diagnostics?.failedTaskCount ?? 0) + (diagnostics?.timedOutTaskCount ?? 0) }}</strong>
      </div>
    </div>

    <el-table :data="rows" size="small" border height="calc(100vh - 220px)" class="drawer-table">
      <el-table-column prop="sourceTable" label="Source table" min-width="150" show-overflow-tooltip />
      <el-table-column prop="mirrorTable" label="Mirror table" min-width="170" show-overflow-tooltip />
      <el-table-column label="Status" width="104">
        <template #default="{ row }">
          <el-tag v-if="row.latestTaskStatus" size="small" :type="syncStatusTagType(row.latestTaskStatus)">
            {{ syncStatusText(row.latestTaskStatus) }}
          </el-tag>
          <el-tag v-else size="small" type="info">idle</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Dirty" width="78">
        <template #default="{ row }">
          <el-tag size="small" :type="row.dirty || row.blockingRunId ? 'warning' : 'success'" effect="plain">
            {{ row.dirty || row.blockingRunId ? 'yes' : 'no' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Rows" width="118">
        <template #default="{ row }">{{ row.mirrorRows ?? row.mirrorRowCount ?? '-' }} / {{ row.sourceRows ?? row.sourceRowCount ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="Watermark" width="160">
        <template #default="{ row }">{{ tableTime(row) }}</template>
      </el-table-column>
      <el-table-column label="Note" min-width="220" show-overflow-tooltip>
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
