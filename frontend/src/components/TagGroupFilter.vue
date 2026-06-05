<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Search, Star, StarFilled } from '@element-plus/icons-vue';
import type {
  TagGroupResponse,
  TagGroupsResponse,
  TagGroupValueResponse,
  TagSelectionRequest,
} from '../types/api';
import {
  getActiveTagGroupSnapshot,
  isDisabledTagValue,
  normalizeTagSelections,
  parseTagGroupSnapshotStore,
  restoreTagGroupSnapshot,
  savePinnedTagGroupSnapshot,
  toggleTagSelectionValue,
} from './tag-group-filter';

const props = withDefaults(
  defineProps<{
    modelValue: TagSelectionRequest[];
    tagGroups: TagGroupsResponse | null;
    loading?: boolean;
    storageKey?: string;
    fixedFilters?: Record<string, unknown>;
    autoRestore?: boolean;
    defaultExpanded?: boolean;
  }>(),
  {
    loading: false,
    storageKey: '',
    fixedFilters: () => ({}),
    autoRestore: true,
    defaultExpanded: false,
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
  }): void;
  (event: 'snapshot-saved'): void;
}>();

const keyword = ref('');
const showDisabledValues = ref(false);
const expanded = ref(props.defaultExpanded);
const snapshotStoreRevision = ref(0);
const lastAutoRestoreKey = ref('');

const groups = computed(() => props.tagGroups?.groups ?? []);
const normalizedSelections = computed(() => normalizeTagSelections(props.modelValue, groups.value));
const selectedCount = computed(() =>
  normalizedSelections.value.reduce((sum, selection) => sum + selection.valueKeys.length, 0),
);
const selectedValueKeys = computed(() => {
  const result = new Set<string>();
  for (const selection of normalizedSelections.value) {
    for (const valueKey of selection.valueKeys) {
      result.add(`${selection.groupKey}:${valueKey}`);
    }
  }
  return result;
});

const snapshotStore = computed(() => {
  snapshotStoreRevision.value;
  return parseTagGroupSnapshotStore(props.storageKey ? window.localStorage.getItem(props.storageKey) : '');
});
const snapshotOptions = computed(() => snapshotStore.value.snapshots);
const hasSnapshot = computed(() => snapshotOptions.value.length > 0);

const visibleGroups = computed(() => {
  const query = keyword.value.trim().toLowerCase();
  return groups.value
    .map((group) => ({
      ...group,
      values: group.values.filter((value) => {
        if (!showDisabledValues.value && isDisabledTagValue(value)) {
          return false;
        }
        if (!query) {
          return true;
        }
        return `${group.label} ${value.label} ${value.valueKey} ${value.unmappedReason ?? ''}`
          .toLowerCase()
          .includes(query);
      }),
    }))
    .filter((group) => group.values.length > 0);
});

function isSelected(group: TagGroupResponse, value: TagGroupValueResponse) {
  return selectedValueKeys.value.has(`${group.groupKey}:${value.valueKey}`);
}

function handleToggle(group: TagGroupResponse, value: TagGroupValueResponse) {
  const nextSelections = toggleTagSelectionValue(
    normalizedSelections.value,
    groups.value,
    group.groupKey,
    value.valueKey,
  );
  emit('update:modelValue', nextSelections);
  emit('change', nextSelections);
}

function toggleExpanded() {
  expanded.value = !expanded.value;
}

function clearSelections() {
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
  emit('snapshot-saved');
}

