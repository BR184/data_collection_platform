<script setup lang="ts">
import { computed } from 'vue';
import { Close, List, Refresh, RefreshRight } from '@element-plus/icons-vue';
import type { GitlabSyncStatus, MirrorStatusResponse, SyncRunDiagnosticsResponse, SyncRunLog } from '../types/api';
import {
  formatDateTime,
  syncStatusTagType,
  syncStatusText,
  syncTypeText,
  tableDiagnosticNote,
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
  return logs.filter((log) => TERMINAL_STATUSES.has(log.status)).slice(0, 3);
});
const activeTableTasks = computed(() => progress.value?.activeTableTasks ?? []);
const activeTableTaskPreview = computed(() => activeTableTasks.value.slice(0, 5));
const activeTableTaskHiddenCount = computed(() => Math.max(activeTableTasks.value.length - activeTableTaskPreview.value.length, 0));
const dirtyTables = computed(() => (props.diagnostics?.tables ?? []).filter((row) => row.dirty || row.blockingRunId).slice(0, 4));
const tableProgressText = computed(() => {
  if (!progress.value) {
    return '-';
  }
  return `${progress.value.completedTables}/${progress.value.totalTables}`;
});
const currentRunTitle = computed(() => {
  const task = currentTask.value;
  if (!task) {
    return '暂无运行中的同步';
  }
  return syncTypeText(task.taskType);
});
const currentMessageText = computed(() =>
  translateSyncMessage(props.status?.currentMessage, currentTask.value?.taskType) || '当前没有运行中的同步任务',
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
          <div class="panel-title">数据镜像监控</div>
          <div class="panel-subtitle">
            {{ status?.config?.name || '未选择数据源' }} / 来源 {{ diagnostics?.sourceInstance || status?.config?.sourceInstance || '-' }}
          </div>
        </div>
        <el-space wrap>
          <el-button size="small" text :icon="Refresh" :loading="refreshing" :disabled="disabled" @click="$emit('refresh')">
            刷新
          </el-button>
          <el-button size="small" text :icon="Close" :loading="cancelling" :disabled="!canCancel" @click="submitCancel">
            取消
          </el-button>
          <el-button size="small" text :icon="RefreshRight" :loading="retrying" :disabled="!canRetry" @click="submitRetry">
            重试失败任务
          </el-button>
          <el-button size="small" text :icon="List" :disabled="!diagnostics" @click="$emit('openTableTasks')">
            表任务
          </el-button>
        </el-space>
      </div>
    </template>

    <div class="monitor-stack">
      <section class="active-run-panel">
        <div class="active-run-main">
          <span>当前处理</span>
          <strong>{{ currentRunTitle }}</strong>
          <small>{{ currentMessageText }}</small>
        </div>
        <div class="active-run-metrics">
          <div>
            <span>状态</span>
            <el-tag :type="syncStatusTagType((currentTask?.status || status?.currentStatus || 'IDLE') as GitlabSyncStatus)">
              {{ syncStatusText(currentTask?.status || status?.currentStatus || 'IDLE') }}
            </el-tag>
          </div>
          <div>
            <span>表项进度</span>
            <strong>{{ tableProgressText }}</strong>
          </div>
          <div>
            <span>写入行数</span>
            <strong>{{ progress?.appliedRows ?? progress?.syncedRecords ?? 0 }}</strong>
          </div>
        </div>
      </section>

      <div class="monitor-columns">
        <div class="monitor-column">
          <MirrorRunWorkerPanel :status="status" />

          <MirrorRunQueueTable :diagnostics="diagnostics" @open-table-tasks="$emit('openTableTasks')" />
        </div>

        <div class="monitor-column">
      <section class="table-task-panel">
        <div class="section-head">
          <div>
            <div class="section-title">运行中的表任务</div>
            <div class="section-subtitle">{{ activeTableTasks.length }} 张表正在执行</div>
          </div>
          <el-button size="small" text :disabled="!diagnostics" @click="$emit('openTableTasks')">查看</el-button>
        </div>
        <div v-if="activeTableTasks.length" class="tag-list">
          <el-tag v-for="table in activeTableTaskPreview" :key="table" size="small" type="warning" effect="plain">{{ table }}</el-tag>
          <el-tag v-if="activeTableTaskHiddenCount" size="small" type="info" effect="plain">+{{ activeTableTaskHiddenCount }}</el-tag>
        </div>
        <el-empty v-else description="暂无运行中的表任务" :image-size="48" />
      </section>

      <section class="dirty-panel">
        <div class="section-head">
          <div>
            <div class="section-title">待修复表</div>
            <div class="section-subtitle">
              {{ diagnostics?.dirtyTableCount ?? 0 }} 张待修复 / {{ diagnostics?.failedTaskCount ?? 0 }} 个失败 /
              {{ diagnostics?.timedOutTaskCount ?? 0 }} 个超时
            </div>
          </div>
        </div>
        <div v-if="dirtyTables.length" class="dirty-list">
          <div v-for="table in dirtyTables" :key="table.sourceTable" class="dirty-row">
            <strong>{{ table.sourceTable }}</strong>
            <span>{{ tableDiagnosticNote(table) || '待修复' }}</span>
          </div>
        </div>
        <el-empty v-else description="暂无待修复表" :image-size="48" />
      </section>

      <section class="terminal-runs-panel">
        <div class="section-title">最近完成的运行</div>
        <div v-if="terminalRuns.length" class="terminal-run-list">
          <div v-for="run in terminalRuns" :key="run.id" class="terminal-run-row">
            <el-tag size="small" effect="plain" :type="syncStatusTagType(run.status)">{{ syncStatusText(run.status) }}</el-tag>
            <span>{{ syncTypeText(run.syncType) }}</span>
            <strong>{{ terminalTime(run) }}</strong>
          </div>
        </div>
        <el-empty v-else description="暂无已完成运行" :image-size="48" />
      </section>
        </div>
      </div>
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
  gap: 12px;
}

.monitor-columns {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  gap: 12px;
  align-items: start;
}

.monitor-column {
  display: grid;
  gap: 12px;
  min-width: 0;
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
  padding: 12px;
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
  min-width: 92px;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.dirty-list,
.terminal-run-list {
  display: grid;
  gap: 6px;
  margin-top: 8px;
}

.dirty-row {
  display: grid;
  grid-template-columns: minmax(0, 110px) minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 7px 9px;
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
  padding: 7px 9px;
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

  .monitor-columns {
    grid-template-columns: 1fr;
  }
}
</style>
