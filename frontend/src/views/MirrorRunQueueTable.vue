<script setup lang="ts">
import { computed } from 'vue';
import type { SyncRunDiagnosticsResponse, SyncRunTableDiagnostics } from '../types/api';
import { formatDateTime, syncStatusTagType, syncStatusText } from './mirror-settings-helpers';

const props = defineProps<{
  diagnostics: SyncRunDiagnosticsResponse | null;
}>();

defineEmits<{
  openTableTasks: [];
}>();

const ACTIVE_TABLE_STATUSES = new Set(['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'FAILED', 'TIMEOUT']);

const rows = computed(() => {
  const tables = props.diagnostics?.tables ?? [];
  const prioritized = tables.filter((row) => row.dirty || ACTIVE_TABLE_STATUSES.has(row.latestTaskStatus ?? ''));
  return (prioritized.length ? prioritized : tables).slice(0, 8);
});

function rowReason(row: SyncRunTableDiagnostics) {
  if (row.latestTaskError || row.lastError) {
    return row.latestTaskError || row.lastError;
  }
  if (row.blockingRunId) {
    return `blocked by ${row.blockingRunId}`;
  }
  if (row.dirtyReason) {
    return row.dirtyReason;
  }
  if (row.driftSummary) {
    return row.driftSummary;
  }
  return row.rowStrategy;
}

function rowTime(row: SyncRunTableDiagnostics) {
  return formatDateTime(row.latestTaskHeartbeatAt || row.latestTaskRunAfter || row.lastAppliedAt || row.lastVerifiedAt);
}
</script>

<template>
  <section class="queue-panel">
    <div class="queue-head">
      <div>
        <div class="queue-title">Table task queue</div>
        <div class="queue-subtitle">
          Pending {{ diagnostics?.pendingTaskCount ?? 0 }} · Running {{ diagnostics?.runningTaskCount ?? 0 }} · Retry
          {{ diagnostics?.retryingTaskCount ?? 0 }}
        </div>
      </div>
      <el-button size="small" text @click="$emit('openTableTasks')">Open drawer</el-button>
    </div>

    <el-table :data="rows" size="small" border class="queue-table" max-height="280">
      <el-table-column prop="sourceTable" label="Source table" min-width="130" show-overflow-tooltip />
      <el-table-column label="Task" width="112">
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
      <el-table-column label="Reason" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ rowReason(row) }}</template>
      </el-table-column>
      <el-table-column label="Updated" width="160">
        <template #default="{ row }">{{ rowTime(row) }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.queue-panel {
  display: grid;
  gap: 12px;
}

.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.queue-title {
  font-size: 13px;
  font-weight: 700;
  color: #111827;
}

.queue-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #64748b;
}

.queue-table {
  width: 100%;
}
</style>
