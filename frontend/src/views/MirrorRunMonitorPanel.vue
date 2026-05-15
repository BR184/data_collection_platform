<script setup lang="ts">
import { computed } from 'vue';
import { Close, List, Refresh, RefreshRight } from '@element-plus/icons-vue';
import type { GitlabSyncStatus, MirrorStatusResponse, SyncRunDiagnosticsResponse, SyncRunLog } from '../types/api';
import {
  formatDateTime,
  syncStatusTagType,
  syncStatusText,
  syncTypeText,
  translateSyncMessage,
} from './mirror-settings-helpers';
import MirrorRunQueueTable from './MirrorRunQueueTable.vue';
import MirrorRunWorkerPanel from './MirrorRunWorkerPanel.vue';

const props = defineProps<{
  status: MirrorStatusResponse | null;
  diagnostics: SyncRunDiagnosticsResponse | null;
  refreshing: boolean;
  cancelling: boolean;
  retrying: boolean;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  refresh: [];
  cancel: [];
  retry: [];
  openTableTasks: [];
}>();

const ACTIVE_STATUSES = new Set<GitlabSyncStatus>(['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING']);
const FAILED_STATUSES = new Set<GitlabSyncStatus>(['FAILED', 'TIMEOUT', 'PARTIAL_SUCCESS']);
const TERMINAL_STATUSES = new Set<GitlabSyncStatus>(['SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'TIMEOUT']);

const currentTask = computed(() => props.status?.currentTask ?? null);
const progress = computed(() => props.status?.progress ?? null);
const canCancel = computed(() => {
  const task = currentTask.value;
  return Boolean(task && ACTIVE_STATUSES.has(task.status) && !task.cancelRequested && !props.disabled);
});
const failedRun = computed(() => {
  const logs = props.status?.logs ?? [];
  return logs.find((log) => FAILED_STATUSES.has(log.status)) ?? null;
});
const canRetry = computed(() => Boolean(failedRun.value && !props.disabled));
const terminalRuns = computed(() => {
  const logs = props.status?.logs ?? [];
  return logs.filter((log) => TERMINAL_STATUSES.has(log.status)).slice(0, 5);
});
const activeTableTasks = computed(() => progress.value?.activeTableTasks ?? []);
const dirtyTables = computed(() => (props.diagnostics?.tables ?? []).filter((row) => row.dirty || row.blockingRunId).slice(0, 6));
const tableProgressText = computed(() => {
  if (!progress.value) {
    return '-';
  }
  return `${progress.value.completedTables}/${progress.value.totalTables}`;
});
const currentMessageText = computed(() =>
  translateSyncMessage(props.status?.currentMessage, currentTask.value?.taskType) || 'No active sync run',
);

function submitRetry() {
  if (canRetry.value) {
    emit('retry');
  }
}

function submitCancel() {
  if (canCancel.value) {
    emit('cancel');
  }
}

function terminalTime(log: SyncRunLog) {
  return formatDateTime(log.finishedAt || log.startedAt);
}
</script>

<template>
  <el-card shadow="never" class="panel-card mirror-run-monitor">
    <template #header>
      <div class="monitor-header">
        <div>
          <div class="panel-title">Data mirror monitor</div>
          <div class="panel-subtitle">
            {{ status?.config?.name || 'No source selected' }} · {{ diagnostics?.sourceInstance || status?.config?.sourceInstance || '-' }}
          </div>
        </div>
        <el-space wrap>
          <el-button size="small" text :icon="Refresh" :loading="refreshing" :disabled="disabled" @click="$emit('refresh')">
            Refresh
          </el-button>
          <el-button size="small" text :icon="Close" :loading="cancelling" :disabled="!canCancel" @click="submitCancel">
            Cancel
          </el-button>
          <el-button size="small" text :icon="RefreshRight" :loading="retrying" :disabled="!canRetry" @click="submitRetry">
            Retry failed
          </el-button>
          <el-button size="small" text :icon="List" :disabled="!diagnostics" @click="$emit('openTableTasks')">
            Table tasks
          </el-button>
        </el-space>
      </div>
    </template>

    <div class="monitor-stack">
      <section class="active-run-panel">
        <div class="active-run-main">
          <span>Active run</span>
          <strong>{{ currentTask?.runId || status?.currentStatus || 'IDLE' }}</strong>
          <small>{{ currentMessageText }}</small>
        </div>
        <div class="active-run-metrics">
          <div>
            <span>Status</span>
            <el-tag :type="syncStatusTagType((currentTask?.status || status?.currentStatus || 'IDLE') as GitlabSyncStatus)">
              {{ syncStatusText(currentTask?.status || status?.currentStatus || 'IDLE') }}
            </el-tag>
          </div>
          <div>
            <span>Tables</span>
            <strong>{{ tableProgressText }}</strong>
          </div>
          <div>
            <span>Applied rows</span>
            <strong>{{ progress?.appliedRows ?? progress?.syncedRecords ?? 0 }}</strong>
          </div>
        </div>
      </section>

      <MirrorRunWorkerPanel :status="status" />

      <MirrorRunQueueTable :diagnostics="diagnostics" @open-table-tasks="$emit('openTableTasks')" />

      <section class="table-task-panel">
        <div class="section-head">
          <div>
            <div class="section-title">Active table tasks</div>
            <div class="section-subtitle">{{ activeTableTasks.length }} running tables</div>
          </div>
          <el-button size="small" text :disabled="!diagnostics" @click="$emit('openTableTasks')">Inspect</el-button>
        </div>
        <div v-if="activeTableTasks.length" class="tag-list">
          <el-tag v-for="table in activeTableTasks" :key="table" size="small" type="warning" effect="plain">{{ table }}</el-tag>
        </div>
        <el-empty v-else description="No active table task" :image-size="48" />
      </section>

      <section class="dirty-panel">
        <div class="section-head">
          <div>
            <div class="section-title">Dirty tables</div>
            <div class="section-subtitle">
              {{ diagnostics?.dirtyTableCount ?? 0 }} dirty · {{ diagnostics?.failedTaskCount ?? 0 }} failed ·
              {{ diagnostics?.timedOutTaskCount ?? 0 }} timeout
            </div>
          </div>
        </div>
        <div v-if="dirtyTables.length" class="dirty-list">
          <div v-for="table in dirtyTables" :key="table.sourceTable" class="dirty-row">
            <strong>{{ table.sourceTable }}</strong>
            <span>{{ table.blockingRunId || table.dirtyReason || table.latestTaskError || table.driftSummary || 'dirty' }}</span>
          </div>
        </div>
        <el-empty v-else description="No dirty table" :image-size="48" />
      </section>

      <section class="terminal-runs-panel">
        <div class="section-title">Recent terminal runs</div>
        <div v-if="terminalRuns.length" class="terminal-run-list">
          <div v-for="run in terminalRuns" :key="run.id" class="terminal-run-row">
            <el-tag size="small" effect="plain" :type="syncStatusTagType(run.status)">{{ syncStatusText(run.status) }}</el-tag>
            <span>{{ syncTypeText(run.syncType) }}</span>
            <strong>{{ terminalTime(run) }}</strong>
          </div>
        </div>
        <el-empty v-else description="No terminal run" :image-size="48" />
      </section>
    </div>
  </el-card>
</template>

<style scoped>
.mirror-run-monitor {
  overflow: hidden;
}

.monitor-header,
.section-head,
.active-run-panel,
.active-run-metrics,
.terminal-run-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.monitor-stack {
  display: grid;
  gap: 14px;
}

.panel-title,
.section-title {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}

.panel-subtitle,
.section-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #64748b;
}

.active-run-panel,
.table-task-panel,
.dirty-panel,
.terminal-runs-panel {
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.active-run-main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.active-run-main span,
.active-run-metrics span {
  font-size: 12px;
  color: #64748b;
}

.active-run-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 18px;
  color: #111827;
}

.active-run-main small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #64748b;
}

.active-run-metrics {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.active-run-metrics > div {
  display: grid;
  gap: 5px;
  min-width: 74px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.dirty-list,
.terminal-run-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.dirty-row {
  display: grid;
  grid-template-columns: minmax(0, 120px) minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f8fafc;
}

.dirty-row strong,
.dirty-row span,
.terminal-run-row span,
.terminal-run-row strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dirty-row strong {
  color: #111827;
}

.dirty-row span,
.terminal-run-row span {
  color: #64748b;
}

.terminal-run-row {
  justify-content: flex-start;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f8fafc;
}

.terminal-run-row strong {
  margin-left: auto;
  color: #111827;
  font-size: 12px;
}

@media (max-width: 900px) {
  .monitor-header,
  .active-run-panel {
    align-items: stretch;
    flex-direction: column;
  }

  .active-run-metrics {
    justify-content: flex-start;
  }
}
</style>
