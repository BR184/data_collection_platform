<script setup lang="ts">
import { Download, InfoFilled, RefreshRight, Search } from '@element-plus/icons-vue';
// 统计板工具栏只承载刷新、规则说明和导出等横向动作，保持看板内容区专注展示。
// 这里不读取业务数据，所有按钮状态都由父级显式传入。
import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import SyncMetaBadge from './realtime/SyncMetaBadge.vue';
import type { StatisticFilterField } from '../types/api';
import type { StatisticFilterDraftGroup } from './statistic-board-filters';
import type { StatisticBoardUiHooks } from './statistic-board-ui';

withDefaults(
  defineProps<{
    filterDraft: StatisticFilterDraftGroup;
    activeFilterFields: StatisticFilterField[];
    boardTitle?: string;
    lastSyncedText: string;
    ruleExplanationLoading: boolean;
    uiHooks?: StatisticBoardUiHooks;
  }>(),
  {
    boardTitle: '',
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
</script>

<template>
  <div class="stat-board-toolbar" :class="uiHooks.toolbarClass">
    <div class="stat-board-toolbar-main" :class="uiHooks.toolbarMainClass">
      <StatisticFilterBuilder :model-value="filterDraft" :fields="activeFilterFields" />
    </div>

    <div class="stat-board-toolbar-actions" :class="uiHooks.toolbarActionsClass">
      <span v-if="boardTitle" class="stat-board-meta-text">{{ boardTitle }}</span>
      <SyncMetaBadge :value="lastSyncedText" />
      <el-button type="primary" :icon="Search" @click="emit('applyFilters')">查询</el-button>
      <el-button @click="emit('resetFilters')">重置</el-button>
      <el-button :icon="RefreshRight" @click="emit('refreshBoard')">刷新最新数据</el-button>
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
