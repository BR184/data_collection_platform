import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { CodeReviewRuleConfig, CodeReviewRuleFieldDefinition } from '../../types/code-review-rule-config';
import CodeReviewRuleConfigEditor from './CodeReviewRuleConfigEditor.vue';

const fields: CodeReviewRuleFieldDefinition[] = [
  {
    key: 'moduleName',
    label: '缺少模块名',
    type: 'text',
    operators: ['isEmpty'],
  },
  {
    key: 'owner',
    label: '缺少被走查人',
    type: 'text',
    operators: ['isEmpty'],
  },
];

const modelValue: CodeReviewRuleConfig = {
  enabled: false,
  updatedAt: null,
  groups: [
    {
      id: 'group-1',
      matchMode: 'any',
      conditions: [
        {
          id: 'condition-owner',
          fieldKey: 'owner',
          operator: 'isEmpty',
          value: '',
        },
      ],
    },
  ],
};

describe('CodeReviewRuleConfigEditor', () => {
  it('places a newly added rule at the top of the active list', async () => {
    const wrapper = mount(CodeReviewRuleConfigEditor, {
      props: {
        modelValue,
        fields,
      },
      global: {
        stubs: {
          SmartSelect: true,
          ElButton: {
            emits: ['click'],
            template: '<button type="button" @click="$emit(\'click\')"><slot /></button>',
          },
          ElEmpty: true,
          ElInput: true,
          ElPopover: {
            template: '<div><slot name="reference" /><slot /></div>',
          },
          ElTag: {
            template: '<span><slot /></span>',
          },
        },
      },
    });

    await wrapper.find('.rule-add-item').trigger('click');

    const emittedConfig = wrapper.emitted('update:modelValue')?.[0]?.[0] as CodeReviewRuleConfig;
    expect(emittedConfig.groups[0].conditions.map((condition) => condition.fieldKey)).toEqual([
      'moduleName',
      'owner',
    ]);
  });
});