function restoreSnapshot(snapshotId?: string) {
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
  emit('update:modelValue', restored.tagSelections);
  emit('snapshot-restored', {
    tagSelections: restored.tagSelections,
    ignoredCount: restored.ignoredCount,
    schemaMismatch: restored.schemaMismatch,
    fixedFilters: restored.fixedFilters,
  });
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
  restoreSnapshot();
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
  <section class="tag-group-filter" aria-label="标签组筛选">
    <div class="tag-group-filter-summary">
      <div class="tag-group-filter-summary-text">
        <span class="tag-group-filter-title">标签组</span>
        <el-tag v-if="selectedCount > 0" size="small" effect="plain">{{ selectedCount }} 个已选</el-tag>
      </div>
      <el-button
        plain
        :icon="expanded ? ArrowUp : ArrowDown"
        :aria-expanded="expanded"
        data-testid="tag-group-filter-toggle"
        @click="toggleExpanded"
      >
        {{ expanded ? '收起' : '展开' }}
      </el-button>
    </div>

    <el-collapse-transition>
      <div v-show="expanded" class="tag-group-filter-body">
        <div class="tag-group-filter-toolbar">
          <el-input
            v-model="keyword"
            class="tag-group-filter-search"
            :prefix-icon="Search"
            clearable
            placeholder="搜索标签值"
          />
          <el-checkbox v-model="showDisabledValues">显示停用标签</el-checkbox>
          <div class="tag-group-filter-actions">
            <el-button plain :icon="Star" :disabled="!tagGroups" @click="saveSnapshot">保存固定快照</el-button>
            <el-dropdown :disabled="!hasSnapshot || !tagGroups" @command="(id) => restoreSnapshot(String(id))">
              <el-button plain :icon="StarFilled" :disabled="!hasSnapshot || !tagGroups" @click="restoreSnapshot()">
                恢复快照
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="snapshot in snapshotOptions"
                    :key="snapshot.id"
                    :command="snapshot.id"
                  >
                    {{ snapshot.savedAt ? snapshot.savedAt.slice(0, 19).replace('T', ' ') : '固定快照' }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button plain :disabled="normalizedSelections.length === 0" @click="clearSelections">
              清空标签
            </el-button>
          </div>
        </div>

        <div v-loading="loading" class="tag-group-filter-groups">
          <article v-for="group in visibleGroups" :key="group.groupKey" class="tag-group-filter-group">
            <header class="tag-group-filter-group-header">
              <span class="tag-group-filter-group-title">{{ group.label }}</span>
              <el-tag size="small" effect="plain">{{ group.selectionMode === 'single' ? '单选' : '多选' }}</el-tag>
            </header>
            <div class="tag-group-filter-values">
              <button
                v-for="value in group.values"
                :key="value.valueKey"
                class="tag-group-filter-value"
                :class="{
                  'is-selected': isSelected(group, value),
                  'is-unmapped': value.valueType === 'unmapped',
                  'is-disabled': isDisabledTagValue(value),
                }"
                type="button"
                :disabled="isDisabledTagValue(value)"
                :title="value.unmappedReason || value.label"
                @click="handleToggle(group, value)"
              >
                <span>{{ value.label }}</span>
                <small v-if="value.unmappedReason">{{ value.unmappedReason }}</small>
              </button>
            </div>
          </article>

          <el-empty v-if="!visibleGroups.length" description="暂无可选标签组" />
        </div>
      </div>
    </el-collapse-transition>
  </section>
</template>

<style scoped>
.tag-group-filter {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.tag-group-filter-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
}

.tag-group-filter-summary-text {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.82);
}

.tag-group-filter-body {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.tag-group-filter-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.tag-group-filter-search {
  width: 220px;
}

.tag-group-filter-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}

.tag-group-filter-groups {
  display: grid;
  gap: 10px;
  min-height: 54px;
}

.tag-group-filter-group {
  display: grid;
  gap: 8px;
}

.tag-group-filter-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tag-group-filter-group-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.78);
}

.tag-group-filter-values {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-group-filter-value {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  max-width: 240px;
  padding: 5px 10px;
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 6px;
  background: #fff;
  color: rgba(15, 23, 42, 0.76);
  line-height: 1.2;
  cursor: pointer;
}

.tag-group-filter-value:hover {
  border-color: rgba(37, 99, 235, 0.4);
  color: #1d4ed8;
}

.tag-group-filter-value.is-selected {
  border-color: rgba(37, 99, 235, 0.72);
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
  font-weight: 700;
}

.tag-group-filter-value.is-unmapped {
  border-style: dashed;
}

.tag-group-filter-value.is-disabled {
  cursor: not-allowed;
  opacity: 0.52;
}

.tag-group-filter-value small {
  min-width: 0;
  overflow: hidden;
  color: rgba(15, 23, 42, 0.48);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
