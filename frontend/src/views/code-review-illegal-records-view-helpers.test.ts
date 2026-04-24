import { describe, expect, it } from 'vitest';
import {
  CODE_REVIEW_ILLEGAL_RECORD_COLUMNS,
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
        mergeRequestId: 1,
        mergeRequestIid: 101,
        projectId: 2001,
        mergeRequestContent: 'demo',
        mergeRequestLink: 'http://gitlab/mr/101',
        owner: '王老师',
        projectName: '项目 A',
        repositoryName: 'repo-a',
        mergedAt: '2026-04-24T10:20:30',
        mergedBy: 'Alice',
        moduleName: '支付模块',
        targetBranch: 'master',
        illegalTypes: ['缺少模块标签'],
        commentRate: 12.345,
        defectCount: 2,
        addedLines: 100,
      },
    ]);

    expect(rows[0]).toMatchObject({
      mergeRequestContent: 'demo',
      mergedAt: '2026-04-24 10:20:30',
      commentRate: '12.35%',
      illegalTypes: [{ label: '缺少模块标签', type: 'warning' }],
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
});
