import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

vi.mock('../api-client/label-groups-api', () => ({
  labelGroupsApi: { listLabelGroups: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../element-plus-services', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) },
}));

import StatisticFilterBuilder from './StatisticFilterBuilder.vue';
import type { StatisticFilterDraftGroup } from './statistic-board-filters';
import type { StatisticFilterField } from '../types/api';

const SmartSelectStub = defineComponent({
  name: 'SmartSelectStub',
  props: { modelValue: { type: [String, Array], default: '' }, options: { type: Array, default: () => [] } },
  template: '<div class="smart-select-stub" />',
});

const ElSegmentedStub = defineComponent({
  name: 'ElSegmentedStub',
  props: { modelValue: String },
  template: '<div />',
});

const stubs = {
  SmartSelect: SmartSelectStub,
  'el-segmented': ElSegmentedStub,
  'el-button': true,
  'el-checkbox': true,
  'el-tag': true,
  'el-input': true,
  'el-input-number': true,
  'el-date-picker': true,
};

const TEXT_FIELD: StatisticFilterField = {
  key: 'projectName',
  label: '项目名称',
  type: 'text',
  operators: ['eq', 'ne', 'contains', 'notContains'],
  options: [],
};

function draftGroup(conditionCount: number): StatisticFilterDraftGroup {
  return {
    logic: 'AND',
    conditions: Array.from({ length: conditionCount }, (_value, index) => ({
      id: `condition-${index}`,
      fieldKey: TEXT_FIELD.key,
      operator: 'eq',
      value: 'X',
      secondaryValue: '',
      valueType: 'LITERAL' as const,
      labelGroupId: null,
      labelGroupName: null,
      source: 'CONDITION' as const,
    })),
  };
}

function mountBuilder(props: Record<string, unknown> = {}, conditionCount = 1) {
  return mount(StatisticFilterBuilder, {
    props: { modelValue: draftGroup(conditionCount), fields: [TEXT_FIELD], expanded: true, ...props },
    global: { stubs },
  });
}

function operatorLabels(view: ReturnType<typeof mountBuilder>) {
  const operatorSelect = view
    .findAllComponents(SmartSelectStub)
    .find((select) => select.classes().includes('stat-filter-operator'))!;
  return (operatorSelect.props('options') as Array<{ label: string }>).map((option) => option.label);
}

describe('StatisticFilterBuilder defaults', () => {
  it('keeps every literal and label-group relation when no customization is passed', () => {
    const view = mountBuilder();

    expect(operatorLabels(view)).toEqual([
      '等于',
      '不等于',
      '包含',
      '不包含',
      '包含任意一个',
      '不包含任意一个',
      '包含全部',
      '不包含全部',
      '局部包含任意',
    ]);
    expect(view.findComponent(ElSegmentedStub).exists()).toBe(true);
    view.unmount();
  });
});

describe('StatisticFilterBuilder customization', () => {
  const customization = {
    labelGroupOperatorOptions: ['intersects', 'notIntersects', 'partialContainsAny'],
    operatorLabels: {
      intersects: '属于该组',
      notIntersects: '不属于该组',
      partialContainsAny: '包含组内任一成员',
    },
    hideLogicSelectorWhenSingleCondition: true,
  };

  it('replaces label-group relations and their labels without touching literal relations', () => {
    const view = mountBuilder(customization);

    expect(operatorLabels(view)).toEqual([
      '等于',
      '不等于',
      '包含',
      '不包含',
      '属于该组',
      '不属于该组',
      '包含组内任一成员',
    ]);
    view.unmount();
  });

  it('hides the logic selector only while at most one condition is set', () => {
    const single = mountBuilder(customization, 1);
    expect(single.findComponent(ElSegmentedStub).exists()).toBe(false);
    expect(single.text()).not.toContain('满足全部');
    single.unmount();

    const multiple = mountBuilder(customization, 2);
    expect(multiple.findComponent(ElSegmentedStub).exists()).toBe(true);
    multiple.unmount();
  });
});
