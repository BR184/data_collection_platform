<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, ref } from 'vue';
import type { SyncRunLog } from '../types/api';
import {
  formatDuration,
  formatLogTime,
  logStatusText,
  logStatusType,
  syncLogTypeText,
  syncLogMessage,
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

    <div class="sync-log-table-shell">
      <el-table :data="filteredLogs" row-key="id" max-height="280" size="small" border class="sync-log-table">
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="sync-log-detail">
              <div class="sync-log-detail-item">
                <span>运行编号</span>
                <strong>{{ row.runId || row.id }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>触发方式</span>
                <strong>{{ row.triggerType || '-' }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>内部类型</span>
                <strong>{{ row.runType || row.syncType }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>内部状态</span>
                <strong>{{ row.status }}</strong>
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
        <el-table-column label="状态" width="96">
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
        <el-table-column prop="tableCount" label="计划批次" width="96" />
        <el-table-column prop="completedTableCount" label="完成批次" width="96" />
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
