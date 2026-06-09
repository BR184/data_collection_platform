<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowDown, Check, Delete, Search, Star, StarFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import type {
  TagGroupResponse,
  TagGroupsResponse,
  TagGroupValueResponse,
  TagSelectionRequest,
} from '../types/api';
import {
  deleteTagGroupSnapshot,
  getActiveTagGroupSnapshot,
  type TagGroupFilterSnapshot,
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
    currentTotal?: number;
    currentViewName?: string;
  }>(),
  {
    loading: false,
    storageKey: '',
    fixedFilters: () => ({}),
    autoRestore: true,
    currentTotal: 0,
    currentViewName: '',
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
  (event: 'snapshot-saved', payload: { name: string }): void;
}>();

const restoreWarningText = ref('');
const snapshotStoreRevision = ref(0);
const lastAutoRestoreKey = ref('');
const savePopoverVisible = ref(false);
const restorePopoverVisible = ref(false);
const filterPopoverVisible = ref(false);
const snapshotName = ref('');
const activeGroupKey = ref('');
const valueKeyword = ref('');

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

const activeGroup = computed(() =>
  visibleGroups.value.find((group) => group.groupKey === activeGroupKey.value) ?? visibleGroups.value[0] ?? null,
);

const filteredActiveValues = computed(() => {
  const group = activeGroup.value;
  if (!group) {
    return [];
  }
  const keyword = valueKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return group.values;
  }
  return group.values.filter((value) =>
    `${value.label} ${value.valueKey} ${value.unmappedReason ?? ''}`.toLowerCase().includes(keyword),
  );
});

const selectedGroupSummaries = computed(() =>
  normalizedSelections.value
    .map((selection) => {
      const group = visibleGroups.value.find((item) => item.groupKey === selection.groupKey);
      if (!group) {
        return null;
      }
      const labels = selection.valueKeys
        .map((valueKey) => group.values.find((value) => value.valueKey === valueKey)?.label ?? valueKey);
      return {
        key: selection.groupKey,
        label: group.label,
        value: labels.join(', '),
      };
    })
    .filter((item): item is { key: string; label: string; value: string } => Boolean(item)),
);

const snapshotStore = computed(() => {
  snapshotStoreRevision.value;
  return parseTagGroupSnapshotStore(props.storageKey ? window.localStorage.getItem(props.storageKey) : '');
});
const snapshotOptions = computed(() => snapshotStore.value.snapshots);
const hasSnapshot = computed(() => snapshotOptions.value.length > 0);
const trimmedSnapshotName = computed(() => snapshotName.value.trim());
const canSaveSnapshot = computed(() => trimmedSnapshotName.value.length >= 2 && trimmedSnapshotName.value.length <= 30);
const currentTotalText = computed(() => Number.isFinite(props.currentTotal) ? props.currentTotal : 0);
const displayCurrentViewName = computed(() =>
  props.currentViewName || formatSnapshotViewName(getActiveTagGroupSnapshot(snapshotStore.value)),
);

function displayGroupLabel(group: TagGroupResponse) {
  if (props.tagGroups?.domain === 'review_data' && group.groupKey === 'module') {
    return '模块';
  }
  return group.label;
}

function groupSelectionCount(group: TagGroupResponse) {
  return activeSelectionByGroup.value.get(group.groupKey)?.length ?? 0;
}

function selectGroup(groupKey: string) {
  activeGroupKey.value = groupKey;
  valueKeyword.value = '';
}

function isValueSelected(group: TagGroupResponse, value: TagGroupValueResponse) {
  return activeSelectionByGroup.value.get(group.groupKey)?.includes(value.valueKey) ?? false;
}

function toggleValue(group: TagGroupResponse, value: TagGroupValueResponse) {
  const nextSelections = toggleTagSelectionValue(
    normalizedSelections.value,
    groups.value,
    group.groupKey,
    value.valueKey,
  );
  restoreWarningText.value = '';
  emit('update:modelValue', nextSelections);
  emit('change', nextSelections);
}

