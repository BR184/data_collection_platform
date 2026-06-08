<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Delete, Star, StarFilled } from '@element-plus/icons-vue';
import type {
  TagGroupResponse,
  TagGroupsResponse,
  TagSelectionRequest,
} from '../types/api';
import {
  getActiveTagGroupSnapshot,
  isDisabledTagValue,
  normalizeTagSelections,
  parseTagGroupSnapshotStore,
  restoreTagGroupSnapshot,
  savePinnedTagGroupSnapshot,
} from './tag-group-filter';

const props = withDefaults(
  defineProps<{
    modelValue: TagSelectionRequest[];
    tagGroups: TagGroupsResponse | null;
    loading?: boolean;
    storageKey?: string;
    fixedFilters?: Record<string, unknown>;
    autoRestore?: boolean;
  }>(),
  {
    loading: false,
    storageKey: '',
    fixedFilters: () => ({}),
    autoRestore: true,
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: TagSelectionRequest[]): void;
  (event: 'change', value: TagSelectionRequest[]): void;
  (event: 'snapshot-restored', payload: {
    tagSelections: TagSelectionRequest[];
    ignoredCount: number;
    schemaMismatch: boolean;
    fixedFilters: Record<string, unknown>;
    source: 'auto' | 'manual';
  }): void;
  (event: 'snapshot-saved'): void;
}>();

const restoreWarningText = ref('');
const snapshotStoreRevision = ref(0);
const lastAutoRestoreKey = ref('');

const groups = computed(() => props.tagGroups?.groups ?? []);
const normalizedSelections = computed(() => normalizeTagSelections(props.modelValue, groups.value));
const selectedCount = computed(() =>
  normalizedSelections.value.reduce((sum, selection) => sum + selection.valueKeys.length, 0),
);

const activeSelectionByGroup = computed(() => {
  const result = new Map<string, string[]>();
  for (const selection of normalizedSelections.value) {
    result.set(selection.groupKey, selection.valueKeys);
  }
  return result;
});

const visibleGroups = computed(() =>
  groups.value
    .map((group) => ({
      ...group,
      label: displayGroupLabel(group),
      values: group.values.filter((value) => !isDisabledTagValue(value)),
    }))
    .filter((group) => group.values.length > 0),
);

const snapshotStore = computed(() => {
  snapshotStoreRevision.value;
  return parseTagGroupSnapshotStore(props.storageKey ? window.localStorage.getItem(props.storageKey) : '');
});
const snapshotOptions = computed(() => snapshotStore.value.snapshots);
const hasSnapshot = computed(() => snapshotOptions.value.length > 0);

function displayGroupLabel(group: TagGroupResponse) {
  if (props.tagGroups?.domain === 'review_data' && group.groupKey === 'module') {
    return '模块';
  }
  return group.label;
}

function selectionValue(group: TagGroupResponse) {
  const valueKeys = activeSelectionByGroup.value.get(group.groupKey) ?? [];
  return group.selectionMode === 'single' ? valueKeys[0] ?? '' : valueKeys;
}

function handleGroupChange(group: TagGroupResponse, rawValue: string | string[]) {
  const valueKeys = Array.isArray(rawValue)
    ? rawValue
    : rawValue
      ? [rawValue]
      : [];
  const nextSelections = normalizeTagSelections(
    [
      ...normalizedSelections.value.filter((selection) => selection.groupKey !== group.groupKey),
      { groupKey: group.groupKey, valueKeys },
    ],
    groups.value,
  );
  restoreWarningText.value = '';
  emit('update:modelValue', nextSelections);
  emit('change', nextSelections);
}

function clearSelections() {
  restoreWarningText.value = '';
  emit('update:modelValue', []);
  emit('change', []);
}

function saveSnapshot() {
  if (!props.storageKey || !props.tagGroups) {
    return;
  }
  const store = savePinnedTagGroupSnapshot(
    window.localStorage.getItem(props.storageKey),
    props.tagGroups,
    normalizedSelections.value,
    props.fixedFilters,
  );
  window.localStorage.setItem(props.storageKey, JSON.stringify(store));
  snapshotStoreRevision.value += 1;
  lastAutoRestoreKey.value = buildAutoRestoreKey();
  restoreWarningText.value = '';
  emit('snapshot-saved');
}

function restoreSnapshot(snapshotId?: string, source: 'auto' | 'manual' = 'manual') {
  if (!props.storageKey || !props.tagGroups) {
    return;
  }
  const snapshot = snapshotId
    ? snapshotStore.value.snapshots.find((item) => item.id === snapshotId)
    : getActiveTagGroupSnapshot(snapshotStore.value);
  if (!snapshot) {
    return;
  }
  const restored = restoreTagGroupSnapshot(snapshot, props.tagGroups);
  restoreWarningText.value = buildRestoreWarning(restored.ignoredCount, restored.schemaMismatch);
  emit('update:modelValue', restored.tagSelections);
  emit('snapshot-restored', {
    tagSelections: restored.tagSelections,
    ignoredCount: restored.ignoredCount,
    schemaMismatch: restored.schemaMismatch,
    fixedFilters: restored.fixedFilters,
    source,
  });
}

