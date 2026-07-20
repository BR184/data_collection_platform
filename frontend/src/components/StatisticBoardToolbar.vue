<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, QuestionFilled, RefreshRight, Setting } from '@element-plus/icons-vue';
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import ExportActionMenu from './base/ExportActionMenu.vue';
import RecordTableFilterFields from './base/RecordTableFilterFields.vue';
import TableFunctionBar from './base/TableFunctionBar.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import type { RealtimeWorkspaceStatusResponse, StatisticFilterField } from '../types/api';
import type { RecordTableFilterField } from '../types/record-table';
import type { StatisticFilterDraftGroup } from './statistic-board-filters';
import type { StatisticBoardToolbarAction, StatisticBoardUiHooks } from './statistic-board-ui';
import { toUserMessage } from '../utils/user-message';

const props = withDefaults(
  defineProps<{
    filterDraft: StatisticFilterDraftGroup;
    activeFilterFields: StatisticFilterField[];
    boardTitle?: string;
    lastSyncedText: string;
    ruleExplanationLoading: boolean;
    ruleExplanationSummary?: string;
    realtimeStatus?: RealtimeWorkspaceStatusResponse | null;
    canRefreshRealtime?: boolean;
    autoRefreshOnEnter?: boolean;
    showExport?: boolean;
    exportLabel?: string;
    quickFilterFields?: RecordTableFilterField[];
    quickFilterValues?: Record<string, unknown>;
    quickFilterInputDrafts?: Record<string, string>;
    disabledQuickFilterKeys?: string[];
    highlightedQuickFilterKeys?: string[];
    quickFilterChangeGuard?: (key: string, value: string | string[] | null) => boolean;
    extraActions?: StatisticBoardToolbarAction[];
    uiHooks?: StatisticBoardUiHooks;
  }>(),
  {
    boardTitle: '',
    ruleExplanationSummary: '',
    realtimeStatus: null,
    canRefreshRealtime: true,
    autoRefreshOnEnter: true,
    showExport: true,
    exportLabel: '导出',
    quickFilterFields: () => [],
    quickFilterValues: () => ({}),
    quickFilterInputDrafts: () => ({}),
    disabledQuickFilterKeys: () => [],
    highlightedQuickFilterKeys: () => [],
    quickFilterChangeGuard: () => true,
    extraActions: () => [],
    uiHooks: () => ({}),
  },
);

const emit = defineEmits<{
  (event: 'applyFilters'): void;
  (event: 'resetFilters'): void;
  (event: 'draftChange'): void;
  (event: 'refreshBoard'): void;
  (event: 'openRuleExplanation'): void;
  (event: 'loadRuleExplanation'): void;
  (event: 'exportBoard'): void;
  (event: 'extraAction', actionKey: string): void;
  (event: 'settingsCommand', command: string): void;
  (event: 'toggleAutoRefresh', enabled: boolean): void;
  (event: 'quickFilterChange', payload: { key: string; value: string | string[] | null }): void;
  (event: 'quickFilterInputUpdate', payload: { key: string; value: string }): void;
}>();

const PRIMARY_EXPORT_COMMAND = '__primary_export__';

const quickFiltersExpanded = ref(false);
const conditionFiltersExpanded = ref(false);
const localQuickFilterInputDrafts = ref<Record<string, string>>({});
const localQuickFilterValues = ref<Record<string, unknown>>({});
const exportExtraActions = computed(() => props.extraActions.filter(isExportAction));
const nonExportExtraActions = computed(() => props.extraActions.filter((action) => !isExportAction(action)));
const exportMenuItems = computed(() => [
  ...(props.showExport ? [{ key: PRIMARY_EXPORT_COMMAND, label: props.exportLabel, disabled: false }] : []),
  ...exportExtraActions.value.map((action) => ({
    key: action.key,
    label: action.label,
    disabled: action.disabled ?? false,
  })),
]);
const useExportDropdown = computed(() => exportMenuItems.value.length >= 2);
const inlineExtraActions = computed(() => (useExportDropdown.value ? nonExportExtraActions.value : props.extraActions));
const exportDropdownLoading = computed(() => exportExtraActions.value.some((action) => Boolean(action.loading)));
const hasQuickFilters = computed(() => props.quickFilterFields.length > 0);
const quickFilterToggleText = computed(() =>
  quickFiltersExpanded.value ? '收起快速筛选' : `快速筛选（${props.quickFilterFields.length}）`,
);
const quickFilterToggleIcon = computed(() => (quickFiltersExpanded.value ? ArrowUp : ArrowDown));
const quickFilterSummaryChips = computed(() => buildQuickFilterSummaryChips());

