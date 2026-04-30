import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import StatisticBoardRuleExplanationDrawer from './StatisticBoardRuleExplanationDrawer.vue';
import type { StatisticBoardRuleExplanationResponse } from '../types/api';

const supportedExplanation: StatisticBoardRuleExplanationResponse = {
  boardKey: 'system-test-defect-summary',
  supported: true,
  title: '系统测试规则说明',
  version: 'rules@1',
  scopeDescription: '只统计系统测试范围内的问题',
  summary: '后端规则摘要',
  flowSteps: [
    { key: 'source', title: '纳入原始数据', description: '读取事实表', inputCount: 10, outputCount: 10, samples: [] },
    { key: 'exclude', title: '排除无效数据', description: '过滤已排除记录', inputCount: 10, outputCount: 6, samples: [] },
  ],
  metricDefinitions: [
    {
      key: 'fixed-rate',
      label: '修复率',
      definition: '已修复 / 总数',
      formula: 'fixed / total',
      note: '按当前筛选条件计算',
    },
  ],
};

function mountDrawer(explanation: StatisticBoardRuleExplanationResponse) {
  return mount(StatisticBoardRuleExplanationDrawer, {
    props: {
      modelValue: true,
      loading: false,
      explanation,
      steps: explanation.flowSteps,
      metrics: explanation.metricDefinitions,
      exclusionSteps: explanation.flowSteps.slice(1),
      firstInputCount: 10,
      finalOutputCount: 6,
      finalRetainedRate: '60.0%',
      qaFriendlySummary: '最终保留 6 条问题。',
    },
    global: {
      stubs: {
        ElDrawer: {
          props: ['modelValue', 'title'],
          emits: ['update:modelValue'],
          template: '<section><h2>{{ title }}</h2><slot /></section>',
        },
        ElEmpty: {
          props: ['description'],
          template: '<div class="empty">{{ description }}</div>',
        },
        ElDescriptions: {
          template: '<dl><slot /></dl>',
        },
        ElDescriptionsItem: {
          props: ['label'],
          template: '<div><dt>{{ label }}</dt><dd><slot /></dd></div>',
        },
      },
    },
  });
}

describe('StatisticBoardRuleExplanationDrawer', () => {
  it('renders supported rule explanation details', () => {
    const wrapper = mountDrawer(supportedExplanation);

    expect(wrapper.text()).toContain('系统测试规则说明');
    expect(wrapper.text()).toContain('最终保留 6 条问题。');
    expect(wrapper.text()).toContain('原始数据');
    expect(wrapper.text()).toContain('10');
    expect(wrapper.text()).toContain('最终保留比例');
    expect(wrapper.text()).toContain('60.0%');
    expect(wrapper.text()).toContain('规则 1：排除无效数据');
    expect(wrapper.text()).toContain('排除 4 条');
    expect(wrapper.text()).toContain('修复率：已修复 / 总数');
    expect(wrapper.text()).toContain('fixed / total');
  });

  it('renders unsupported reason when rule explanation is unavailable', () => {
    const wrapper = mountDrawer({
      boardKey: 'unsupported',
      supported: false,
      flowSteps: [],
      metricDefinitions: [],
      unsupportedReason: '当前统计表暂不支持规则说明',
    });

    expect(wrapper.text()).toContain('当前统计表暂不支持规则说明');
  });
});