function clearGroup(groupKey: string) {
  const nextSelections = normalizeTagSelections(
    normalizedSelections.value.filter((selection) => selection.groupKey !== groupKey),
    groups.value,
  );
  restoreWarningText.value = '';
  emit('update:modelValue', nextSelections);
  emit('change', nextSelections);
}

async function clearSelections() {
  if (normalizedSelections.value.length === 0) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      '只清空标签组筛选，不会清空关键词、指标与例外条件或排序。',
      '清空标签',
      {
        type: 'warning',
        confirmButtonText: '清空标签',
        cancelButtonText: '取消',
      },
    );
  } catch {
    return;
  }
  restoreWarningText.value = '';
  emit('update:modelValue', []);
  emit('change', []);
}

function openSaveSnapshotForm() {
  snapshotName.value = '';
  savePopoverVisible.value = true;
  restoreWarningText.value = '';
}

function saveSnapshot() {
  if (!props.storageKey || !props.tagGroups) {
    return;
  }
  if (!canSaveSnapshot.value) {
    ElMessage.warning('请输入 2-30 个字符的快照名称');
    return;
  }
  const name = trimmedSnapshotName.value;
  const store = savePinnedTagGroupSnapshot(
    window.localStorage.getItem(props.storageKey),
    props.tagGroups,
    normalizedSelections.value,
    props.fixedFilters,
    { name },
  );
  window.localStorage.setItem(props.storageKey, JSON.stringify(store));
  snapshotStoreRevision.value += 1;
  lastAutoRestoreKey.value = buildAutoRestoreKey();
  restoreWarningText.value = '';
  savePopoverVisible.value = false;
  snapshotName.value = '';
  emit('snapshot-saved', { name });
}

