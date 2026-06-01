<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
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
const scrollbarAwake = ref(false);
let scrollbarAwakeTimer: number | undefined;

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
    optionMap.set(log.status, logStatusText(log.status));
  }
  return Array.from(optionMap, ([value, label]) => ({ value, label }));
});

const filteredLogs = computed(() =>
  props.logs.filter((log) => {
    const typeMatched = !typeFilter.value || typeFilterKey(log) === typeFilter.value;
    const statusMatched = !statusFilter.value || log.status === statusFilter.value;
    return typeMatched && statusMatched;
  }),
);

function typeFilterKey(log: SyncRunLog) {
  return log.runType?.trim() || log.syncType;
}

function wakeHorizontalScrollbar() {
  scrollbarAwake.value = true;
  if (scrollbarAwakeTimer !== undefined) {
    window.clearTimeout(scrollbarAwakeTimer);
  }
  scrollbarAwakeTimer = window.setTimeout(() => {
    scrollbarAwake.value = false;
    scrollbarAwakeTimer = undefined;
  }, 1200);
}

async function handleExpandChange() {
  await nextTick();
  tableRef.value?.doLayout?.();
  wakeHorizontalScrollbar();
}

function handleHorizontalWheel(event: WheelEvent) {
  if (!event.shiftKey || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) {
    return;
  }
  const tableBody = (event.currentTarget as HTMLElement | null)?.querySelector<HTMLElement>('.el-scrollbar__wrap');
  if (!tableBody) {
    return;
  }
  tableBody.scrollLeft += event.deltaY;
  event.preventDefault();
  wakeHorizontalScrollbar();
}

onBeforeUnmount(() => {
  if (scrollbarAwakeTimer !== undefined) {
    window.clearTimeout(scrollbarAwakeTimer);
  }
});
</script>

<template>
  <el-card shadow="never" class="panel-card">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-title">最近同步日志</div>
        </div>
        <div class="sync-log-actions">
          <el-select v-model="typeFilter" class="sync-log-filter" size="small" placeholder="全部类型" clearable>
            <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select v-model="statusFilter" class="sync-log-filter" size="small" placeholder="全部结果" clearable>
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button link :icon="Refresh" :loading="refreshing" @click="$emit('refresh')">刷新</el-button>
        </div>
      </div>
    </template>

    <div
      class="sync-log-table-shell"
      :class="{ 'is-scrollbar-awake': scrollbarAwake }"
      tabindex="0"
      @wheel="handleHorizontalWheel"
    >
      <el-table
        ref="tableRef"
        :data="filteredLogs"
        row-key="id"
        max-height="280"
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
                <strong>{{ logStatusText(row.status) }}</strong>
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
            <el-tag size="small" :type="logStatusType(row.status)">{{ logStatusText(row.status) }}</el-tag>
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
  padding-bottom: 10px;
  outline: none;
  scrollbar-gutter: stable;
}

.sync-log-table-shell::after {
  position: absolute;
  right: 0;
  bottom: 10px;
  width: 36px;
  height: 28px;
  pointer-events: none;
  content: '';
  opacity: 0;
  background: linear-gradient(90deg, rgb(255 255 255 / 0%), rgb(255 255 255 / 92%));
  transition: opacity 0.16s ease;
}

.sync-log-table-shell:hover::after,
.sync-log-table-shell:focus-within::after,
.sync-log-table-shell.is-scrollbar-awake::after {
  opacity: 1;
}

.sync-log-table-shell :deep(.el-scrollbar__bar.is-horizontal) {
  bottom: 2px;
  height: 10px;
  opacity: 0.24;
  transition: opacity 0.16s ease, height 0.16s ease;
}

.sync-log-table-shell:hover :deep(.el-scrollbar__bar.is-horizontal),
.sync-log-table-shell:focus-within :deep(.el-scrollbar__bar.is-horizontal),
.sync-log-table-shell.is-scrollbar-awake :deep(.el-scrollbar__bar.is-horizontal) {
  height: 12px;
  opacity: 1;
}

.sync-log-table-shell :deep(.el-scrollbar__thumb) {
  min-width: 48px;
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