watch(
  () => props.quickFilterInputDrafts,
  (value) => {
    localQuickFilterInputDrafts.value = { ...value };
  },
  { immediate: true, deep: true },
);

watch(
  () => props.quickFilterValues,
  (value) => {
    localQuickFilterValues.value = { ...value };
  },
  { immediate: true, deep: true },
);

const activeStatuses = new Set(['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING', 'REFRESHING']);
const failureStatuses = new Set(['FAILED', 'TIMEOUT', 'CANCELLED']);
const partialStatuses = new Set(['PARTIAL_SUCCESS']);
const internalStatusNames = new Set([
  ...activeStatuses,
  ...failureStatuses,
  ...partialStatuses,
  'READY',
  'SUCCESS',
  'STALE',
]);

const workspaceStatusText = computed(() => {
  const status = props.realtimeStatus;
  if (!status) {
    return '';
  }
  if (status.refreshing) {
    return activeStatuses.has(status.factStatus || '') ? '事实刷新中' : '镜像同步中';
  }
  if (failureStatuses.has(status.mirrorStatus || '') || failureStatuses.has(status.factStatus || '')) {
    return '已展示当前可用数据';
  }
  if (status.status === 'READY') {
    return '已是最新';
  }
  return formatWorkspaceMessage(status);
});

const workspaceStatusTagType = computed(() => {
  const status = props.realtimeStatus;
  if (!status) {
    return 'info';
  }
  if (status.refreshing) {
    return 'warning';
  }
  if (failureStatuses.has(status.mirrorStatus || '') || failureStatuses.has(status.factStatus || '')) {
    return 'warning';
  }
  return 'success';
});

const mirrorStatusText = computed(() => formatStageStatus('镜像', props.realtimeStatus?.mirrorStatus));
const factStatusText = computed(() => formatStageStatus('事实', props.realtimeStatus?.factStatus));
const showStageStatusDetails = computed(() => {
  const status = props.realtimeStatus;
  if (!status) {
    return false;
  }
  return Boolean(status.refreshing)
    || failureStatuses.has(status.mirrorStatus || '')
    || failureStatuses.has(status.factStatus || '')
    || partialStatuses.has(status.mirrorStatus || '')
    || partialStatuses.has(status.factStatus || '');
});
function formatStageStatus(label: string, status?: string | null) {
  if (!status) {
    return `${label}待刷新`;
  }
  if (activeStatuses.has(status)) {
    return `${label}${label === '镜像' ? '同步' : '刷新'}中`;
  }
  if (failureStatuses.has(status)) {
    return `${label}待更新`;
  }
  if (partialStatuses.has(status)) {
    return `${label}已更新，需查看明细`;
  }
  if (status === 'SUCCESS' || status === 'READY') {
    return `${label}已完成`;
  }
  return `${label}待查看`;
}

function formatWorkspaceMessage(status: RealtimeWorkspaceStatusResponse) {
  const message = toUserMessage(status.message, '');
  if (message && !internalStatusNames.has(message)) {
    return message;
  }
  if (status.status && !internalStatusNames.has(status.status)) {
    return status.status;
  }
  return '状态待确认';
}

function isExportAction(action: StatisticBoardToolbarAction) {
  return action.key.startsWith('export-') || action.actionClass?.split(/\s+/).includes('app-action-button--export');
}

function handleExportDropdownCommand(command: string | number | object) {
  const actionKey = String(command);
  if (actionKey === PRIMARY_EXPORT_COMMAND) {
    emit('exportBoard');
    return;
  }
  emit('extraAction', actionKey);
}

function updateQuickFilterInput(key: string, value: string) {
  localQuickFilterInputDrafts.value = {
    ...localQuickFilterInputDrafts.value,
    [key]: value,
  };
}

