<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { usePlatformProgress } from '../composables/usePlatformProgress';

const { currentTask, runningCount, hasVisibleTask } = usePlatformProgress();
const anchorRef = ref<HTMLElement>();
const floatingStyle = ref<Record<string, string>>({});

const percentage = computed(() => Math.round(currentTask.value?.percentage ?? 0));

const isProgressComplete = computed(() => currentTask.value?.status === 'success' || percentage.value >= 100);

const progressBarStyle = computed(() => ({
  width: `${percentage.value}%`,
  backgroundColor: isProgressComplete.value ? 'var(--el-color-success)' : 'var(--el-color-primary)',
}));

const progressStatus = computed(() => {
  if (currentTask.value?.status === 'exception') {
    return 'exception';
  }
  if (isProgressComplete.value) {
    return 'success';
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

function syncFloatingPosition() {
  const anchor = anchorRef.value;
  if (!anchor) {
    return;
  }
  const rect = anchor.getBoundingClientRect();
  floatingStyle.value = {
    top: `${Math.max(8, rect.top)}px`,
    left: `${rect.left}px`,
    width: `${rect.width}px`,
  };
}

async function scheduleFloatingPositionSync() {
  await nextTick();
  syncFloatingPosition();
}

watch(
  [hasVisibleTask, currentTask],
  () => {
    void scheduleFloatingPositionSync();
  },
  { immediate: true },
);

onMounted(() => {
  void scheduleFloatingPositionSync();
  window.addEventListener('resize', syncFloatingPosition, { passive: true });
  window.addEventListener('scroll', syncFloatingPosition, { passive: true, capture: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncFloatingPosition);
  window.removeEventListener('scroll', syncFloatingPosition, { capture: true });
});
</script>

<template>
  <div ref="anchorRef" class="global-progress-anchor" aria-hidden="true" />
  <Teleport to="body">
    <Transition name="global-progress-fade">
      <div
        v-if="hasVisibleTask && currentTask"
        class="global-progress-indicator"
        :style="floatingStyle"
        role="status"
        aria-live="polite"
      >
        <div class="global-progress-copy">
          <span class="global-progress-title">{{ progressText }}</span>
          <span v-if="runningCount > 1" class="global-progress-count">+{{ runningCount - 1 }}</span>
        </div>
        <div
          class="global-progress-bar"
          :class="{ 'is-complete': isProgressComplete, 'is-exception': progressStatus === 'exception' }"
          role="progressbar"
          :aria-valuenow="percentage"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div class="global-progress-bar-fill" :style="progressBarStyle" />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.global-progress-anchor {
  flex: 0 0 auto;
  width: 184px;
  min-width: 156px;
  height: 39px;
  pointer-events: none;
}

.global-progress-indicator {
  position: fixed;
  z-index: 10000;
  width: 184px;
  min-width: 156px;
  padding: 5px 8px 6px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 6px 18px rgba(31, 45, 61, 0.08);
  pointer-events: none;
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

.global-progress-bar {
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background-color: #edf2f7;
  overflow: hidden;
}

.global-progress-bar-fill {
  height: 100%;
  border-radius: inherit;
  transition:
    width 0.6s ease,
    background-color 180ms ease;
}

.global-progress-bar.is-exception .global-progress-bar-fill {
  background-color: var(--el-color-danger) !important;
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
  .global-progress-anchor,
  .global-progress-indicator {
    width: 144px;
  }
}

@media (max-width: 720px) {
  .global-progress-anchor,
  .global-progress-indicator {
    display: none;
  }
}
</style>
