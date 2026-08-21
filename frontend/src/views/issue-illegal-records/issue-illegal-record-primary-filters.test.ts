import { describe, expect, it } from 'vitest';
import { buildIssueIllegalRecordPrimaryFilters } from './issue-illegal-record-primary-filters';

const commonOptions = {
  moduleOptions: [{ label: '模块 A', value: 'module-a' }],
  illegalReasonOptions: [{ label: '原因 A', value: 'reason-a' }],
  assigneeOptions: [{ label: '处理人 A', value: 'assignee-a' }],
  severityOptions: [{ label: '一级', value: 'LEVEL1' }],
  issueStateOptions: [{ label: '未关闭', value: 'opened' }],
  bugStatusOptions: [{ label: '已修复', value: 'fixed' }],
};

describe('issue illegal record primary filters', () => {
  it('preserves customer-specific scope and priority filters', () => {
    const fields = buildIssueIllegalRecordPrimaryFilters({
      scopeKey: 'milestoneTitle',
      scopeLabel: '里程碑',
      scopeOptions: [{ label: 'R1', value: 'R1' }],
      priorityOptions: [{ label: 'P0', value: 'P0' }],
      bugStatusWidth: 220,
      ...commonOptions,
    });

    expect(fields.map((field) => field.key)).toEqual([
      'milestoneTitle',
      'moduleName',
      'illegalReason',
      'issueIid',
      'title',
      'assigneeName',
      'severityLevel',
      'priorityLevel',
      'issueState',
      'bugStatus',
    ]);
    expect(fields[0]).toMatchObject({
      label: '里程碑',
      defaultStrategy: 'first-available',
      clearable: false,
      options: [{ label: 'R1', value: 'R1' }],
    });
    expect(fields[7]).toMatchObject({ options: [{ label: 'P0', value: 'P0' }] });
    expect(fields.at(-1)?.width).toBe(220);
  });

  it('keeps system-test scope without adding a customer-only priority filter', () => {
    const fields = buildIssueIllegalRecordPrimaryFilters({
      scopeKey: 'testingPhase',
      scopeLabel: '测试阶段',
      scopeOptions: [{ label: '阶段 A', value: 'phase-a' }],
      bugStatusWidth: 240,
      ...commonOptions,
    });

    expect(fields.map((field) => field.key)).toEqual([
      'testingPhase',
      'moduleName',
      'illegalReason',
      'issueIid',
      'title',
      'assigneeName',
      'severityLevel',
      'issueState',
      'bugStatus',
    ]);
    expect(fields[0]).toMatchObject({ label: '测试阶段', options: [{ value: 'phase-a' }] });
    expect(fields.some((field) => field.key === 'priorityLevel')).toBe(false);
    expect(fields.at(-1)?.width).toBe(240);
  });
});
