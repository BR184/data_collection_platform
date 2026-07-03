<script setup lang="ts">
import { ref } from 'vue';
import { Setting } from '@element-plus/icons-vue';
import PageSettingsDialog from './PageSettingsDialog.vue';
import { usePageAutoRefreshPreference } from '../composables/usePageAutoRefreshPreference';
import { usePageSavedViews } from '../composables/usePageSavedViews';

const props = withDefaults(
  defineProps<{
    scopeKey: string;
    title?: string;
  }>(),
  {
    title: '页面设置',
  },
);

const settingsVisible = ref(false);
const { autoRefreshOnEnter, setAutoRefreshOnEnter } = usePageAutoRefreshPreference(() => props.scopeKey);
const {
  savedViews,
  loadSavedViews,
  saveCurrentView,
  applySavedView,
  deleteSavedView,
} = usePageSavedViews({
  scopeKey: () => props.scopeKey,
  afterApply: () => {
    settingsVisible.value = false;
  },
});

function openSettings() {
  loadSavedViews();
  settingsVisible.value = true;
}
</script>

<template>
  <el-button
    class="app-action-button app-action-button--settings btn-gray"
    :icon="Setting"
    @click="openSettings"
  >
    设置
  </el-button>
  <PageSettingsDialog
    v-model="settingsVisible"
    :title="title"
    :auto-refresh-enabled="autoRefreshOnEnter"
    :saved-views="savedViews"
    @toggle-auto-refresh="setAutoRefreshOnEnter"
    @save-view="saveCurrentView"
    @apply-view="applySavedView"
    @delete-view="deleteSavedView"
  />
</template>
