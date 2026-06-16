<script setup lang="ts">
import { computed, watch } from 'vue';
import SavedTableViewsPanel from './SavedTableViewsPanel.vue';
import type { SavedTableView } from '../composables/useSavedTableViews';

const props = defineProps<{
  modelValue: boolean;
  views: SavedTableView[];
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'save', name: string): void;
  (event: 'apply', id: string): void;
  (event: 'delete', id: string): void;
  (event: 'open'): void;
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

watch(
  () => props.modelValue,
  (nextVisible) => {
    if (nextVisible) {
      emit('open');
    }
  },
);

function applyView(id: string) {
  emit('apply', id);
  visible.value = false;
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="固定表格视图"
    width="560px"
    destroy-on-close
    class="saved-table-view-dialog"
  >
    <SavedTableViewsPanel
      :views="views"
      @save="emit('save', $event)"
      @apply="applyView"
      @delete="emit('delete', $event)"
    />
  </el-dialog>
</template>
