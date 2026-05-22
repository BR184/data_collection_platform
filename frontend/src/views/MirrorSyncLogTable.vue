<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
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

defineProps<{
  logs: SyncRunLog[];
  refreshing: boolean;
}>();

defineEmits<{
  refresh: [];
}>();
</script>

<template>
  <el-card shadow="never" class="panel-card">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-title">最近同步日志</div>
        </div>
        <el-button link :icon="Refresh" :loading="refreshing" @click="$emit('refresh')">刷新</el-button>
      </div>
    </template>

    <div class="sync-log-table-shell">
      <el-table :data="logs" row-key="id" max-height="240" size="small" border class="sync-log-table">
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