function buildRestoreWarning(ignoredCount: number, schemaMismatch: boolean) {
  if (!schemaMismatch && ignoredCount <= 0) {
    return '';
  }
  if (schemaMismatch && ignoredCount > 0) {
    return `快照口径已变化，已忽略 ${ignoredCount} 个失效条件。`;
  }
  if (schemaMismatch) {
    return '快照口径已变化，请确认恢复后的条件仍符合预期。';
  }
  return `已忽略 ${ignoredCount} 个失效条件。`;
}

function buildAutoRestoreKey() {
  return `${props.storageKey}:${props.tagGroups?.schemaHash ?? ''}`;
}

function tryAutoRestoreSnapshot() {
  if (!props.autoRestore || !props.storageKey || !props.tagGroups || normalizedSelections.value.length > 0) {
    return;
  }
  const restoreKey = buildAutoRestoreKey();
  if (lastAutoRestoreKey.value === restoreKey) {
    return;
  }
  lastAutoRestoreKey.value = restoreKey;
  restoreSnapshot(undefined, 'auto');
}

watch(
  () => [props.storageKey, props.tagGroups?.schemaHash] as const,
  () => {
    snapshotStoreRevision.value += 1;
    tryAutoRestoreSnapshot();
  },
  { immediate: true },
);
</script>

<template>
  <section class="tag-group-filter-bar" aria-label="标签组筛选">
    <div class="tag-group-filter-bar-main" v-loading="loading">
      <el-select
        v-for="group in visibleGroups"
        :key="group.groupKey"
        class="tag-group-filter-bar-select"
        :model-value="selectionValue(group)"
        :multiple="group.selectionMode !== 'single'"
        :collapse-tags="group.selectionMode !== 'single'"
        collapse-tags-tooltip
        clearable
        filterable
        :placeholder="group.label"
        :aria-label="group.label"
        @update:model-value="(value) => handleGroupChange(group, value as string | string[])"
      >
        <el-option
          v-for="value in group.values"
          :key="value.valueKey"
          :label="value.label"
          :value="value.valueKey"
        >
          <span class="tag-group-filter-bar-option-label">{{ value.label }}</span>
          <span v-if="value.unmappedReason" class="tag-group-filter-bar-option-note">
            {{ value.unmappedReason }}
          </span>
        </el-option>
      </el-select>

      <el-empty v-if="!visibleGroups.length" class="tag-group-filter-bar-empty" description="暂无可选标签组" />
    </div>

    <div class="tag-group-filter-bar-actions">
      <el-tag v-if="selectedCount > 0" size="small" effect="plain">{{ selectedCount }} 个已选</el-tag>
      <el-button plain :icon="Star" :disabled="!tagGroups" @click="saveSnapshot">保存快照</el-button>
      <el-dropdown :disabled="!hasSnapshot || !tagGroups" @command="(id) => restoreSnapshot(String(id), 'manual')">
        <el-button
          plain
          :icon="StarFilled"
          :disabled="!hasSnapshot || !tagGroups"
          @click="restoreSnapshot(undefined, 'manual')"
        >
          恢复快照
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="snapshot in snapshotOptions"
              :key="snapshot.id"
              :command="snapshot.id"
            >
              {{ snapshot.name || (snapshot.savedAt ? snapshot.savedAt.slice(0, 19).replace('T', ' ') : '固定快照') }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button
        plain
        :icon="Delete"
        :disabled="normalizedSelections.length === 0"
        @click="clearSelections"
      >
        清空标签
      </el-button>
    </div>

    <el-text v-if="restoreWarningText" class="tag-group-filter-bar-warning" type="warning">
      {{ restoreWarningText }}
    </el-text>
  </section>
</template>

<style scoped>
.tag-group-filter-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-bar-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  min-height: 32px;
}

.tag-group-filter-bar-select {
  width: 180px;
}

.tag-group-filter-bar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-bar-option-label {
  min-width: 0;
}

.tag-group-filter-bar-option-note {
  float: right;
  max-width: 110px;
  margin-left: 16px;
  overflow: hidden;
  color: rgba(15, 23, 42, 0.45);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-group-filter-bar-empty {
  --el-empty-padding: 0;
  width: 100%;
}

.tag-group-filter-bar-warning {
  grid-column: 1 / -1;
  justify-self: start;
}

@media (max-width: 1180px) {
  .tag-group-filter-bar {
    grid-template-columns: 1fr;
  }

  .tag-group-filter-bar-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .tag-group-filter-bar-select {
    width: 100%;
  }
}
</style>
