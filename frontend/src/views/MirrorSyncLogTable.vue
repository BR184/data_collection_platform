<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, ref } from 'vue';
import { useFloatingHorizontalScrollbar } from '../composables/useFloatingHorizontalScrollbar';
import type { SyncRunLog } from '../types/api';
import {
  formatDuration,
  formatLogTime,
  logStatusText,
  logStatusType,
  syncLogTypeText,
  syncLogMessage,
  syncTriggerTypeText,
  syncTypeTagType,
} from './mirror-settings-helpers';

const props = defineProps<{
  logs: SyncRunLog[];
  refreshing: boolean;
}>();

defineEmits<{
  refresh: [];
}>();

const typeFilter = ref('');
const statusFilter = ref('');
const tableRef = ref<{ doLayout?: () => void }>();
const tableShellRef = ref<HTMLElement>();

const typeOptions = computed(() => {
  const optionMap = new Map<string, string>();
  for (const log of props.logs) {
    optionMap.set(typeFilterKey(log), syncLogTypeText(log));
  }
  return Array.from(optionMap, ([value, label]) => ({ value, label }));
});

const statusOptions = computed(() => {
  const optionMap = new Map<string, string>();
  for (const log of props.logs) {
    optionMap.set(statusFilterKey(log), logDisplayStatusText(log));
  }
  return Array.from(optionMap, ([value, label]) => ({ value, label }));
});

const filteredLogs = computed(() =>
  props.logs.filter((log) => {
    const typeMatched = !typeFilter.value || typeFilterKey(log) === typeFilter.value;
    const statusMatched = !statusFilter.value || statusFilterKey(log) === statusFilter.value;
    return typeMatched && statusMatched;
  }),
);

const {
  floatingScrollbarRef,
  floatingTrackRef,
  scrollbarAwake,
  hasHorizontalOverflow,
  isFloatingScrollbarVisible,
  floatingScrollbarStyle,
  floatingThumbStyle,
  wakeHorizontalScrollbar,
  handleHorizontalWheel,
  handleFloatingTrackPointerDown,
  handleFloatingThumbPointerDown,
  handleFloatingScrollbarPointerUp,
  scheduleHorizontalScrollbarUpdate,
} = useFloatingHorizontalScrollbar({
  tableShellRef,
  watchedSources: [filteredLogs],
});

function typeFilterKey(log: SyncRunLog) {
  return log.runType?.trim() || log.syncType;
}

function isMergedLog(log: SyncRunLog) {
  return log.runStatus === 'MERGED';
}

function statusFilterKey(log: SyncRunLog) {
  return isMergedLog(log) ? 'MERGED' : log.status;
}

function logDisplayStatusText(log: SyncRunLog) {
  return isMergedLog(log) ? '已合并' : logStatusText(log.status);
}

function logDisplayStatusType(log: SyncRunLog) {
  return isMergedLog(log) ? 'info' : logStatusType(log.status);
}

function mergedTargetText(log: SyncRunLog) {
  const parentRun = log.parentRunRunId || log.parentRunId;
  return parentRun == null || String(parentRun).trim() === ''
    ? '-'
    : String(parentRun);
}

function sourcePageText(log: SyncRunLog) {
  return log.sourcePageKey || log.requestReason || '-';
}

async function handleExpandChange() {
  tableRef.value?.doLayout?.();
  await scheduleHorizontalScrollbarUpdate();
  wakeHorizontalScrollbar();
}
</script>

