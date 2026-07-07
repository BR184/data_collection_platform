<script setup lang="ts">
import { computed } from 'vue';
import { usePlatformProgress } from '../composables/usePlatformProgress';

const { currentTask, runningCount, hasVisibleTask } = usePlatformProgress();

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
    return '已完成';
  }
  if (task.status === 'exception') {
    return '处理失败';
  }
  return task.label;
});

const percentage = computed(() => Math.round(currentTask.value?.percentage ?? 0));
</script>

<template>
  <Transition name="global-progress-fade">
    <div v-if="hasVisibleTask && currentTask" class="global-progress-indicator" role="status" aria-live="polite">
      <div class="global-progress-copy">
        <span class="global-progress-title">{{ progressText }}</span>
        <span v-if="runningCount > 1" class="global-progress-count">+{{ runningCount - 1 }}</span>
      </div>
      <el-progress
        class="global-progress-bar"
        :percentage="percentage"
        :status="progressStatus"
        :stroke-width="6"
        :show-text="false"
      />
    </div>
  </Transition>
</template>

<style scoped>
.global-progress-indicator {
  width: 184px;
  min-width: 156px;
  padding: 5px 8px 6px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 6px 18px rgba(31, 45, 61, 0.08);
}

.global-progress-copy {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 4px;
  min-width: 0;
}

.global-progress-title {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-progress-count {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 16px;
}

.global-progress-bar :deep(.el-progress-bar__outer) {
  background-color: #edf2f7;
}

.global-progress-fade-enter-active,
.global-progress-fade-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.global-progress-fade-enter-from,
.global-progress-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 960px) {
  .global-progress-indicator {
    width: 144px;
  }
}

@media (max-width: 720px) {
  .global-progress-indicator {
    display: none;
  }
}
</style>
