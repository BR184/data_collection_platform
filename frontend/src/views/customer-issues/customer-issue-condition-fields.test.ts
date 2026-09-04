import { describe, expect, it } from 'vitest';
import {
  buildCustomerIssueIllegalConditionFields,
  buildCustomerIssueRecordConditionFields,
} from './customer-issue-condition-fields';

const option = { label: 'A', value: 'A' };

describe('customer issue condition fields', () => {
  it('enables label groups for reusable string value fields on customer issue records', () => {
    const fields = buildCustomerIssueRecordConditionFields({
      projectNames: [option],
      moduleNames: [option],
      functionNames: [option],
      customerNames: [option],
      severityLevels: [option],
      priorityLevels: [option],
      issueStates: [option],
      bugStatuses: [option],
      categories: [option],
      authorNames: [option],
      handlerNames: [option],
      assigneeNames: [option],
      testingPhases: [option],
      fixUsers: [option],
      delayCauses: [option],
      plannedMergeVersionBranches: [option],
      milestoneTitles: [option],
      reasonCategories: [option],
    });

    expect(fields.find((field) => field.key === 'moduleName')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'handlerName')?.labelGroupValueType).toBe('STRING');
    expect(fields.find((field) => field.key === 'assigneeName')?.labelGroupValueType).toBe('STRING');
    expect(fields.find((field) => field.key === 'authorName')?.labelDimensionKey).toBe('person');
    expect(fields.find((field) => field.key === 'handlerName')?.labelDimensionKey).toBe('person');
    expect(fields.find((field) => field.key === 'assigneeName')?.labelDimensionKey).toBe('person');
    expect(fields.find((field) => field.key === 'priorityLevel')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'bugStatus')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'reasonCategory')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'title')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'customerName')?.options).toEqual([option]);
    expect(fields.find((field) => field.key === 'testingPhase')?.options).toEqual([option]);
    expect(fields.find((field) => field.key === 'fixUser')?.options).toEqual([option]);
    expect(fields.find((field) => field.key === 'delayCause')?.options).toEqual([option]);
    expect(fields.find((field) => field.key === 'handlerName')?.label).toBe('议题处理人');
    expect(fields.find((field) => field.key === 'assigneeName')?.label).toBe('议题指派人');
  });

  it('keeps CC_PRODUCT-only condition fields out of the delay topic', () => {
    const fields = buildCustomerIssueRecordConditionFields({
      projectNames: [option],
      moduleNames: [option],
      functionNames: [option],
      customerNames: [option],
      severityLevels: [option],
      priorityLevels: [option],
      issueStates: [option],
      bugStatuses: [option],
      categories: [option],
      authorNames: [option],
      handlerNames: [option],
      assigneeNames: [option],
      testingPhases: [option],
      fixUsers: [option],
      delayCauses: [option],
      plannedMergeVersionBranches: [option],
      milestoneTitles: [option],
      reasonCategories: [option],
    }, false);

    expect(fields.map((field) => field.key)).not.toContain('testingPhase');
    expect(fields.map((field) => field.key)).not.toContain('customerName');
    expect(fields.map((field) => field.key)).not.toContain('fixUser');
    expect(fields.map((field) => field.key)).not.toContain('delayCause');
    expect(fields.map((field) => field.key)).not.toContain('handlerName');
  });

  it('enables label groups for customer illegal reason values', () => {
    const fields = buildCustomerIssueIllegalConditionFields({
      projectNames: [option],
      moduleNames: [option],
      functionNames: [option],
      severityLevels: [option],
      priorityLevels: [option],
      issueStates: [option],
      bugStatuses: [option],
      categories: [option],
      authorNames: [option],
      assigneeNames: [option],
      milestoneTitles: [option],
      illegalReasons: [option],
    });

    expect(fields.find((field) => field.key === 'illegalReason')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'milestoneTitle')?.labelGroupValueType).toBe('STRING');
  });
});
