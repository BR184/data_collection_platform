import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReviewDataDetailDrawer from './ReviewDataDetailDrawer.vue';
import type { ReviewDataRecordDetailResponse } from '../../types/api';

const detailData: ReviewDataRecordDetailResponse = {
  record: {
    id: 1,
    projectName: 'CrownCAD',
    title: '测试用例评审文档',
    moduleName: '工具',
    reviewType: '测试用例评审',
    reviewDate: '2026-09-18',
    reviewOwner: '负责人',
    reviewExpertsSummary: '专家',
    reviewScalePages: 10,
    reviewProduct: '测试用例评审文档',
    authorName: '作者',
    reviewVersion: 'V1',
    problemCount: 3,
    problemDensity: 0.3,
    docSpecificationCount: 1,
    integrityCount: 1,
    functionalityCount: 1,
    feasibilityCount: 0,
    independentReviewWorkload: 1,
    independentReviewProblemCount: 2,
    meetingReviewWorkload: 0,
    meetingReviewProblemCount: 1,
    reachStandard: true,
    deleted: false,
  },
  reviewExperts: ['专家'],
  problemItems: [],
  descriptions: [],
  contents: [],
};

describe('ReviewDataDetailDrawer', () => {
  it('uses problem labels for the three review problem categories only', async () => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    });

    const wrapper = mount(ReviewDataDetailDrawer, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
      props: { visible: true, detailData },
    });

    await wrapper.vm.$nextTick();
    const text = document.body.textContent ?? '';
    expect(text).toContain('文档规范');
    expect(text).toContain('完整性问题');
    expect(text).toContain('功能性问题');
    expect(text).toContain('可行性问题');
    expect(text).not.toContain('完整性规范');
    expect(text).not.toContain('功能性规范');
    expect(text).not.toContain('可行性规范');

    wrapper.unmount();
    vi.unstubAllGlobals();
  });
});
