<script setup lang="ts">
import { computed } from 'vue';
import { Cpu, Timer } from '@element-plus/icons-vue';
import type { MirrorStatusResponse } from '../types/api';

const props = defineProps<{
  status: MirrorStatusResponse | null;
}>();

const progress = computed(() => props.status?.progress ?? null);
const config = computed(() => props.status?.config ?? null);
const runningTables = computed(() => progress.value?.runningTables ?? progress.value?.activeTableTasks?.length ?? 0);
const resolvedThreads = computed(() => props.status?.resolvedSyncThreads ?? config.value?.maxSyncThreads ?? 0);
const threadPercent = computed(() => {
  if (!resolvedThreads.value) {
    return 0;
  }
  return Math.min(100, Math.round((runningTables.value / resolvedThreads.value) * 100));
});
const modeLabel = computed(() => {
  if (!config.value) {
    return '未知策略';
  }
  if (config.value.syncThreadMode === 'CPU_RATIO') {
    return `CPU 比例 ${Number(config.value.syncThreadValue ?? 0).toFixed(2)}`;
  }
  return `固定 ${Math.floor(Number(config.value.syncThreadValue ?? 0))} 线程`;
});
const recordsPerSecond = computed(() => progress.value?.recordsPerSecond ?? null);
const recordsPerSecondText = computed(() => {
  const value = recordsPerSecond.value;
  if (value == null) {
    return '-';
  }
  if (value > 0 && value < 1) {
    return '低于 1/秒';
  }
  return `${Math.round(value)}/秒`;
});
</script>

<template>
  <section class="worker-panel">
    <div class="worker-head">
      <div>
        <div class="worker-title">同步线程使用</div>
        <div class="worker-subtitle">{{ modeLabel }}</div>
      </div>
      <el-icon class="worker-icon"><Cpu /></el-icon>
    </div>

    <div class="worker-meter">
      <el-progress :percentage="threadPercent" :stroke-width="10" :show-text="false" />
      <div class="worker-meter-copy">
        <span>{{ runningTables }} 个活跃任务</span>
        <strong>{{ resolvedThreads || '-' }} 个线程</strong>
      </div>
    </div>

    <div class="worker-stats">
      <div>
        <span>服务器 CPU</span>
        <strong>{{ status?.availableProcessors ?? '-' }}</strong>
      </div>
      <div>
        <span>写入速率</span>
        <strong>{{ recordsPerSecondText }}</strong>
      </div>
      <div>
        <span>预计剩余</span>
        <strong>
          <el-icon><Timer /></el-icon>
          {{ progress?.estimatedRemainingSeconds == null ? '-' : `${progress.estimatedRemainingSeconds} 秒` }}
        </strong>
      </div>
    </div>
  </section>
</template>

<style scoped>
.worker-panel {
  display: grid;
  gap: 14px;
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.worker-head,
.worker-meter-copy,
.worker-stats {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.worker-title {
  font-size: 13px;
  font-weight: 700;
  color: #111827;
}

.worker-subtitle,
.worker-meter-copy span,
.worker-stats span {
  font-size: 12px;
  color: #64748b;
}

.worker-icon {
  color: #2563eb;
}

.worker-meter {
  display: grid;
  gap: 8px;
}

.worker-meter-copy strong,
.worker-stats strong {
  color: #111827;
  font-size: 13px;
}

.worker-stats {
  align-items: stretch;
}

.worker-stats > div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.worker-stats strong {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
</style>
