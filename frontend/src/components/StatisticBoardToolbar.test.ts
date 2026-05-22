import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import StatisticBoardToolbar from './StatisticBoardToolbar.vue';
import type { StatisticFilterDraftGroup } from './statistic-board-filters';
import type { StatisticFilterField } from '../types/api';

const filterDraft: StatisticFilterDraftGroup = {
  logic: 'AND',
  conditions: [],
};

const fields: StatisticFilterField[] = [
  {
    key: 'projectId',
    label: 'Project',
    type: 'select',
    operators: ['eq'],
    options: [],
  },
];

function mountToolbar() {
  return mount(StatisticBoardToolbar, {
    props: {
      filterDraft,
      activeFilterFields: fields,
      boardTitle: 'Defect summary',
      lastSyncedText: '2026-04-30 10:00',
      ruleExplanationLoading: false,
      realtimeStatus: {
        workspaceKey: 'system-test-defect-summary',
        supported: true,
        status: 'READY',
        message: 'ok',
        refreshing: false,
        lastSyncedAt: '2026-04-30T10:00:00',
        mirrorStatus: 'SUCCESS',
        factStatus: 'SUCCESS',
      },
      autoRefreshOnEnter: true,
      uiHooks: {
        toolbarClass: 'toolbar-hook',
        toolbarMainClass: 'main-hook',
        toolbarActionsClass: 'actions-hook',
      },
    },
    global: {
      stubs: {
        StatisticFilterBuilder: {
          props: ['modelValue', 'fields'],
          template: '<div data-testid="filter-builder">{{ fields.length }}</div>',
        },
        SyncMetaBadge: {
          props: ['value'],
          template: '<span data-testid="sync-meta">{{ value }}</span>',
        },
        ElButton: {
          props: ['icon', 'loading'],
          emits: ['click'],
          template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
        },
        ElTag: {
          props: ['type', 'size'],
          template: '<span class="el-tag"><slot /></span>',
        },
        ElDropdown: {
          emits: ['command'],
          template: '<div data-testid="settings-dropdown"><slot /><slot name="dropdown" /></div>',
        },
        ElDropdownMenu: {
          template: '<div><slot /></div>',
        },
        ElDropdownItem: {
          props: ['command'],
          template: '<button type="button" class="dropdown-item" @click="$parent?.$emit(\'command\', command)"><slot /></button>',
        },
      },
    },
  });
}

describe('StatisticBoardToolbar', () => {
  it('renders filter builder, board title and sync metadata', () => {
    const wrapper = mountToolbar();

    expect(wrapper.get('[data-testid="filter-builder"]').text()).toBe('1');
    expect(wrapper.text()).toContain('Defect summary');
    expect(wrapper.get('[data-testid="sync-meta"]').text()).toBe('2026-04-30 10:00');
    expect(wrapper.get('[data-testid="realtime-refresh-status"]').text()).toContain('已是最新');
    expect(wrapper.get('[data-testid="realtime-refresh-status"]').text()).toContain('镜像已完成');
    expect(wrapper.get('[data-testid="realtime-refresh-status"]').text()).toContain('事实已完成');
    expect(wrapper.text()).toContain('关闭进入页面自动刷新');
    expect(wrapper.classes()).toContain('toolbar-hook');
    expect(wrapper.find('.stat-board-toolbar-main').classes()).toContain('main-hook');
    expect(wrapper.find('.stat-board-toolbar-actions').classes()).toContain('actions-hook');
  });

  it('emits toolbar action events', async () => {
    const wrapper = mountToolbar();
    const buttons = wrapper.findAll('button');

    await buttons.find((button) => button.text() === '查询')?.trigger('click');
    await buttons.find((button) => button.text() === '重置')?.trigger('click');
    await buttons.find((button) => button.text() === '刷新最新数据')?.trigger('click');
    await buttons.find((button) => button.text() === '规则说明')?.trigger('click');
    await buttons.find((button) => button.text() === '导出')?.trigger('click');

    expect(wrapper.emitted('applyFilters')).toHaveLength(1);
    expect(wrapper.emitted('resetFilters')).toHaveLength(1);
    expect(wrapper.emitted('refreshBoard')).toHaveLength(1);
    expect(wrapper.emitted('openRuleExplanation')).toHaveLength(1);
    expect(wrapper.emitted('exportBoard')).toHaveLength(1);
  });

  it('shows the enable copy when page auto refresh is disabled', async () => {
    const wrapper = mountToolbar();
    await wrapper.setProps({ autoRefreshOnEnter: false });

    expect(wrapper.text()).toContain('开启进入页面自动刷新');
  });

  it('shows two-stage refresh progress and failure copy', async () => {
    const refreshing = mountToolbar();
    await refreshing.setProps({
      realtimeStatus: {
        workspaceKey: 'system-test-defect-summary',
        supported: true,
        status: 'REFRESHING',
        message: 'refreshing',
        refreshing: true,
        mirrorStatus: 'SUCCESS',
        factStatus: 'RUNNING',
      },
    });
    expect(refreshing.get('[data-testid="realtime-refresh-status"]').text()).toContain('事实刷新中');

    const failed = mountToolbar();
    await failed.setProps({
      realtimeStatus: {
        workspaceKey: 'system-test-defect-summary',
        supported: true,
        status: 'FAILED',
        message: 'failed',
        refreshing: false,
        mirrorStatus: 'SUCCESS',
        factStatus: 'FAILED',
      },
    });
    expect(failed.get('[data-testid="realtime-refresh-status"]').text()).toContain('部分失败');
    expect(failed.get('[data-testid="realtime-refresh-status"]').text()).toContain('事实失败');
  });
});
