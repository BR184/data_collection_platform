import { describe, expect, it } from 'vitest';
import type { CustomerIssueRecordRowResponse } from '../../types/api';
import { mapCustomerIssueRecordTableRows } from './customer-issue-record-table-rows';

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
    const rows = mapCustomerIssueRecordTableRows([{ ...record, delayCause: '方案卡点&技术卡点' }]);

    expect(rows[0]).toMatchObject({
      customerNames: '高晶电器、郑州新世纪',
      retentionHours: 49,
      plannedResolutionAt: '2026年7月25日',
      plannedMergeVersionBranch: ['CC2026R4'],
      handlerName: '王五',
      assigneeName: '张三',
      testingPhase: '未设定测试阶段',
    });
    expect(rows[0].delayCause).toEqual([
      { label: '方案卡点', type: 'primary' },
      { label: '技术卡点', type: 'primary' },
    ]);
  });

  it('keeps_emptyDelayCause_asEmptyTagListForTablePlaceholder', () => {
    const rows = mapCustomerIssueRecordTableRows([{ ...record, delayCause: '' }]);

    expect(rows[0].delayCause).toEqual([]);
  });

  it('test_planMergeVersions_map_keepsEverySourceMemberAsASeparateTableTag', () => {
    const rows = mapCustomerIssueRecordTableRows([
      { ...record, plannedMergeVersionBranch: 'release_2026R3, dev' },
    ]);

    expect(rows[0].plannedMergeVersionBranch).toEqual(['release_2026R3', 'dev']);
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
