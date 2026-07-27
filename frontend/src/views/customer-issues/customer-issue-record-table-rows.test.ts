import { describe, expect, it } from 'vitest';
import type { CustomerIssueRecordRowResponse } from '../../types/api';
import {
  mapCustomerIssueRecordTableRows,
  parseCustomerIssuePlannedMergeVersions,
} from './customer-issue-record-table-rows';

const record: CustomerIssueRecordRowResponse = {
  issueId: 35679,
  issueIid: 1415,
  issueLink: 'http://gitlab/issues/1415',
  projectId: 325,
  projectName: 'CC_PRODUCT',
  title: '【圆柱齿轮】建议圆柱齿轮的齿数不做限制——高晶电器/新世纪',
  customerNames: '高晶电器、郑州新世纪',
  issueState: 'opened',
  severityLevel: '二级缺陷',
  priorityLevel: 'P1',
  bugStatus: '待处理',
  category: '缺陷',
  reasonCategory: '',
  milestoneTitle: '',
  authorName: '',
  handlerName: '王五',
  assigneeName: '张三',
  testingPhase: '',
  fixUser: '',
  moduleNames: '圆柱齿轮',
  functionName: '齿轮',
  delayIssue: false,
  delayReason: '',
  delayCause: '',
  responseDelayed: false,
  resolveDelayed: false,
  illegal: false,
  illegalReason: '',
  createdAt: '2026-07-20T08:00:00',
  retentionHours: 49,
  plannedResolutionAt: '2026-07-25T00:00:00',
  plannedResolutionText: '2026年7月25日',
  plannedMergeVersionBranch: 'CC2026R4',
  updatedAt: '2026-07-22T08:00:00',
  closedAt: null,
  labels: [],
};

describe('customer issue record table rows', () => {
  it('test_customerFields_map_displaysAllCcProductValues', () => {
    const rows = mapCustomerIssueRecordTableRows([record]);

    expect(rows[0]).toMatchObject({
      customerNames: '高晶电器、郑州新世纪',
      retentionHours: 49,
      plannedResolutionAt: '2026年7月25日',
      plannedMergeVersionBranch: ['CC2026R4'],
      handlerName: '王五',
      assigneeName: '张三',
      testingPhase: '未设定测试阶段',
    });
  });

  it('test_planMergeVersions_parse_displaysEveryVersionAsASeparateTag', () => {
    expect(parseCustomerIssuePlannedMergeVersions('CC2026R4 & CC2026R5')).toEqual([
      'CC2026R4',
      'CC2026R5',
    ]);
  });

  it('test_customerFields_map_usesParsedTimeWhenTemplateTextIsEmpty', () => {
    const rows = mapCustomerIssueRecordTableRows([
      { ...record, customerNames: '', retentionHours: null, plannedResolutionText: '' },
    ]);

    expect(rows[0]).toMatchObject({
      customerNames: '-',
      retentionHours: '-',
      plannedResolutionAt: '2026-07-25 00:00:00',
    });
  });
});