function commitQuickFilterInput(key: string, value: string) {
  if (!props.quickFilterChangeGuard(key, value)) {
    updateQuickFilterInput(key, String(localQuickFilterValues.value[key] ?? ''));
    return;
  }
  emit('quickFilterInputUpdate', { key, value });
  emit('quickFilterChange', { key, value });
  emit('applyFilters');
}

function commitQuickFilterValue(key: string, value: string | string[] | null) {
  if (!props.quickFilterChangeGuard(key, value)) {
    localQuickFilterValues.value = { ...props.quickFilterValues };
    return;
  }
  localQuickFilterValues.value = {
    ...localQuickFilterValues.value,
    [key]: value,
  };
  emit('quickFilterChange', { key, value });
  emit('applyFilters');
}

function buildQuickFilterSummaryChips() {
  const chips: Array<{ id: string; label: string }> = [];
  for (const filter of props.quickFilterFields) {
    const value = localQuickFilterValues.value[filter.key];
    const label = formatQuickFilterSummaryValue(filter, value);
    if (!label) {
      continue;
    }
    chips.push({
      id: `quick:${filter.key}`,
      label: `${filter.label} ${label}`,
    });
  }
  return chips;
}

function formatQuickFilterSummaryValue(filter: RecordTableFilterField, value: unknown) {
  if (filter.type === 'daterange') {
    if (!Array.isArray(value) || value.length !== 2 || !value[0] || !value[1]) {
      return '';
    }
    return `${value[0]} ~ ${value[1]}`;
  }
  if (filter.type === 'select') {
    const values = Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : [String(value ?? '').trim()].filter(Boolean);
    if (!values.length) {
      return '';
    }
    return values.map((item) => filter.options?.find((option) => option.value === item)?.label || item).join('、');
  }
  return String(value ?? '').trim();
}
</script>

