import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { BiPageResponse, BiReviewPageData } from '../data/types';
import ReviewStageContent from './ReviewStageContent.vue';

function mockReviewResponse(): BiPageResponse<BiReviewPageData> {
  return {
    pageKey: 'requirements',
    status: 'READY',
    sourceVersion: 'review-v1',
    snapshotId: 'review-v1',
    ruleVersion: 'bi-review-v3',
    generatedAt: '2026-08-05T00:00:00Z',
    sections: [
      { key: 'problem-categories', label: '评审问题类别分布', status: 'READY', message: '' },
      { key: 'module-quality', label: '各模块需求评审质量', status: 'READY', message: '' },
      { key: 'review-scatter', label: '每次需求评审质量分布', status: 'READY', message: '' },
    ],
    traces: [],
    data: {
      summary: {
        reviewCount: 2,
        effectiveProblemCount: 5,
        reviewedPages: 10,
        workloadHours: 5,
        defectDensity: 0.5,
        reviewRate: 2.0,
        achieved: true,
      },
      categories: [
        { category: '功能性', count: 3, sharePercent: 60 },
        { category: '可行性', count: 2, sharePercent: 40 },
      ],
      modules: [
        {
          module: { sourceValue: '草图', displayName: '草图', identified: true },
          reviewedPages: 10,
          effectiveProblemCount: 5,
          workloadHours: 5,
          defectDensity: 0.5,
          reviewRate: 2.0,
          achieved: true,
        },
      ],
      reviewPoints: [],
      moduleCoverage: { totalObservations: 1, validObservations: 1, coveragePercent: 100 },
      reviewPointCoverage: { totalObservations: 0, validObservations: 0, coveragePercent: 100 },
    },
  };
}

describe('ReviewStageContent height alignment', () => {
  it('aligns problem categories donut chart height with module quality chart at 430px', () => {
    const response = mockReviewResponse();
    const wrapper = shallowMount(ReviewStageContent, {
      props: {
        response,
        productVersionId: 11,
        productVersionName: 'v1.0',
        stageLabel: '需求',
        pageKey: 'requirements',
      },
    });

    const panels = wrapper.findAllComponents({ name: 'BiChartPanel' });
    const categoryPanel = panels.find((p) => p.props('title') === '需求评审问题类别分布');
    const modulePanel = panels.find((p) => p.props('title') === '各模块需求评审质量');

    expect(categoryPanel).toBeDefined();
    expect(modulePanel).toBeDefined();

    // Both top row panels must have identical height of 430px
    expect(categoryPanel?.props('height')).toBe(430);
    expect(modulePanel?.props('height')).toBe(430);
  });
});
