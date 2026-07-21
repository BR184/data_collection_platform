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
  { key: 'problemDescription', label: '问题描述', width: 300 },
  { key: 'problemStatus', label: '问题状态', type: 'tag', width: 110 },
  { key: 'updatedAt', label: '更新日期', type: 'datetime', width: 160, fixed: 'right' },
];

describe('ReviewProblemPanel', () => {
  it('renders problem rows and forwards actions', async () => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    });
    const onCreateProblemItem = vi.fn();
    const onEditProblemItem = vi.fn();
    const onDeleteProblemItem = vi.fn();
    const longDescription = '描述'.repeat(80);

    const wrapper = mount(ReviewProblemPanel, {
      global: { plugins: [ElementPlus] },
      props: {
        record: record(),
        loading: false,
        rows: [
          {
            __raw: rawProblemItem(9),
            problemDescription: longDescription,
            problemStatus: [{ label: 'new', type: 'info' }],
            updatedAt: '2026-04-27 10:00:00',
          },
        ],
        columns,
        onCreateProblemItem,
        onEditProblemItem,
        onDeleteProblemItem,
        canCreate: true,
        canEdit: true,
        canDelete: true,
      },
    });

    expect(wrapper.text()).toContain('评审问题清单');
    const table = wrapper.findComponent({ name: 'ElTable' });
    expect(table.exists()).toBe(true);
    const tableColumns = wrapper.findAllComponents({ name: 'ElTableColumn' });
    expect(tableColumns.length).toBeGreaterThan(0);
    const updatedAtColumn = tableColumns
      .find((column) => column.props('label') === '更新日期');
    const descriptionColumn = tableColumns
      .find((column) => column.props('label') === '问题描述');
    expect(Number(updatedAtColumn?.props('width'))).toBe(160);
    expect(updatedAtColumn?.props('fixed')).toBe('right');
    expect(Number(descriptionColumn?.props('width'))).toBe(300);
    expect(descriptionColumn?.props('showOverflowTooltip')).toBe(true);
    expect(tableColumns.find((column) => column.props('label') === '问题状态')
      ?.props('showOverflowTooltip')).toBe(false);
    expect(document.body.querySelector('.review-problem-floating-horizontal')).not.toBeNull();
    const renderedRows = table.props('data') as Array<Record<string, unknown>>;
    expect(renderedRows[0]?.problemDescription).toBe(longDescription);
    expect(renderedRows[0]?.updatedAt)
      .toBe('2026-04-27 10:00:00');

    await wrapper.findAll('button')[0]?.trigger('click');
    expect(onCreateProblemItem).toHaveBeenCalledWith(3);

    const buttons = wrapper.findAll('button');
    await buttons[1]?.trigger('click');
    expect(onEditProblemItem).toHaveBeenCalled();

    await buttons[2]?.trigger('click');
    expect(onDeleteProblemItem).toHaveBeenCalledWith(3, 9);
    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
