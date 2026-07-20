import { mount } from '@vue/test-utils';
import type { ECElementEvent } from 'echarts/core';
import { describe, expect, it } from 'vitest';
import DashboardChartCard from './DashboardChartCard.vue';
import AnalyticsDashboardDetailCell from './AnalyticsDashboardDetailCell.vue';
import RuleHintIcon from './RuleHintIcon.vue';
import { normalizeEChartPointClick } from '../charts/echart-panel-events';
import { buildAnalyticsDetailRoute } from './detail-view-routes';
import type { AnalyticsDashboardChart } from '../../types/api';
import {
  formatAnalyticsDetailValue,
  safeAnalyticsDetailLink,
  shouldShowAnalyticsDetailPagination,
} from './analytics-dashboard-detail-cell';

describe('analytics dashboard shared foundation', () => {
  it('shows the rule formula, scope, target and description in the question icon tooltip', () => {
    const wrapper = mount(RuleHintIcon, {
      props: {
        rule: {
          key: 'remaining-defects',
          title: '剩余缺陷',
          formula: 'open 状态缺陷数量',
          scope: '当前项目与测试阶段',
          target: '0 个',
          description: '按指派人汇总',
        },
      },
      global: {
        stubs: {
          ElTooltip: { template: '<div><slot name="content"/><slot /></div>' },
          ElIcon: { template: '<i><slot /></i>' },
          QuestionFilled: { template: '<span />' },
        },
      },
    });

    expect(wrapper.text()).toContain('open 状态缺陷数量');
    expect(wrapper.text()).toContain('当前项目与测试阶段');
    expect(wrapper.text()).toContain('0 个');
    expect(wrapper.text()).toContain('按指派人汇总');
  });

  it('normalizes only backend-provided point identity and detail parameters', () => {
    const event = {
      componentType: 'series',
      seriesName: '剩余缺陷',
      seriesIndex: 0,
      dataIndex: 1,
      name: '张三',
      value: 4,
      data: {
        value: 4,
        pointKey: 'assignee:zhangsan',
        detailViewKey: 'assignee-remaining-defects',
        detailParams: { assigneeName: '张三', projectId: 9 },
      },
    } as unknown as ECElementEvent;

    expect(normalizeEChartPointClick(event)).toMatchObject({
      pointKey: 'assignee:zhangsan',
      detailViewKey: 'assignee-remaining-defects',
      detailParams: { assigneeName: '张三', projectId: '9' },
      name: '张三',
      value: 4,
    });
  });

  it('keeps title, point and export interactions separate', async () => {
    const chart: AnalyticsDashboardChart = {
      key: 'remaining-defects',
      title: '指派人剩余缺陷数量',
      option: {},
      detail: { viewKey: 'assignee-remaining-defects', params: {} },
      export: { exportKey: 'remaining-defects', label: '导出' },
    };
    const point = {
      value: 4,
      detailParams: { assigneeName: '张三' },
      data: {},
    };
    const wrapper = mount(DashboardChartCard, {
      props: { chart },
      global: {
        stubs: {
          EChartPanel: {
            emits: ['point-click'],
            template: '<button data-testid="point" @click="$emit(\'point-click\', point)">point</button>',
            setup: () => ({ point }),
          },
          RuleHintIcon: { template: '<button data-testid="rule">rule</button>' },
          ExportActionMenu: {
            props: ['actions'],
            emits: ['select'],
            template: '<button data-testid="export" @click.stop="$emit(\'select\', actions[0]?.key)"><slot /></button>',
          },
        },
      },
    });

    await wrapper.get('.dashboard-chart-card__detail-action').trigger('click');
    expect(wrapper.emitted('title-click')).toHaveLength(1);
    expect(wrapper.emitted('point-click')).toBeUndefined();
    expect(wrapper.emitted('export')).toBeUndefined();

    await wrapper.get('[data-testid="point"]').trigger('click');
    expect(wrapper.emitted('point-click')).toHaveLength(1);
    expect(wrapper.emitted('export')).toBeUndefined();

    await wrapper.get('[data-testid="export"]').trigger('click');
    expect(wrapper.emitted('export')).toHaveLength(1);
    expect(wrapper.emitted('title-click')).toHaveLength(1);
  });

  it('forwards only the exact registered detail parameters and preserves system-test scope', () => {
    const systemTestRoute = buildAnalyticsDetailRoute(
      'system-test-multi',
      {
        viewKey: 'system-test-issue-records',
        params: {
          projectId: '9',
          testingPhase: 'CC2026R3',
          assigneeName: '张三',
          unknown: 'drop-me',
        },
      },
    );
    expect(systemTestRoute).toMatchObject({
      path: '/question-metrics/issue-search',
      query: {
        projectId: '9',
        testingPhase: 'CC2026R3',
        assigneeName: '张三',
      },
    });
    expect((systemTestRoute as { query: Record<string, string> }).query).not.toHaveProperty('unknown');

    expect(() => buildAnalyticsDetailRoute(
      'quality-rd',
      { viewKey: 'unregistered-view', params: { projectName: 'CC2026R3' } },
    )).toThrow('未注册的看板详情视图');
  });

  it('keeps pagination available for an empty current page with remaining records', () => {
    expect(shouldShowAnalyticsDetailPagination({
      dashboardKey: 'quality-rd',
      viewKey: 'assignee-remaining-defects',
      title: '剩余缺陷',
      columns: [],
      records: [],
      total: 41,
      page: 3,
      size: 20,
      exports: [],
      filters: [],
    })).toBe(true);
  });

  it('formats controlled detail values and never activates unsafe links or html', () => {
    expect(formatAnalyticsDetailValue(0.125, {
      key: 'rate', label: '修复率', format: 'percent', width: 120,
    })).toBe('12.5%');
    expect(safeAnalyticsDetailLink({ label: '危险地址', href: 'javascript:alert(1)' })).toBeNull();

    const wrapper = mount(AnalyticsDashboardDetailCell, {
      props: {
        column: { key: 'title', label: '标题', format: 'text', width: 180 },
        value: '<img src=x onerror=alert(1)>',
      },
    });
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>');
  });
});
