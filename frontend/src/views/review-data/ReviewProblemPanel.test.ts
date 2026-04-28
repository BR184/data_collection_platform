import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReviewProblemPanel from './ReviewProblemPanel.vue';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import type { RecordTableColumn } from '../../types/record-table';

function record(): ReviewDataRecordRowResponse {
  return {
    id: 3,
    projectName: 'project',
    title: 'Architecture review',
    moduleName: 'platform',
    reviewType: 'design',
    reviewDate: '2026-04-27',
    reviewOwner: 'owner',
    reviewExpertsSummary: 'Ada, Grace',
    reviewScalePages: 20,
    reviewProduct: 'design doc',
    authorName: 'author',
    reviewVersion: 'v1',
    problemCount: 2,
    problemDensity: 0.1,
    updatedAt: '2026-04-27T10:00:00',
    deleted: false,
  };
}

function rawProblemItem(id: number): ReviewDataProblemItemResponse {
  return {
    id,
    reviewRecordId: 3,
    reviewerName: 'Ada',
    workloadHours: 1.5,
    reviewCategory: 'design',
    documentPosition: '2.1',
    problemCategory: 'logic',
    problemDescription: `problem-${id}`,
    suggestedSolution: 'solution',
    ownerName: 'owner',
    rejectionReason: '',
    problemStatus: 'new',
    updatedAt: '2026-04-27T10:00:00',
  };
}

const columns: RecordTableColumn[] = [
  { key: 'problemDescription', label: '问题描述', minWidth: 220 },
  { key: 'problemStatus', label: '问题状态', type: 'tag', width: 110 },
];

describe('ReviewProblemPanel', () => {
  it('renders problem rows and forwards actions', async () => {
    const onCreateProblemItem = vi.fn();
    const onEditProblemItem = vi.fn();
    const onDeleteProblemItem = vi.fn();

    const wrapper = mount(ReviewProblemPanel, {
      global: { plugins: [ElementPlus] },
      props: {
        record: record(),
        loading: false,
        rows: [
          {
            __raw: rawProblemItem(9),
            problemDescription: 'problem-9',
            problemStatus: [{ label: 'new', type: 'info' }],
          },
        ],
        columns,
        onCreateProblemItem,
        onEditProblemItem,
        onDeleteProblemItem,
      },
    });

    expect(wrapper.text()).toContain('评审问题清单');
    expect(wrapper.findComponent({ name: 'ElTable' }).exists()).toBe(true);
    expect(wrapper.findAllComponents({ name: 'ElTableColumn' }).length).toBeGreaterThan(0);

    await wrapper.findAll('button')[0]?.trigger('click');
    expect(onCreateProblemItem).toHaveBeenCalledWith(3);

    const buttons = wrapper.findAll('button');
    await buttons[1]?.trigger('click');
    expect(onEditProblemItem).toHaveBeenCalled();

    await buttons[2]?.trigger('click');
    expect(onDeleteProblemItem).toHaveBeenCalledWith(3, 9);
  });
});
