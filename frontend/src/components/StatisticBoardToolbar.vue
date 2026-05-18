<script setup lang="ts">
import { computed } from 'vue';
import { Download, InfoFilled, RefreshRight, Search } from '@element-plus/icons-vue';
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import type { RealtimeWorkspaceStatusResponse, StatisticFilterField } from '../types/api';
import type { StatisticFilterDraftGroup } from './statistic-board-filters';
import type { StatisticBoardUiHooks } from './statistic-board-ui';

const props = withDefaults(
  defineProps<{
    filterDraft: StatisticFilterDraftGroup;
    activeFilterFields: StatisticFilterField[];
    boardTitle?: string;
    lastSyncedText: string;
    ruleExplanationLoading: boolean;
    realtimeStatus?: RealtimeWorkspaceStatusResponse | null;
    canRefreshRealtime?: boolean;
    uiHooks?: StatisticBoardUiHooks;
  }>(),
  {
    boardTitle: '',
    realtimeStatus: null,
    canRefreshRealtime: true,
    uiHooks: () => ({}),
  },
);

const emit = defineEmits<{
  (event: 'applyFilters'): void;
  (event: 'resetFilters'): void;
  (event: 'refreshBoard'): void;
  (event: 'openRuleExplanation'): void;
  (event: 'exportBoard'): void;
  (event: 'settingsCommand', command: string): void;
}>();

const activeStatuses = new Set(['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING', 'REFRESHING']);
const failureStatuses = new Set(['FAILED', 'TIMEOUT', 'CANCELLED']);

const workspaceStatusText = computed(() => {
  const status = props.realtimeStatus;
  if (!status) {
    return '';
  }
  if (status.refreshing) {
    return activeStatuses.has(status.factStatus || '') ? '事实刷新中' : '镜像同步中';
  }
  if (failureStatuses.has(status.mirrorStatus || '') || failureStatuses.has(status.factStatus || '')) {
    return '部分失败';
  }
  if (status.status === 'READY') {
    return '已是最新';
  }
  return status.message || status.status;
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
    return 'danger';
  }
  return 'success';
});

const mirrorStatusText = computed(() => formatStageStatus('镜像', props.realtimeStatus?.mirrorStatus));
const factStatusText = computed(() => formatStageStatus('事实', props.realtimeStatus?.factStatus));

function formatStageStatus(label: string, status?: string | null) {
  if (!status) {
    return `${label}待刷新`;
  }
  if (activeStatuses.has(status)) {
    return `${label}${label === '镜像' ? '同步' : '刷新'}中`;
  }
  if (failureStatuses.has(status)) {
    return `${label}失败`;
  }
  if (status === 'SUCCESS' || status === 'READY') {
    return `${label}已完成`;
  }
  return `${label}${status}`;
}
</script>

<template>
  <div class="stat-board-toolbar" :class="props.uiHooks.toolbarClass">
    <div class="stat-board-toolbar-main" :class="props.uiHooks.toolbarMainClass">
      <StatisticFilterBuilder :model-value="filterDraft" :fields="activeFilterFields" />
    </div>

    <div class="stat-board-toolbar-actions" :class="props.uiHooks.toolbarActionsClass">
      <span v-if="boardTitle" class="stat-board-meta-text">{{ boardTitle }}</span>
      <SyncMetaBadge :value="lastSyncedText" />
      <div v-if="realtimeStatus" class="stat-board-refresh-status" data-testid="realtime-refresh-status">
        <el-tag size="small" :type="workspaceStatusTagType">{{ workspaceStatusText }}</el-tag>
        <span>{{ mirrorStatusText }}</span>
        <span>{{ factStatusText }}</span>
      </div>
      <el-button type="primary" :icon="Search" @click="emit('applyFilters')">查询</el-button>
      <el-button @click="emit('resetFilters')">重置</el-button>
      <el-button v-if="canRefreshRealtime" :icon="RefreshRight" @click="emit('refreshBoard')">刷新最新数据</el-button>
      <el-button
        plain
        :icon="InfoFilled"
        :loading="ruleExplanationLoading"
        @click="emit('openRuleExplanation')"
      >
        规则说明
      </el-button>
      <el-button plain :icon="Download" @click="emit('exportBoard')">导出</el-button>
      <el-dropdown trigger="click" @command="(command: string) => emit('settingsCommand', command)">
        <el-button class="view-settings-trigger">
          <span class="hamburger-icon" aria-hidden="true">
            <span></span>
            <span></span>
            <span></span>
          </span>
          <span>设置</span>
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="open-settings">列显示设置</el-dropdown-item>
            <el-dropdown-item command="clear-sort">恢复默认排序</el-dropdown-item>
            <el-dropdown-item command="restore-default-view">恢复默认视图</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<style scoped>
.stat-board-refresh-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
  color: rgba(15, 23, 42, 0.68);
  font-size: 12px;
  white-space: nowrap;
}
</style>
