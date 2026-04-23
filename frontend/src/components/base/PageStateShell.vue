<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  ready: boolean;
  minHeight?: string;
}>(), {
  minHeight: 'calc(100vh - 148px)',
});

const shellStyle = computed(() => ({
  minHeight: props.minHeight,
}));
</script>

<template>
  <div class="page-state-shell" :style="shellStyle">
    <template v-if="ready">
      <slot />
    </template>
    <div v-else class="page-state-shell__skeleton">
      <slot name="skeleton">
        <el-card shadow="never" class="empty-stage-card page-state-shell__card">
          <el-skeleton animated>
            <template #template>
              <div class="page-state-shell__template">
                <el-skeleton-item variant="h3" style="width: 28%" />
                <el-skeleton-item variant="text" style="width: 62%" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 52px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 52px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 240px" />
              </div>
            </template>
          </el-skeleton>
        </el-card>
      </slot>
    </div>
  </div>
</template>

<style scoped>
.page-state-shell {
  width: 100%;
}

.page-state-shell__skeleton,
.page-state-shell__card {
  width: 100%;
  height: 100%;
}

.page-state-shell__template {
  display: grid;
  gap: 16px;
}
</style>
