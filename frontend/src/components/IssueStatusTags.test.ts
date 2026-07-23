import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import IssueStatusTags from './IssueStatusTags.vue';

function mountTags(value: string) {
  return mount(IssueStatusTags, {
    props: { value },
    global: {
      stubs: {
        ElTag: {
          template: '<span><slot /></span>',
        },
      },
    },
  });
}

describe('IssueStatusTags', () => {
  it('test_combinedStatus_render_rendersIndependentTags', () => {
    const wrapper = mountTags('历史遗留、申请延期');

    expect(wrapper.findAll('.issue-status-tag')).toHaveLength(2);
    expect(wrapper.text()).toContain('历史遗留');
    expect(wrapper.text()).toContain('申请延期');
  });

  it('test_resolvedStatus_render_keepsSlashStatusAsOneTag', () => {
    const wrapper = mountTags('已修复/完成');

    expect(wrapper.findAll('.issue-status-tag')).toHaveLength(1);
    expect(wrapper.text()).toContain('已修复/完成');
  });
});
