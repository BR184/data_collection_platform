<script setup lang="ts">
import { computed } from 'vue';
import { useExportProgress } from '../composables/useExportProgress';

const { currentTask, runningCount, hasVisibleTask } = useExportProgress();

const progressStatus = computed(() => {
  if (currentTask.value?.status === 'success') {
    return 'success';
  }
  if (currentTask.value?.status === 'exception') {
    return 'exception';
  }
  return undefined;
});

const progressText = computed(() => {
  const task = currentTask.value;
  if (!task) {
    return '';
  }
  if (task.status === 'success') {
    return '导出完成';
  }
  if (task.status === 'exception') {
    return '导出失败';
  }
  return task.label;
});

const percentage = computed(() => Math.round(currentTask.value?.percentage ?? 0));
</script>

<template>
  <Transition name="export-progress-fade">
    <div v-if="hasVisibleTask && currentTask" class="export-progress-indicator" role="status" aria-live="polite">
      <div class="export-progress-copy">
        <span class="export-progress-title">{{ progressText }}</span>
        <span v-if="runningCount > 1" class="export-progress-count">+{{ runningCount - 1 }}</span>
      </div>
      <el-progress
        class="export-progress-bar"
        :percentage="percentage"
        :status="progressStatus"
        :stroke-width="6"
        :show-text="false"
      />
    </div>
  </Transition>
</template>

<style scoped>
.export-progress-indicator {
  width: 176px;
  min-width: 150px;
  padding: 5px 8px 6px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 6px 18px rgba(31, 45, 61, 0.08);
}

.export-progress-copy {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 4px;
  min-width: 0;
}

.export-progress-title {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.export-progress-count {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 16px;
}

.export-progress-bar :deep(.el-progress-bar__outer) {
  background-color: #edf2f7;
}

.export-progress-fade-enter-active,
.export-progress-fade-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.export-progress-fade-enter-from,
.export-progress-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 960px) {
  .export-progress-indicator {
    width: 140px;
  }
}

@media (max-width: 720px) {
  .export-progress-indicator {
    display: none;
  }
}
</style>