<template>
  <TableFunctionBar
    class="stat-board-toolbar"
    :class="props.uiHooks.toolbarClass"
    :quick-visible="quickFiltersExpanded && hasQuickFilters"
  >
    <template #filter>
      <div class="stat-board-toolbar-main" :class="props.uiHooks.toolbarMainClass">
        <StatisticFilterBuilder
          :model-value="filterDraft"
          :fields="activeFilterFields"
          :extra-summary-chips="quickFilterSummaryChips"
          :expanded="conditionFiltersExpanded"
          show-apply-actions
          @draft-change="emit('draftChange')"
          @apply="emit('applyFilters')"
          @reset="emit('resetFilters')"
          @update:expanded="conditionFiltersExpanded = $event"
        >
          <template #summary-actions-extra>
            <el-button
              v-if="hasQuickFilters"
              class="app-action-button app-action-button--filter"
              plain
              :icon="quickFilterToggleIcon"
              @click="quickFiltersExpanded = !quickFiltersExpanded"
            >
              {{ quickFilterToggleText }}
            </el-button>
          </template>
        </StatisticFilterBuilder>
      </div>
    </template>

    <template v-if="hasQuickFilters" #quick>
      <RecordTableFilterFields
        :filters="quickFilterFields"
        :filter-values="localQuickFilterValues"
        :input-drafts="localQuickFilterInputDrafts"
        :keyword-field-visible="quickFilterFields.some((field) => field.key === 'keyword')"
        :disabled-keys="disabledQuickFilterKeys"
        :highlighted-keys="highlightedQuickFilterKeys"
        @input-update="updateQuickFilterInput"
        @input-change="commitQuickFilterInput"
        @input-search="(key) => commitQuickFilterInput(key, String(localQuickFilterInputDrafts[key] ?? localQuickFilterValues[key] ?? ''))"
        @input-clear="(key) => commitQuickFilterInput(key, '')"
        @filter-change="commitQuickFilterValue"
      />
      <div v-if="!conditionFiltersExpanded" class="stat-board-toolbar-quick-actions">
        <el-button class="app-action-button app-action-button--reset" @click="emit('resetFilters')">重置</el-button>
      </div>
    </template>

    <template #status>
      <slot name="scope" />
      <span v-if="boardTitle" class="stat-board-meta-text">{{ boardTitle }}</span>
      <SyncMetaBadge :value="lastSyncedText" />
      <div v-if="realtimeStatus" class="stat-board-refresh-status" data-testid="realtime-refresh-status">
        <el-tag size="small" :type="workspaceStatusTagType">{{ workspaceStatusText }}</el-tag>
        <span v-if="showStageStatusDetails">{{ mirrorStatusText }}</span>
        <span v-if="showStageStatusDetails">{{ factStatusText }}</span>
      </div>
    </template>

    <template #actions>
      <div class="stat-board-toolbar-actions" :class="props.uiHooks.toolbarActionsClass">
        <el-button
          v-if="canRefreshRealtime"
          class="app-action-button app-action-button--refresh"
          :icon="RefreshRight"
          @click="emit('refreshBoard')"
        >
          刷新最新数据
        </el-button>
        <el-tooltip
          :content="ruleExplanationSummary || (ruleExplanationLoading ? '正在加载统计规则…' : '悬停查看统计规则，点击打开完整说明')"
          placement="top"
          :show-after="200"
        >
          <el-button
            class="app-action-button app-action-button--rule stat-board-rule-icon"
            plain
            circle
            :icon="QuestionFilled"
            :loading="ruleExplanationLoading"
            aria-label="规则说明"
            @mouseenter="emit('loadRuleExplanation')"
            @focus="emit('loadRuleExplanation')"
            @click="emit('openRuleExplanation')"
          />
        </el-tooltip>
        <el-button
          v-for="action in inlineExtraActions"
          :key="action.key"
          :class="['app-action-button', action.actionClass || 'app-action-button--neutral']"
          :plain="action.plain ?? true"
          :icon="action.icon"
          :loading="action.loading"
          :disabled="action.disabled"
          @click="emit('extraAction', action.key)"
        >
          {{ action.label }}
        </el-button>
        <ExportActionMenu
          v-if="exportMenuItems.length"
          :actions="exportMenuItems"
          :loading="exportDropdownLoading"
          @select="handleExportDropdownCommand"
        />
        <el-dropdown trigger="click" @command="(command: string) => emit('settingsCommand', command)">
          <el-button
            class="view-settings-trigger app-action-button app-action-button--settings"
            :icon="Setting"
          >
            设置
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="open-settings">列显示设置</el-dropdown-item>
              <el-dropdown-item command="open-saved-views">固定/管理视图</el-dropdown-item>
              <el-dropdown-item command="clear-sort">恢复默认排序</el-dropdown-item>
              <el-dropdown-item divided command="noop" class="view-settings-switch-item">
                <span>进入页面自动刷新</span>
                <el-switch
                  :model-value="autoRefreshOnEnter"
                  inline-prompt
                  active-text="开"
                  inactive-text="关"
                  @click.stop
                  @change="emit('toggleAutoRefresh', Boolean($event))"
                />
              </el-dropdown-item>
              <el-dropdown-item command="restore-default-view">恢复默认视图</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </template>
  </TableFunctionBar>
</template>

<style scoped>
.stat-board-toolbar {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.stat-board-toolbar-main {
  display: grid;
  min-width: 0;
}

.stat-board-toolbar-quick-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-left: auto;
}

.stat-board-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  max-width: 100%;
  min-width: 0;
}

.stat-board-toolbar-actions {
  justify-content: flex-end;
}

.stat-board-rule-icon {
  width: 32px;
  height: 32px;
  padding: 0;
}

.stat-board-toolbar-actions :deep(.el-button + .el-button),
.stat-board-toolbar-actions :deep(.el-dropdown + .el-button),
.stat-board-toolbar-actions :deep(.el-button + .el-dropdown),
.stat-board-toolbar-actions :deep(.el-dropdown + .el-dropdown) {
  margin-left: 0;
}

.view-settings-switch-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 220px;
}

.stat-board-refresh-status {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 32px;
  color: rgba(15, 23, 42, 0.68);
  font-size: 12px;
  min-width: 0;
}

@media (max-width: 1180px) {
  .stat-board-toolbar-actions {
    justify-content: flex-start;
    width: 100%;
  }

  .stat-board-toolbar-quick-actions {
    margin-left: 0;
  }
}
</style>
