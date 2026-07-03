<script setup lang="ts">
import { computed } from 'vue';
import { Download, InfoFilled, RefreshRight, Setting } from '@element-plus/icons-vue';
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import type { RealtimeWorkspaceStatusResponse, StatisticFilterField } from '../types/api';
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
    realtimeStatus?: RealtimeWorkspaceStatusResponse | null;
    canRefreshRealtime?: boolean;
    autoRefreshOnEnter?: boolean;
    showExport?: boolean;
    exportLabel?: string;
    extraActions?: StatisticBoardToolbarAction[];
    uiHooks?: StatisticBoardUiHooks;
  }>(),
  {
    boardTitle: '',
    realtimeStatus: null,
    canRefreshRealtime: true,
    autoRefreshOnEnter: true,
    showExport: true,
    exportLabel: '导出',
    extraActions: () => [],
    uiHooks: () => ({}),
  },
);

const emit = defineEmits<{
  (event: 'applyFilters'): void;
  (event: 'resetFilters'): void;
  (event: 'refreshBoard'): void;
  (event: 'openRuleExplanation'): void;
  (event: 'exportBoard'): void;
  (event: 'extraAction', actionKey: string): void;
  (event: 'settingsCommand', command: string): void;
  (event: 'toggleAutoRefresh', enabled: boolean): void;
}>();

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
const taskStartedText = computed(() => formatDateTime(props.realtimeStatus?.lastRefreshStartedAt));
const taskDurationText = computed(() =>
  formatDuration(
    props.realtimeStatus?.lastRefreshStartedAt,
    props.realtimeStatus?.lastRefreshFinishedAt,
    props.realtimeStatus?.refreshing,
  ),
);

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

function formatDateTime(value?: string | null) {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatDuration(startedAt?: string | null, finishedAt?: string | null, refreshing?: boolean | null) {
  if (!startedAt) {
    return '';
  }
  if (refreshing && !finishedAt) {
    return '进行中';
  }
  if (!finishedAt) {
    return '';
  }
  const start = new Date(startedAt).getTime();
  const finish = new Date(finishedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(finish) || finish < start) {
    return '';
  }
  const totalSeconds = Math.round((finish - start) / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes <= 0) {
    return `${seconds} 秒`;
  }
  return `${minutes} 分 ${seconds} 秒`;
}
</script>

<template>
  <div class="stat-board-toolbar" :class="props.uiHooks.toolbarClass">
    <div class="stat-board-toolbar-filter-row">
      <div class="stat-board-toolbar-main" :class="props.uiHooks.toolbarMainClass">
        <StatisticFilterBuilder
          :model-value="filterDraft"
          :fields="activeFilterFields"
          show-apply-actions
          @apply="emit('applyFilters')"
          @reset="emit('resetFilters')"
        />
      </div>
    </div>

    <div class="stat-board-toolbar-status-row">
      <div class="stat-board-toolbar-status">
        <slot name="scope" />
        <span v-if="boardTitle" class="stat-board-meta-text">{{ boardTitle }}</span>
        <SyncMetaBadge :value="lastSyncedText" />
        <div v-if="realtimeStatus" class="stat-board-refresh-status" data-testid="realtime-refresh-status">
          <el-tag size="small" :type="workspaceStatusTagType">{{ workspaceStatusText }}</el-tag>
          <span v-if="showStageStatusDetails">{{ mirrorStatusText }}</span>
          <span v-if="showStageStatusDetails">{{ factStatusText }}</span>
          <span v-if="taskStartedText">任务执行时间：{{ taskStartedText }}</span>
          <span v-if="taskDurationText">执行时长：{{ taskDurationText }}</span>
        </div>
      </div>

      <div class="stat-board-toolbar-actions" :class="props.uiHooks.toolbarActionsClass">
        <el-button
          v-if="canRefreshRealtime"
          class="app-action-button app-action-button--refresh"
          :icon="RefreshRight"
          @click="emit('refreshBoard')"
        >
          刷新最新数据
        </el-button>
        <el-button
          class="app-action-button app-action-button--rule"
          plain
          :icon="InfoFilled"
          :loading="ruleExplanationLoading"
          @click="emit('openRuleExplanation')"
        >
          规则说明
        </el-button>
        <el-button
          v-for="action in extraActions"
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
        <el-button
          v-if="showExport"
          class="app-action-button app-action-button--export"
          plain
          :icon="Download"
          @click="emit('exportBoard')"
        >
          {{ exportLabel }}
        </el-button>
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
    </div>
  </div>
</template>

<style scoped>
.stat-board-toolbar {
  display: grid;
  gap: 10px;
  width: 100%;
  min-width: 0;
}

.stat-board-toolbar-status-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.stat-board-toolbar-filter-row {
  min-width: 0;
}

.stat-board-toolbar-main {
  display: grid;
  min-width: 0;
}

.stat-board-toolbar-status,
.stat-board-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  max-width: 100%;
  min-width: 0;
}

.stat-board-toolbar-status {
  justify-content: flex-start;
}

.stat-board-toolbar-actions {
  justify-content: flex-end;
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
  .stat-board-toolbar-status-row {
    grid-template-columns: 1fr;
  }

  .stat-board-toolbar-actions {
    justify-content: flex-start;
    width: 100%;
  }
}
</style>
