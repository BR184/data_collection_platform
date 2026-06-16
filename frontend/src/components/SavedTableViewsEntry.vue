<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { Star } from '@element-plus/icons-vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import SavedTableViewsManager from './SavedTableViewsManager.vue';
import {
  normalizeRouteQueryForSnapshot,
  toRouteQueryFromSnapshot,
  useSavedTableViews,
  type SavedTableViewSnapshot,
} from '../composables/useSavedTableViews';

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

const route = useRoute();
const router = useRouter();

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
} = useSavedTableViews({
  scopeKey: () => props.scopeKey,
  getCurrentSnapshot,
  applySnapshot,
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  confirmDelete: (viewName) =>
    ElMessageBox.confirm(`确定删除固定视图「${viewName}」吗？删除后无法恢复。`, '删除固定视图', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    }),
});

onMounted(loadSavedViews);

function getCurrentSnapshot(): SavedTableViewSnapshot {
  return {
    routeQuery: normalizeRouteQueryForSnapshot(route.query),
    viewPrefs: props.captureViewPrefs?.(),
  };
}

async function applySnapshot(snapshot: SavedTableViewSnapshot) {
  if (props.applyViewPrefs && snapshot.viewPrefs !== undefined) {
    await props.applyViewPrefs(snapshot.viewPrefs);
  }
  await router.replace({
    path: route.path,
    query: toRouteQueryFromSnapshot(snapshot),
    hash: route.hash,
  });
}

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
