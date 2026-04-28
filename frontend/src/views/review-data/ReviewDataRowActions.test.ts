import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReviewDataRowActions from './ReviewDataRowActions.vue';

describe('ReviewDataRowActions', () => {
  it('forwards all row action clicks', async () => {
    const row = { id: 3, title: 'review-3' };
    const onToggleProblemPanel = vi.fn();
    const onOpenDetail = vi.fn();
    const onEditRecord = vi.fn();
    const onCreateProblemItem = vi.fn();
    const onDeleteRecord = vi.fn();

    const wrapper = mount(ReviewDataRowActions, {
      global: { plugins: [ElementPlus] },
      props: {
        row,
        expanded: false,
        onToggleProblemPanel,
        onOpenDetail,
        onEditRecord,
        onCreateProblemItem,
        onDeleteRecord,
      },
    });

    const buttons = wrapper.findAll('button');
    await buttons[0]?.trigger('click');
    await buttons[1]?.trigger('click');
    expect(onToggleProblemPanel).toHaveBeenCalledWith(row);
    expect(onOpenDetail).toHaveBeenCalledWith(row);

    await wrapper.find('.record-actions-more').trigger('click');
    const items = document.body.querySelectorAll('.el-dropdown-menu__item');
    (items[0] as HTMLElement)?.click();
    (items[1] as HTMLElement)?.click();
    (items[2] as HTMLElement)?.click();

    expect(onEditRecord).toHaveBeenCalledWith(row);
    expect(onCreateProblemItem).toHaveBeenCalledWith(row);
    expect(onDeleteRecord).toHaveBeenCalledWith(row);
  });
});
