import { shallowMount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import type { BiCodingPageData, BiPageResponse } from '../data/types';
import CodingStageContent from './CodingStageContent.vue';

function mockCodingResponse(): BiPageResponse<BiCodingPageData> {
  return {
    pageKey: 'coding',
    status: 'READY',
    sourceVersion: 'coding-v1',
    snapshotId: 'coding-v1',
    ruleVersion: 'bi-coding-v3',
    generatedAt: '2026-08-05T00:00:00Z',
    sections: [
      { key: 'code-trend', label: '代码增加趋势', status: 'READY', message: '' },
      { key: 'submission-trend', label: '提交趋势', status: 'READY', message: '' },
    ],
    traces: [],
    data: {
      summary: {
        addedLines: 3000,
        addedKloc: 3.0,
        mergeRequestCount: 5,
        contributorCount: 2,
        reviewDefectDensity: 4.5,
        reviewSpeedLocPerHour: 500,
        reviewDensityAchieved: true,
      },
      codeTrend: [
        { period: '2026-08-01', addedLines: 1000, cumulativeLines: 1000 },
        { period: '2026-08-02', addedLines: 1000, cumulativeLines: 2000 },
        { period: '2026-08-03', addedLines: 1000, cumulativeLines: 3000 },
      ],
      submissionTrend: [
        { period: '2026-08-01', commitCount: 5, mergeRequestCount: 2 },
        { period: '2026-08-02', commitCount: 3, mergeRequestCount: 1 },
        { period: '2026-08-03', commitCount: 8, mergeRequestCount: 2 },
      ],
      reviewCategories: [],
      contributors: [],
      moduleIncrements: [],
      scanTrend: [],
      moduleReviewQuality: [],
      reviewPoints: [],
      commentRatePoints: [],
      reviewDensityTrend: [],
      scanCoverage: { totalObservations: 0, validObservations: 0, coveragePercent: 100 },
      commentRateCoverage: { totalObservations: 0, validObservations: 0, coveragePercent: 100 },
      reviewDensityCoverage: { totalObservations: 0, validObservations: 0, coveragePercent: 100 },
    },
  };
}

describe('CodingStageContent', () => {
  it('renders code trend and submission trend with default daily data and descriptions, without removed frequency chart', () => {
    const response = mockCodingResponse();
    response.data!.moduleIncrements = [
      { module: { sourceValue: 'M1', displayName: '模块1', identified: true }, addedLines: 2500 },
    ];
    response.data!.moduleReviewQuality = [
      { module: { sourceValue: 'M1', displayName: '模块1', identified: true }, defectDensity: 3.2, reviewSpeedLocPerHour: 450, achieved: true },
    ];

    const wrapper = shallowMount(CodingStageContent, {
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
    });

    const panels = wrapper.findAllComponents({ name: 'BiChartPanel' });
    const codeTrendPanel = panels.find((p) => p.props('title') === '代码增量趋势');
    const submissionTrendPanel = panels.find((p) => p.props('title') === '提交趋势');
    const frequencyPanel = panels.find((p) => p.props('title') === '代码提交频次时间分布');
    const reviewQualityPanel = panels.find((p) => p.props('title') === '各模块人工代码走查质量');

    expect(codeTrendPanel).toBeDefined();
    expect(submissionTrendPanel).toBeDefined();
    // Frequency chart must be removed
    expect(frequencyPanel).toBeUndefined();

    // Verify descriptions
    expect(codeTrendPanel?.props('description')).toContain('展示按日或按周的新增代码量');
    expect(submissionTrendPanel?.props('description')).toContain('展示按日或按周的代码提交次数');
    expect(reviewQualityPanel?.props('description')).toContain('统计各模块人工代码走查缺陷密度');

    // Default is day
    expect(codeTrendPanel?.props('data')).toEqual({
      periods: ['2026-08-01', '2026-08-02', '2026-08-03'],
      addedLines: [1000, 1000, 1000],
      cumulativeLines: [1000, 2000, 3000],
    });

    expect(submissionTrendPanel?.props('data')).toEqual({
      periods: ['2026-08-01', '2026-08-02', '2026-08-03'],
      commits: [5, 3, 8],
      mergeRequests: [2, 1, 2],
    });

    // Review quality data includes mapped addedLines
    expect(reviewQualityPanel?.props('data')).toEqual([
      {
        name: '模块1',
        density: 3.2,
        rate: 450,
        achieved: true,
        addedLines: 2500,
      },
    ]);
  });
});
