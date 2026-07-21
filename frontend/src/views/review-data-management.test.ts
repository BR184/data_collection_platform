import { describe, expect, it } from 'vitest';
import type {
  ReviewDataFilterOptionsResponse,
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
} from '../types/api';
import {
  buildProblemItemTableRows,
  buildReviewDataFilterFields,
  buildReviewDataMetricFilterFields,
  buildReviewDataExportCsv,
  buildReviewDataSummaryCards,
  buildReviewDataTableRows,
  createEmptyProblemItemForm,
  createEmptyReviewRecordForm,
  reviewDataColumns,
  reviewProblemItemColumns,
} from './review-data-management';

describe('review-data-management helpers', () => {
  it('should build summary cards from summary payload', () => {
    const cards = buildReviewDataSummaryCards({
      totalRecords: 12,
      totalProblemItems: 36,
      averageReviewScalePages: 24.5,
      averageProblemCount: 3.0,
    });

    expect(cards[0].value).toBe('12');
    expect(cards[1].value).toBe('36');
    expect(cards[2].value).toBe('24.5');
    expect(cards[3].value).toBe('3.0');
  });

  it('should map review data rows to record table rows', () => {
    const rows: ReviewDataRecordRowResponse[] = [
      {
        id: 1,
        projectName: 'CrownCAD',
        title: '草图功能设计说明书评审',
        moduleName: '草图',
        reviewType: '设计说明书评审',
        reviewDate: '2026-04-10',
        reviewOwner: '王青',
        reviewExpertsSummary: '张三、李四',
        reviewScalePages: 24,
        reviewProduct: '设计说明书',
        authorName: '路士坤',
        reviewVersion: 'V1.0',
        problemCount: 5,
        problemDensity: 0.2083,
        reviewEfficiency: 2.5,
        reviewRate: 12,
        independentReviewWorkload: 1.2,
        independentReviewProblemCount: 3,
        meetingReviewWorkload: 0.8,
        meetingReviewProblemCount: 2,
        notReachStandardReason: '样本不足',
        reachStandard: true,
        updatedAt: '2026-04-12T10:00:00',
        deleted: false,
      },
    ];

    const tableRows = buildReviewDataTableRows(rows);
    expect(tableRows[0].title).toBe('草图功能设计说明书评审');
    expect(tableRows[0].problemDensity).toBe('0.21');
    expect(tableRows[0].reviewEfficiency).toBe('2.50');
    expect(tableRows[0].reviewRate).toBe('12.00');
    expect(tableRows[0].independentReviewWorkload).toBe('1.20');
    expect(tableRows[0].independentReviewProblemCount).toBe(3);
    expect(tableRows[0].meetingReviewWorkload).toBe('0.80');
    expect(tableRows[0].meetingReviewProblemCount).toBe(2);
    expect(tableRows[0].notReachStandardReason).toBe('样本不足');
    expect((tableRows[0].reachStandard as Array<{ label: string }>)[0].label).toBe('是');
    expect(tableRows[0].reviewDate).toBe('2026-04-10');
    expect(tableRows[0].updatedAt).toBe('2026-04-12 10:00:00');
  });

  it('should keep title fixed on the left and reach-standard fixed on the right', () => {
    const columns = reviewDataColumns();

    expect(columns[0]).toMatchObject({ key: 'title', fixed: 'left' });
    expect(columns.find((column) => column.key === 'reachStandard'))
      .toMatchObject({ fixed: 'right' });
  });

  it('should export review data rows as Excel friendly csv', () => {
    const rows: ReviewDataRecordRowResponse[] = [
      {
        id: 1,
        projectName: 'CrownCAD',
        title: '=风险标题',
        moduleName: '草图',
        reviewType: '会议评审',
        reviewDate: '2026-04-10',
        reviewOwner: '王强',
        reviewExpertsSummary: '张晓涵、崔雪峰',
        reviewScalePages: 24,
        reviewProduct: '设计说明书',
        authorName: '路士坤',
        reviewVersion: 'V1.0',
        problemCount: 5,
        problemDensity: 0.2083,
        reviewEfficiency: 2.5,
        reviewRate: 12,
        independentReviewWorkload: 1.2,
        independentReviewProblemCount: 3,
        meetingReviewWorkload: 0.8,
        meetingReviewProblemCount: 2,
        notReachStandardReason: '样本不足',
        reachStandard: false,
        updatedAt: '2026-04-12T10:00:00',
        deleted: false,
      },
    ];

    const csv = buildReviewDataExportCsv(rows);
    expect(csv).toContain('"标题","项目","模块"');
    expect(csv).toContain('"\'=风险标题"');
    expect(csv).toContain('"0.21"');
    expect(csv).toContain('"2.50"');
    expect(csv).toContain('"样本不足"');
    expect(csv).toContain('"否"');
    expect(csv).toContain('"有效"');
  });

  it('should map problem item rows to child table rows', () => {
    const rows: ReviewDataProblemItemResponse[] = [
      {
        id: 9,
        reviewRecordId: 1,
        reviewerName: '张三',
        workloadHours: 0.8,
        reviewCategory: '会议评审',
        documentPosition: '3.3.2',
        problemCategory: '文档规范',
        problemDescription: '命名不规范',
        suggestedSolution: '统一命名',
        ownerName: '路士坤',
        rejectionReason: '',
        problemStatus: '已修复',
        updatedAt: '2026-04-17T11:00:00',
      },
    ];

    const tableRows = buildProblemItemTableRows(rows);
    expect(tableRows[0].reviewerName).toBe('张三');
    expect(tableRows[0].workloadHours).toBe('0.8');
    expect((tableRows[0].problemStatus as Array<{ label: string }>)[0].label).toBe('已修复');

    const columns = reviewProblemItemColumns();
    expect(columns.find((column) => column.key === 'workloadHours')?.type).toBe('number');
    expect(columns.find((column) => column.key === 'updatedAt')?.type).toBe('datetime');
  });

  it('should create empty form defaults', () => {
    expect(createEmptyReviewRecordForm().reviewExperts).toEqual([]);
    expect(createEmptyReviewRecordForm().notReachStandardReason).toBe('');
    expect(createEmptyProblemItemForm().problemStatus).toBe('');
  });

  it('should build condition filter fields from review data options', () => {
    const filterOptions: ReviewDataFilterOptionsResponse = {
      projectNames: [{ label: 'Project A', value: 'Project A' }],
      moduleNames: [{ label: 'Module A', value: 'Module A' }],
      reviewOwners: [{ label: 'Owner A', value: 'Owner A' }],
      reviewTypes: [{ label: 'Design', value: 'Design' }],
      reviewExperts: [{ label: 'Expert A', value: 'Expert A' }],
      reviewVersions: [{ label: 'V1.0', value: 'V1.0' }],
      problemStatuses: [{ label: 'Open', value: 'Open' }],
      reviewCategories: [],
      problemCategories: [],
    };

    const fields = buildReviewDataFilterFields(filterOptions);

    expect(fields.map((field) => field.key)).toEqual([
      'title',
      'projectName',
      'moduleName',
      'reviewOwner',
      'reviewType',
      'reviewExpert',
      'problemStatus',
      'reviewScalePages',
      'problemCount',
      'problemDensity',
      'reviewEfficiency',
      'reviewRate',
      'independentReviewWorkload',
      'independentReviewProblemCount',
      'meetingReviewWorkload',
      'meetingReviewProblemCount',
      'notReachStandardReason',
      'createdAt',
      'reviewDate',
    ]);
    expect(fields.find((field) => field.key === 'projectName')?.options).toBe(filterOptions.projectNames);
    expect(fields.find((field) => field.key === 'projectName')).toMatchObject({
      labelDimensionKey: 'project',
      labelGroupEnabled: true,
    });
    expect(fields.find((field) => field.key === 'moduleName')).toMatchObject({
      labelDimensionKey: 'module',
      labelGroupEnabled: true,
    });
    expect(fields.find((field) => field.key === 'reviewOwner')).toMatchObject({
      labelDimensionKey: 'review_owner',
      labelGroupEnabled: true,
    });
    expect(fields.find((field) => field.key === 'reviewExpert')).toMatchObject({
      labelDimensionKey: 'review_expert',
      labelGroupEnabled: true,
    });
    expect(fields.find((field) => field.key === 'reviewType')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'reviewDate')?.operators).toContain('between');
  });

  it('should build metric and exception filter fields for the review page', () => {
    const filterOptions: ReviewDataFilterOptionsResponse = {
      projectNames: [{ label: 'Project A', value: 'Project A' }],
      moduleNames: [{ label: 'Module A', value: 'Module A' }],
      reviewOwners: [{ label: 'Owner A', value: 'Owner A' }],
      reviewTypes: [{ label: 'Design', value: 'Design' }],
      reviewExperts: [{ label: 'Expert A', value: 'Expert A' }],
      reviewVersions: [{ label: 'V1.0', value: 'V1.0' }],
      problemStatuses: [{ label: 'Open', value: 'Open' }],
      reviewCategories: [],
      problemCategories: [],
    };

    const fields = buildReviewDataMetricFilterFields(filterOptions);

    expect(fields.map((field) => field.key)).toEqual([
      'title',
      'reviewScalePages',
      'problemCount',
      'problemDensity',
      'reviewEfficiency',
      'reviewRate',
      'independentReviewWorkload',
      'independentReviewProblemCount',
      'meetingReviewWorkload',
      'meetingReviewProblemCount',
      'notReachStandardReason',
      'createdAt',
      'reviewDate',
    ]);
    expect(fields.some((field) => field.key === 'moduleName')).toBe(false);
    expect(fields.some((field) => field.key === 'problemStatus')).toBe(false);
  });
});
