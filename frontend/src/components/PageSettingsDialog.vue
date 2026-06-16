<script setup lang="ts">
import { computed } from 'vue';
import { Close } from '@element-plus/icons-vue';
import SavedTableViewsPanel from './SavedTableViewsPanel.vue';
import type { SavedTableView } from '../composables/useSavedTableViews';

const props = defineProps<{
  modelValue: boolean;
  title: string;
  autoRefreshEnabled?: boolean;
  savedViews: SavedTableView[];
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'toggle-auto-refresh', value: boolean): void;
  (event: 'save-view', name: string): void;
  (event: 'apply-view', id: string): void;
  (event: 'delete-view', id: string): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="620px" destroy-on-close class="page-settings-dialog">
    <section class="page-settings-stack">
      <div class="page-settings-row">
        <div>
          <div class="page-settings-label">进入页面自动刷新</div>
          <div class="page-settings-desc">页面聚焦后自动刷新最新数据</div>
        </div>
        <el-switch
          :model-value="autoRefreshEnabled"
          inline-prompt
          active-text="开"
          inactive-text="关"
          @change="emit('toggle-auto-refresh', Boolean($event))"
        />
      </div>

      <SavedTableViewsPanel
        :views="savedViews"
        @save="emit('save-view', $event)"
        @apply="emit('apply-view', $event)"
        @delete="emit('delete-view', $event)"
      />
    </section>

    <template #footer>
      <el-button :icon="Close" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.page-settings-stack {
  display: grid;
  gap: 16px;
}

.page-settings-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.96);
}

.page-settings-label {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.88);
}

.page-settings-desc {
  margin-top: 4px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.52);
}

@media (max-width: 640px) {
  .page-settings-row {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
