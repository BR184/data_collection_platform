<script setup lang="ts">
import { computed } from 'vue';
import { Star } from '@element-plus/icons-vue';
import SavedTableViewsManager from './SavedTableViewsManager.vue';
import { usePageSavedViews } from '../composables/usePageSavedViews';

const props = withDefaults(
  defineProps<{
    scopeKey: string;
    modelValue?: boolean;
    showTrigger?: boolean;
    buttonText?: string;
    captureViewPrefs?: () => unknown;
    applyViewPrefs?: (viewPrefs: unknown) => void | Promise<void>;
  }>(),
  {
    modelValue: false,
    showTrigger: true,
    buttonText: '固定视图',
    captureViewPrefs: undefined,
    applyViewPrefs: undefined,
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

const {
  savedViews,
  loadSavedViews,
  saveCurrentView,
  applySavedView,
  deleteSavedView,
} = usePageSavedViews({
  scopeKey: () => props.scopeKey,
  captureViewPrefs: props.captureViewPrefs,
  applyViewPrefs: props.applyViewPrefs,
});

function openDialog() {
  loadSavedViews();
  dialogVisible.value = true;
}
</script>

<template>
  <el-button v-if="showTrigger" plain :icon="Star" @click="openDialog">
    {{ buttonText }}
  </el-button>

  <SavedTableViewsManager
    v-model="dialogVisible"
    :views="savedViews"
    @open="loadSavedViews"
    @save="saveCurrentView"
    @apply="applySavedView"
    @delete="deleteSavedView"
  />
</template>