function restoreSnapshot(snapshotId?: string, source: 'auto' | 'manual' = 'manual') {
  if (!props.storageKey || !props.tagGroups) {
    return;
  }
  if (!snapshotId && source === 'manual') {
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

function handleRestoreSnapshot(snapshotId: string) {
  restorePopoverVisible.value = false;
  restoreSnapshot(snapshotId, 'manual');
}

function handleDeleteSnapshot(snapshotId: string) {
  if (!props.storageKey) {
    return;
  }
  const store = deleteTagGroupSnapshot(window.localStorage.getItem(props.storageKey), snapshotId);
  window.localStorage.setItem(props.storageKey, JSON.stringify(store));
  snapshotStoreRevision.value += 1;
  if (!store.snapshots.length) {
    restorePopoverVisible.value = false;
  }
}

function snapshotTitle(snapshot: TagGroupFilterSnapshot) {
  return snapshot.name || '未命名快照';
}

function snapshotSubtitle(snapshot: TagGroupFilterSnapshot) {
  return [
    formatSnapshotTime(snapshot.savedAt),
    `${snapshotConditionCount(snapshot)} 个条件`,
    snapshot.schemaHash !== props.tagGroups?.schemaHash ? '口径已变化' : '',
  ].filter(Boolean).join(' · ');
}

function formatSnapshotTime(value?: string) {
  return value ? value.slice(0, 19).replace('T', ' ') : '保存时间未知';
}

function formatSnapshotViewName(snapshot: TagGroupFilterSnapshot | null) {
  return snapshot?.savedAt ? `上次保存 ${formatSnapshotTime(snapshot.savedAt).slice(0, 16)}` : '';
}

function snapshotConditionCount(snapshot: TagGroupFilterSnapshot) {
  const tagValueCount = (snapshot.tagSelections ?? [])
    .reduce((sum, selection) => sum + selection.valueKeys.length, 0);
  const fixedFilterCount = Object.values(snapshot.fixedFilters ?? {})
    .filter((value) => String(value ?? '').trim()).length;
  return tagValueCount + fixedFilterCount;
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

watch(
  visibleGroups,
  (nextGroups) => {
    if (!nextGroups.some((group) => group.groupKey === activeGroupKey.value)) {
      activeGroupKey.value = nextGroups[0]?.groupKey ?? '';
    }
  },
  { immediate: true },
);
</script>

<template>
  <section class="tag-group-filter-bar" aria-label="标签组筛选">
    <div class="tag-group-filter-bar-row">
      <el-popover
        v-model:visible="filterPopoverVisible"
        trigger="click"
        placement="bottom-start"
        width="680"
        popper-class="tag-group-filter-bar-popper"
      >
        <template #reference>
          <el-button
            plain
            :icon="Search"
            :loading="loading"
            :aria-expanded="filterPopoverVisible"
            data-testid="tag-group-filter-bar-header"
          >
            标签组
            <el-tag v-if="selectedCount > 0" class="tag-group-filter-bar-button-count" size="small" effect="plain">
              {{ selectedCount }}
            </el-tag>
            <el-icon class="el-icon--right"><ArrowDown /></el-icon>
          </el-button>
        </template>

        <section class="tag-group-filter-bar-panel" data-testid="tag-group-filter-bar-body">
          <aside class="tag-group-filter-bar-groups" aria-label="标签组列表">
            <button
              v-for="group in visibleGroups"
              :key="group.groupKey"
              class="tag-group-filter-bar-group"
              :class="{ 'is-active': activeGroup?.groupKey === group.groupKey }"
              type="button"
              :data-testid="`tag-group-filter-bar-group-${group.groupKey}`"
              @click="selectGroup(group.groupKey)"
            >
              <span>{{ group.label }}</span>
              <el-tag v-if="groupSelectionCount(group) > 0" size="small" effect="plain">
                {{ groupSelectionCount(group) }}
              </el-tag>
            </button>
          </aside>

          <main class="tag-group-filter-bar-values" v-loading="loading">
            <template v-if="activeGroup">
              <div class="tag-group-filter-bar-values-head">
                <strong>{{ activeGroup.label }}</strong>
                <el-button
                  text
                  size="small"
                  :disabled="groupSelectionCount(activeGroup) === 0"
                  @click="clearGroup(activeGroup.groupKey)"
                >
                  清空
                </el-button>
              </div>
              <el-input
                v-model="valueKeyword"
                class="tag-group-filter-bar-search"
                :prefix-icon="Search"
                clearable
                placeholder="搜索"
              />
              <div class="tag-group-filter-bar-value-list">
                <button
                  v-for="value in filteredActiveValues"
                  :key="value.valueKey"
                  class="tag-group-filter-bar-value"
                  :class="{ 'is-selected': isValueSelected(activeGroup, value) }"
                  type="button"
                  :data-testid="`tag-group-filter-bar-value-${value.valueKey}`"
                  @click="toggleValue(activeGroup, value)"
                >
                  <span class="tag-group-filter-bar-check">
                    <el-icon v-if="isValueSelected(activeGroup, value)"><Check /></el-icon>
                  </span>
                  <span class="tag-group-filter-bar-value-text">
                    <span>{{ value.label }}</span>
                    <small v-if="value.unmappedReason">{{ value.unmappedReason }}</small>
                  </span>
                </button>
                <el-empty
                  v-if="!filteredActiveValues.length"
                  class="tag-group-filter-bar-empty"
                  description="暂无可选标签"
                />
              </div>
            </template>
            <el-empty v-else class="tag-group-filter-bar-empty" description="暂无可选标签组" />
          </main>
        </section>
      </el-popover>

      <span class="tag-group-filter-bar-summary" data-testid="tag-group-filter-bar-summary">
        <template v-if="displayCurrentViewName">
          当前视图：{{ displayCurrentViewName }}
          <el-divider direction="vertical" />
        </template>
        当前 {{ currentTotalText }} 条
        <el-divider direction="vertical" />
        {{ selectedCount }} 个已选
      </span>

      <div class="tag-group-filter-bar-chip-list" aria-label="已选标签">
        <el-tag
          v-for="selection in selectedGroupSummaries.slice(0, 2)"
          :key="selection.key"
          class="tag-group-filter-bar-chip"
          size="small"
          effect="plain"
          closable
          @close="clearGroup(selection.key)"
        >
          {{ selection.label }}: {{ selection.value }}
        </el-tag>
        <el-tag
          v-if="selectedGroupSummaries.length > 2"
          class="tag-group-filter-bar-chip"
          size="small"
          effect="plain"
        >
          +{{ selectedGroupSummaries.length - 2 }}
        </el-tag>
      </div>

      <div class="tag-group-filter-bar-actions">
        <el-popover
          v-model:visible="savePopoverVisible"
          trigger="manual"
          placement="bottom-end"
          width="260"
        >
          <template #reference>
            <el-button plain :icon="Star" :disabled="!tagGroups" @click="openSaveSnapshotForm">保存快照</el-button>
          </template>
          <div class="tag-group-filter-bar-popover">
            <strong class="tag-group-filter-bar-popover-title">保存快照</strong>
            <el-input
              v-model="snapshotName"
              maxlength="30"
              show-word-limit
              placeholder="输入快照名称"
              data-testid="tag-group-snapshot-name-input"
              @keyup.enter="saveSnapshot"
            />
            <div class="tag-group-filter-bar-popover-actions">
              <el-button text @click="savePopoverVisible = false">取消</el-button>
              <el-button
                type="primary"
                :disabled="!canSaveSnapshot"
                data-testid="tag-group-snapshot-save-confirm"
                @click="saveSnapshot"
              >
                保存
              </el-button>
            </div>
          </div>
        </el-popover>
        <el-popover
          v-model:visible="restorePopoverVisible"
          trigger="click"
          placement="bottom-end"
          width="320"
          :disabled="!hasSnapshot || !tagGroups"
        >
          <template #reference>
            <el-button
              plain
              :icon="StarFilled"
              :disabled="!hasSnapshot || !tagGroups"
              data-testid="tag-group-snapshot-restore-trigger"
            >
              恢复快照
            </el-button>
          </template>
          <div class="tag-group-filter-bar-popover">
            <strong class="tag-group-filter-bar-popover-title">恢复快照</strong>
            <div
              v-for="snapshot in snapshotOptions"
              :key="snapshot.id"
              class="tag-group-filter-bar-snapshot-option"
              @click="handleRestoreSnapshot(String(snapshot.id))"
            >
              <button
                class="tag-group-filter-bar-snapshot-restore"
                type="button"
                @click.stop="handleRestoreSnapshot(String(snapshot.id))"
              >
                <span>{{ snapshotTitle(snapshot) }}</span>
                <small>{{ snapshotSubtitle(snapshot) }}</small>
              </button>
              <el-button
                text
                :icon="Delete"
                aria-label="删除快照"
                title="删除快照"
                :data-testid="`tag-group-snapshot-delete-${snapshot.id}`"
                @click.stop="handleDeleteSnapshot(String(snapshot.id))"
              />
            </div>
          </div>
        </el-popover>
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
    </div>
  </section>
</template>

<style scoped>
.tag-group-filter-bar {
  min-width: 0;
}

.tag-group-filter-bar-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-bar-button-count {
  margin-left: 4px;
}

.tag-group-filter-bar-summary {
  min-width: 0;
  color: rgba(15, 23, 42, 0.58);
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
}

.tag-group-filter-bar-chip-list {
  display: flex;
  align-items: center;
  flex: 1 1 220px;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.tag-group-filter-bar-chip {
  max-width: 240px;
}

.tag-group-filter-bar-chip :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-group-filter-bar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-bar-panel {
  display: grid;
  grid-template-columns: 180px minmax(0, 1fr);
  width: 100%;
  min-height: 340px;
  overflow: hidden;
}

.tag-group-filter-bar-groups {
  display: grid;
  align-content: start;
  gap: 4px;
  max-height: 360px;
  padding: 8px;
  overflow-y: auto;
  border-right: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(248, 250, 252, 0.8);
}

.tag-group-filter-bar-group {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 32px;
  padding: 6px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: rgba(15, 23, 42, 0.72);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.tag-group-filter-bar-group:hover,
.tag-group-filter-bar-group.is-active {
  border-color: rgba(37, 99, 235, 0.16);
  background: #fff;
  color: rgba(15, 23, 42, 0.9);
}

.tag-group-filter-bar-values {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 8px;
  min-width: 0;
  padding: 10px;
}

.tag-group-filter-bar-values-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.tag-group-filter-bar-values-head strong {
  overflow: hidden;
  color: rgba(15, 23, 42, 0.86);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-group-filter-bar-search {
  width: 100%;
}

.tag-group-filter-bar-value-list {
  display: grid;
  align-content: start;
  gap: 4px;
  min-height: 0;
  overflow-y: auto;
}

.tag-group-filter-bar-value {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 34px;
  padding: 6px 8px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: #fff;
  color: rgba(15, 23, 42, 0.76);
  text-align: left;
  cursor: pointer;
}

.tag-group-filter-bar-value:hover,
.tag-group-filter-bar-value.is-selected {
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(239, 246, 255, 0.76);
  color: rgba(15, 23, 42, 0.9);
}

.tag-group-filter-bar-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border: 1px solid rgba(148, 163, 184, 0.7);
  border-radius: 4px;
  color: #2563eb;
}

.tag-group-filter-bar-value.is-selected .tag-group-filter-bar-check {
  border-color: rgba(37, 99, 235, 0.6);
  background: rgba(219, 234, 254, 0.8);
}

.tag-group-filter-bar-value-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.tag-group-filter-bar-value-text span,
.tag-group-filter-bar-value-text small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-group-filter-bar-value-text small {
  color: rgba(15, 23, 42, 0.45);
  font-size: 12px;
}

.tag-group-filter-bar-empty {
  --el-empty-padding: 0;
  width: 100%;
}

.tag-group-filter-bar-warning {
  flex-basis: 100%;
}

.tag-group-filter-bar-popover {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.tag-group-filter-bar-popover-title {
  color: rgba(15, 23, 42, 0.82);
  font-size: 13px;
  line-height: 1.4;
}

.tag-group-filter-bar-popover-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.tag-group-filter-bar-snapshot-option {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px;
  width: 100%;
  min-width: 0;
  padding: 8px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 6px;
  background: #fff;
  color: rgba(15, 23, 42, 0.82);
  text-align: left;
  cursor: pointer;
}

.tag-group-filter-bar-snapshot-option + .tag-group-filter-bar-snapshot-option {
  margin-top: 6px;
}

.tag-group-filter-bar-snapshot-option:hover {
  border-color: rgba(37, 99, 235, 0.22);
  background: rgba(239, 246, 255, 0.72);
}

.tag-group-filter-bar-snapshot-restore {
  display: grid;
  gap: 3px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.tag-group-filter-bar-snapshot-option span,
.tag-group-filter-bar-snapshot-option small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tag-group-filter-bar-snapshot-option span {
  font-weight: 600;
}

.tag-group-filter-bar-snapshot-option small {
  color: rgba(15, 23, 42, 0.52);
  font-size: 12px;
}

:global(.tag-group-filter-bar-popper.el-popover.el-popper) {
  padding: 0;
}

@media (max-width: 900px) {
  .tag-group-filter-bar-panel {
    grid-template-columns: 1fr;
  }

  .tag-group-filter-bar-groups {
    display: flex;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  }

  .tag-group-filter-bar-group {
    width: auto;
    white-space: nowrap;
  }
}
</style>
