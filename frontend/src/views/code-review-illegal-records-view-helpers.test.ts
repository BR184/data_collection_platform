import { describe, expect, it } from 'vitest';
import {
  CODE_REVIEW_ILLEGAL_RECORD_COLUMNS,
  buildCodeReviewRuleExplanationOverview,
  createCodeReviewConditionFields,
  createCodeReviewRuleExplanationFallback,
  createDefaultCodeReviewFilterOptions,
  formatCodeReviewDateTime,
  formatCodeReviewPercent,
  mapCodeReviewIllegalTableRows,
} from './code-review-illegal-records-view-helpers';

describe('code review illegal records view helpers', () => {
  it('builds condition fields from filter options', () => {
    const fields = createCodeReviewConditionFields(createDefaultCodeReviewFilterOptions());

    expect(fields).toHaveLength(14);
    expect(fields[0]).toMatchObject({ key: 'repositoryName', type: 'select' });
    expect(fields[3]).toMatchObject({ key: 'keyword', type: 'text', width: 240 });
    expect(fields.at(-1)).toMatchObject({ key: 'addedLines', type: 'number' });
  });

  it('formats rows for BaseRecordTable consumption', () => {
    const rows = mapCodeReviewIllegalTableRows([
      {
        requestType: 'merge_request',
        sourceInstance: 'cc',
        mergeRequestId: 1,
        mergeRequestIid: 101,
        projectId: 2001,
        mergeRequestContent: 'demo',
        mergeRequestLink: 'http://gitlab/mr/101',
        owner: '王老师',
        projectName: '项目 A',
        repositoryName: 'repo-a',
        mergedAt: '2026-04-24T10:20:30',
        author: 'Bob',
        mergedBy: 'Alice',
        moduleName: '支付模块',
        targetBranch: 'master',
        illegalTypes: ['未标注模块名'],
        reviewerNames: '王老师',
        assigneeNames: '李老师',
        reviewStatus: 'COMPLETED',
        reviewDurationMinutes: 30,
        reviewExceptionReason: '',
        scanStatus: '',
        scanBugCount: 0,
        annotationRateResult: '',
        bugCountResult: '',
        commentRate: 12.345,
        defectCount: 2,
        addedLines: 100,
        deletedLines: 8,
        codeSpecificationCount: 1,
        codeLogicSpecificationCount: 1,
        performanceSpecificationCount: 0,
        designSpecificationCount: 0,
        otherSpecificationCount: 0,
        reviewSpeedLocPerHour: 200,
        reviewSpeedKlocPerHour: 0.2,
        defectDensityPerKloc: 20,
        reviewEfficiencyPerHour: 4,
        commitCount: 2,
        commitRate: 50,
        functionName: '支付',
        clangAddedLineCount: 100,
      },
    ]);

    expect(rows[0]).toMatchObject({
      mergeRequestIid: { label: '101', href: 'http://gitlab/mr/101' },
      mergeRequestContent: 'demo',
      author: 'Bob',
      mergedAt: '2026-04-24 10:20:30',
      commentRate: '12.35%',
      illegalTypes: [{ label: '未标注模块名', type: 'warning' }],
    });
  });

  it('provides stable fallback explanation and basic formatters', () => {
    expect(createCodeReviewRuleExplanationFallback('加载失败')).toMatchObject({
      boardKey: 'code-review-illegal-records',
      supported: false,
      unsupportedReason: '加载失败',
    });
    expect(formatCodeReviewDateTime('2026-04-24T10:20:30')).toBe('2026-04-24 10:20:30');
    expect(formatCodeReviewPercent(0.5)).toBe('0.50%');
    expect(CODE_REVIEW_ILLEGAL_RECORD_COLUMNS[0].key).toBe('mergeRequestIid');
  });

  it('builds rule explanation overview with illegal-total as final output', () => {
    const overview = buildCodeReviewRuleExplanationOverview({
      supported: true,
      summary: 'ignored',
      flowSteps: [
        { key: 'source', title: 'source', description: '', inputCount: 100, outputCount: 90, samples: [] },
        { key: 'illegal-total', title: 'illegal', description: '', inputCount: 90, outputCount: 12, samples: [] },
        { key: 'appendix', title: 'appendix', description: '', inputCount: 12, outputCount: 8, samples: [] },
      ],
    });

    expect(overview.firstInputCount).toBe(100);
    expect(overview.finalOutputCount).toBe(12);
    expect(overview.finalRetainedRate).toBe('12.0%');
    expect(overview.summary).toContain('100 条合并请求');
    expect(overview.summary).toContain('12 条需要关注的记录');
  });
});
