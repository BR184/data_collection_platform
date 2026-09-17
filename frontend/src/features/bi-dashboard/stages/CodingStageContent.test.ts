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
    ruleVersion: 'bi-coding-v5',
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
      moduleReviewQuality: [],
      reviewPoints: [],
      commentRateTrend: [],
      reviewDensityTrend: [],
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
    const scanPanel = panels.find((p) => p.props('title') === '静态代码扫描结果');
    const reviewQualityPanel = panels.find((p) => p.props('title') === '各模块人工代码走查质量');

    expect(codeTrendPanel).toBeDefined();
    expect(submissionTrendPanel).toBeDefined();
    // Frequency chart must be removed
    expect(frequencyPanel).toBeUndefined();
    // Static scan chart must be removed (D-12)
    expect(scanPanel).toBeUndefined();

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

  it('renders quality trend from backend period averages with coverage disclosure', () => {
    const response = mockCodingResponse();
    // 后端已按请求粒度聚合，页面只承载“周期 → 值”，不再按同日记录选择最后一条。
    response.data!.commentRateTrend = [
      { period: '2026-08-01', averageCommentRate: 12.5 },
      { period: '2026-08-02', averageCommentRate: 13.5 },
    ];
    // 后端覆盖率语义：存在坏行时密度趋势仅返回合法子集，不再整条置空。
    response.data!.reviewDensityTrend = [
      { period: '2026-08-01', reviewDefectDensity: 4 },
    ];
    response.sections = [
      ...response.sections,
      {
        key: 'quality-trend',
        label: '代码质量趋势',
        status: 'INCOMPLETE',
        message: '注释率有效走查记录 2/2；密度有效走查记录 1/2；其余记录缺少合法注释率或有效密度输入',
      },
    ];

    const wrapper = shallowMount(CodingStageContent, {
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
    });

    const panel = wrapper
      .findAllComponents({ name: 'BiChartPanel' })
      .find((p) => p.props('title') === '代码注释率与走查密度趋势');
    expect(panel).toBeDefined();
    expect(panel?.props('data')).toEqual({
      periods: ['2026-08-01', '2026-08-02'],
      commentRates: [12.5, 13.5],
      defectDensities: [4, null],
    });
    expect(panel?.props('status')).toBe('INCOMPLETE');
    expect(panel?.props('statusMessage')).toContain('注释率有效走查记录 2/2');
    expect(panel?.props('statusMessage')).toContain('密度有效走查记录 1/2');
  });

  it('aligns weekly backend periods without re-aggregating or rewriting backend averages', () => {
    const response = mockCodingResponse();
    response.data!.commentRateTrend = [
      { period: '2026-08-17', averageCommentRate: 15 },
      { period: '2026-08-24', averageCommentRate: 60 },
    ];
    response.data!.reviewDensityTrend = [
      { period: '2026-08-17', reviewDefectDensity: 4 },
      { period: '2026-08-24', reviewDefectDensity: 5 },
    ];

    const wrapper = shallowMount(CodingStageContent, {
      props: { response, productVersionId: 11, productVersionName: 'v1.0' },
    });

    const panel = wrapper
      .findAllComponents({ name: 'BiChartPanel' })
      .find((p) => p.props('title') === '代码注释率与走查密度趋势');
    // 每个周期只有一个后端值：页面只做周期轴并集与查找，原样透传后端平均值。
    expect(panel?.props('data')).toEqual({
      periods: ['2026-08-17', '2026-08-24'],
      commentRates: [15, 60],
      defectDensities: [4, 5],
    });
  });
});
