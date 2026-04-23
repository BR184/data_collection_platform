import type { CodeReviewIllegalRecordFilterOptionsResponse } from '../types/api';
import type { CodeReviewRuleFieldDefinition } from '../types/code-review-rule-config';

export function buildCodeReviewRuleFields(
  options: CodeReviewIllegalRecordFilterOptionsResponse,
): CodeReviewRuleFieldDefinition[] {
  return [
    {
      key: 'moduleName',
      label: '缺少模块名',
      type: 'select',
      operators: ['isEmpty'],
    },
    {
      key: 'owner',
      label: '缺少责任人',
      type: 'text',
      operators: ['isEmpty'],
    },
    {
      key: 'reviewRecordMissing',
      label: '缺少代码走查记录',
      type: 'text',
      operators: ['isMissingReview'],
    },
    {
      key: 'scanNotDone',
      label: '未完成代码扫描',
      type: 'text',
      operators: ['isNotScanned'],
    },
    {
      key: 'scanIssueOpen',
      label: '扫描问题未关闭',
      type: 'number',
      operators: ['hasOpenScanIssue'],
    },
    {
      key: 'targetBranch',
      label: '目标分支不合规',
      type: 'multi-select',
      operators: ['notIn'],
      options: options.targetBranches,
    },
    {
      key: 'mergeRequestContent',
      label: '合并内容包含风险词',
      type: 'text',
      operators: ['contains'],
    },
    {
      key: 'commentRateMissing',
      label: '注释率缺失',
      type: 'number',
      operators: ['isEmpty'],
    },
    {
      key: 'commentRateLow',
      label: '注释率过低',
      type: 'number',
      operators: ['lt'],
    },
    {
      key: 'defectCountMissing',
      label: '缺陷数缺失',
      type: 'number',
      operators: ['isEmpty'],
    },
    {
      key: 'defectCountHigh',
      label: '缺陷数过高',
      type: 'number',
      operators: ['gt'],
    },
    {
      key: 'addedLinesMissing',
      label: '新增代码行数缺失',
      type: 'number',
      operators: ['isEmpty'],
    },
    {
      key: 'addedLinesHigh',
      label: '新增代码行数过多',
      type: 'number',
      operators: ['gt'],
    },
  ];
}
