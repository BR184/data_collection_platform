import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReviewProblemItemsDialog from './ReviewProblemItemsDialog.vue';
import type { ReviewDataRecordRowResponse } from '../../types/api';

function record(): ReviewDataRecordRowResponse {
  return {
    id: 3,
    projectName: 'project',
    title: 'Architecture review',
    moduleName: 'platform',
    reviewType: 'design',
    reviewDate: '2026-04-27',
    reviewOwner: 'owner',
    reviewExpertsSummary: 'Ada',
    reviewScalePages: 20,
    reviewProduct: 'design doc',
    authorName: 'author',
    reviewVersion: 'v1',
    problemCount: 0,
    problemDensity: 0,
    updatedAt: '2026-04-27T10:00:00',
    deleted: false,
  };
}

describe('ReviewProblemItemsDialog', () => {
  it('uses a drill-down dialog and closes through the dialog close event', async () => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    });

    const wrapper = mount(ReviewProblemItemsDialog, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
      props: {
        visible: true,
        record: record(),
        loading: false,
        rows: [],
        columns: [],
        onCreateProblemItem: vi.fn(),
        onEditProblemItem: vi.fn(),
        onDeleteProblemItem: vi.fn(),
        canCreate: true,
        canEdit: true,
        canDelete: true,
      },
    });

    await wrapper.vm.$nextTick();
    const dialog = wrapper.findComponent({ name: 'ElDialog' });
    expect(dialog.exists()).toBe(true);
    expect(dialog.props('closeOnClickModal')).toBe(true);
    expect(document.body.textContent).toContain('评审问题清单');
    expect(document.body.textContent).toContain('Architecture review');

    expect(document.body.querySelector('.review-problem-dialog-close')).not.toBeNull();
    dialog.vm.$emit('update:modelValue', false);
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('update:visible')).toEqual([[false]]);
    vi.unstubAllGlobals();
    wrapper.unmount();
  });
});
