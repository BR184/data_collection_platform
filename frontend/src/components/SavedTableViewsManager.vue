<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Delete, FolderOpened, Select, StarFilled } from '@element-plus/icons-vue';
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

const viewName = ref('');

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value),
});

const sortedViews = computed(() =>
  [...props.views].sort((left, right) => right.updatedAt.localeCompare(left.updatedAt)),
);

const canSave = computed(() => viewName.value.trim().length > 0);

watch(
  () => props.modelValue,
  (nextVisible) => {
    if (nextVisible) {
      emit('open');
    }
  },
);

function saveView() {
  if (!canSave.value) {
    return;
  }
  emit('save', viewName.value.trim());
  viewName.value = '';
}

function applyView(id: string) {
  emit('apply', id);
  visible.value = false;
}

function formatViewTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
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
    <section class="saved-view-panel">
      <div class="saved-view-intro">
        保存当前筛选、关键字、排序、分页和列显示状态。之后可以从列表中一键恢复。
      </div>

      <div class="saved-view-save-row">
        <el-input
          v-model="viewName"
          placeholder="请输入视图名称"
          clearable
          maxlength="40"
          show-word-limit
          @keyup.enter="saveView"
        />
        <el-button type="primary" :icon="StarFilled" :disabled="!canSave" @click="saveView">
          保存当前
        </el-button>
      </div>

      <section class="saved-view-list">
        <div class="saved-view-list-title">
          <span>已保存视图</span>
          <el-tag size="small" effect="plain">{{ sortedViews.length }}</el-tag>
        </div>

        <div v-if="sortedViews.length" class="saved-view-items">
          <article v-for="view in sortedViews" :key="view.id" class="saved-view-item">
            <div class="saved-view-item-main">
              <div class="saved-view-item-name">{{ view.name }}</div>
              <div class="saved-view-item-time">更新于 {{ formatViewTime(view.updatedAt) }}</div>
            </div>
            <div class="saved-view-item-actions">
              <el-button link type="primary" :icon="Select" @click="applyView(view.id)">应用</el-button>
              <el-button link type="danger" :icon="Delete" @click="emit('delete', view.id)">删除</el-button>
            </div>
          </article>
        </div>

        <el-empty v-else description="暂无固定视图" :image-size="54">
          <template #image>
            <el-icon class="saved-view-empty-icon"><FolderOpened /></el-icon>
          </template>
        </el-empty>
      </section>
    </section>
  </el-dialog>
</template>

<style scoped>
.saved-view-panel {
  display: grid;
  gap: 14px;
}

.saved-view-intro {
  padding: 10px 12px;
  border: 1px solid rgba(59, 130, 246, 0.12);
  border-radius: 8px;
  background: rgba(239, 246, 255, 0.82);
  color: rgba(15, 23, 42, 0.66);
  font-size: 13px;
  line-height: 1.6;
}

.saved-view-save-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
}

.saved-view-list {
  display: grid;
  gap: 8px;
}

.saved-view-list-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.78);
}

.saved-view-items {
  display: grid;
  gap: 8px;
  max-height: 320px;
  overflow: auto;
  padding-right: 2px;
}

.saved-view-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #fff;
}

.saved-view-item-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.86);
}

.saved-view-item-time {
  margin-top: 3px;
  font-size: 12px;
  color: rgba(15, 23, 42, 0.48);
}

.saved-view-item-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: nowrap;
}

.saved-view-empty-icon {
  font-size: 44px;
  color: rgba(148, 163, 184, 0.82);
}

@media (max-width: 560px) {
  .saved-view-save-row,
  .saved-view-item {
    grid-template-columns: 1fr;
  }

  .saved-view-item-actions {
    justify-content: flex-start;
  }
}
</style>
