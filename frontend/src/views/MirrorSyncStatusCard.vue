<script setup lang="ts">
import type { SyncRunSummary, SyncProgress } from '../types/api';
import { formatDateTime } from './mirror-settings-helpers';

defineProps<{
  displayStatus: { text: string; type: 'danger' | 'info' | 'success' | 'warning' };
  statusMessageClass: string[];
  currentMessageText: string;
  phaseText: string;
  progressPercent: number;
  progressHint: string;
  progress: SyncProgress | null;
  currentTask: SyncRunSummary | null;
  currentStartedAt?: string | null;
}>();
</script>

<template>
  <el-card shadow="never" class="panel-card progress-panel">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-title">同步状态</div>
        </div>
        <el-tag :type="displayStatus.type">{{ displayStatus.text }}</el-tag>
      </div>
    </template>

    <div :class="statusMessageClass">{{ currentMessageText }}</div>

    <div class="progress-shell">
      <div class="progress-head">
        <div>
          <div class="progress-title">同步进度</div>
          <div class="progress-subtitle">{{ phaseText }}</div>
        </div>
        <div class="progress-percentage">{{ progressPercent }}%</div>
      </div>
      <el-progress
        :percentage="progressPercent"
        :stroke-width="18"
        :status="currentTask?.status === 'RUNNING' || currentTask?.status === 'CANCELLING' ? undefined : 'success'"
      />
      <div class="progress-tip">{{ progressHint }}</div>
      <div class="progress-meta-grid">
        <div class="meta-item">
          <span class="meta-label">正在处理表</span>
          <span class="meta-value mono">
            {{ progress?.activeTableTasks?.length ? progress.activeTableTasks.join(', ') : '-' }}
          </span>
        </div>
        <div class="meta-item">
          <span class="meta-label">总任务进度</span>
          <span class="meta-value">{{ progress?.completedTables || 0 }}/{{ progress?.totalTables || 0 }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">活跃线程</span>
          <span class="meta-value">{{ progress?.runningTables || 0 }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">Fact stage</span>
          <span class="meta-value">{{ progress?.factRefreshStatus || '-' }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">失败批次</span>
          <span class="meta-value">{{ progress?.failedTables || 0 }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">已写入记录</span>
          <span class="meta-value">{{ progress?.appliedRows ?? progress?.syncedRecords ?? 0 }}</span>
        </div>
        <div class="meta-item">
          <span class="meta-label">开始时间</span>
          <span class="meta-value">{{ formatDateTime(progress?.startedAt || currentStartedAt) }}</span>
        </div>
      </div>
    </div>
  </el-card>
</template>
