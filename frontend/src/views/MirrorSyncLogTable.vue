<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import type { GitlabSyncLog } from '../types/api';
import {
  formatDuration,
  formatLogTime,
  logStatusText,
  logStatusType,
  syncLogMessage,
  syncTypeTagType,
  syncTypeText,
} from './mirror-settings-helpers';

defineProps<{
  logs: GitlabSyncLog[];
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
          <div class="panel-title">Recent sync activity</div>
        </div>
        <el-button link :icon="Refresh" :loading="refreshing" @click="$emit('refresh')">Refresh</el-button>
      </div>
    </template>

    <div class="sync-log-table-shell">
      <el-table :data="logs" row-key="id" max-height="320" size="small" border class="sync-log-table">
        <el-table-column label="Type" width="126">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="syncTypeTagType(row.syncType)">
              {{ syncTypeText(row.syncType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Status" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="logStatusType(row.status)">{{ logStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Time" width="160">
          <template #default="{ row }">{{ formatLogTime(row) }}</template>
        </el-table-column>
        <el-table-column label="Duration" width="90">
          <template #default="{ row }">{{ formatDuration(row) }}</template>
        </el-table-column>
        <el-table-column prop="tableCount" label="Tables" width="96" />
        <el-table-column prop="recordCount" label="Records" width="88" />
        <el-table-column label="Message" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ syncLogMessage(row) }}</template>
        </el-table-column>
      </el-table>
    </div>
  </el-card>
</template>