<template>
  <el-card shadow="never" class="panel-card">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-title">最近同步日志</div>
        </div>
        <div class="sync-log-actions">
          <el-select
            v-model="typeFilter"
            class="sync-log-filter"
            size="small"
            placeholder="全部类型"
            clearable
            fit-input-width
            popper-class="platform-select-dropdown"
          >
            <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select
            v-model="statusFilter"
            class="sync-log-filter"
            size="small"
            placeholder="全部结果"
            clearable
            fit-input-width
            popper-class="platform-select-dropdown"
          >
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button link :icon="Refresh" :loading="refreshing" @click="$emit('refresh')">刷新</el-button>
        </div>
      </div>
    </template>

    <div
      ref="tableShellRef"
      class="sync-log-table-shell"
      :class="{ 'is-scrollbar-awake': scrollbarAwake, 'has-horizontal-overflow': hasHorizontalOverflow }"
      tabindex="0"
      @mouseenter="wakeHorizontalScrollbar"
      @mousemove="wakeHorizontalScrollbar"
      @focusin="wakeHorizontalScrollbar"
      @wheel="handleHorizontalWheel"
    >
      <el-table
        ref="tableRef"
        :data="filteredLogs"
        row-key="id"
        size="small"
        border
        class="sync-log-table"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="sync-log-detail">
              <div class="sync-log-detail-item">
                <span>运行编号</span>
                <strong>{{ row.runId || row.id }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>触发来源</span>
                <strong>{{ syncTriggerTypeText(row.triggerType) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>同步内容</span>
                <strong>{{ syncLogTypeText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>当前结果</span>
                <strong>{{ logDisplayStatusText(row) }}</strong>
              </div>
              <div v-if="isMergedLog(row)" class="sync-log-detail-item">
                <span>已并入</span>
                <strong>{{ mergedTargetText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>来源页面</span>
                <strong>{{ sourcePageText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>写入记录</span>
                <strong>{{ row.recordCount }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>错误信息</span>
                <strong>{{ row.errorSummary || '-' }}</strong>
              </div>
              <div class="sync-log-detail-item sync-log-detail-item-wide">
                <span>完整消息</span>
                <strong>{{ syncLogMessage(row) }}</strong>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="126">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="syncTypeTagType(row.syncType)">
              {{ syncLogTypeText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="logDisplayStatusType(row)">{{ logDisplayStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatLogTime(row) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ formatDuration(row) }}</template>
        </el-table-column>
        <el-table-column prop="tableCount" label="计划表项" width="96" />
        <el-table-column prop="completedTableCount" label="完成表项" width="96" />
        <el-table-column prop="recordCount" label="写入记录" width="96" />
        <el-table-column label="消息" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ syncLogMessage(row) }}</template>
        </el-table-column>
      </el-table>
      <Teleport to="body">
        <div
          v-show="isFloatingScrollbarVisible"
          ref="floatingScrollbarRef"
          class="sync-log-floating-horizontal"
          :style="floatingScrollbarStyle"
          aria-hidden="true"
          @mouseenter="wakeHorizontalScrollbar"
          @pointerup="handleFloatingScrollbarPointerUp"
        >
          <div
            ref="floatingTrackRef"
            class="platform-floating-horizontal-track"
            @pointerdown="handleFloatingTrackPointerDown"
          >
            <div
              class="platform-floating-horizontal-thumb"
              :style="floatingThumbStyle"
              @pointerdown="handleFloatingThumbPointerDown"
            />
          </div>
        </div>
      </Teleport>
    </div>
  </el-card>
</template>

<style scoped>
.sync-log-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.sync-log-filter {
  width: 140px;
}

.sync-log-table-shell {
  position: relative;
  overflow-x: hidden;
  overflow-y: hidden;
  outline: none;
  scrollbar-gutter: auto;
}

.sync-log-table {
  width: 100%;
}

.sync-log-table :deep(.el-table__inner-wrapper),
.sync-log-table :deep(.el-table__header-wrapper),
.sync-log-table :deep(.el-table__body-wrapper),
.sync-log-table :deep(.el-table__header),
.sync-log-table :deep(.el-table__body) {
  min-width: 100%;
}

.sync-log-table-shell :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.sync-log-floating-horizontal {
  position: fixed;
  z-index: 1900;
  height: 16px;
  padding: 5px 0;
  overflow: visible;
  pointer-events: auto;
  opacity: 1;
  background: transparent;
  box-shadow: none;
}

.sync-log-detail {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px 16px;
  padding: 12px 16px;
  color: #334155;
  background: #f8fafc;
}

.sync-log-detail-item {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.sync-log-detail-item span {
  color: #64748b;
  font-size: 12px;
}

.sync-log-detail-item strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 500;
}

.sync-log-detail-item-wide {
  grid-column: 1 / -1;
}
</style>
