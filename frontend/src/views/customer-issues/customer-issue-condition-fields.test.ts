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
      severityLevels: [option],
      priorityLevels: [option],
      issueStates: [option],
      bugStatuses: [option],
      categories: [option],
      milestoneTitles: [option],
      reasonCategories: [option],
    });

    expect(fields.find((field) => field.key === 'moduleName')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'assigneeName')?.labelGroupValueType).toBe('STRING');
    expect(fields.find((field) => field.key === 'priorityLevel')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'bugStatus')?.labelGroupEnabled).toBe(true);
    expect(fields.find((field) => field.key === 'reasonCategory')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'title')?.labelGroupEnabled).toBeUndefined();
  });

  it('enables label groups for customer illegal reason values', () => {
    const fields = buildCustomerIssueIllegalConditionFields({
      projectNames: [option],
      moduleNames: [option],
      severityLevels: [option],
      priorityLevels: [option],
      issueStates: [option],
      bugStatuses: [option],
      categories: [option],
      assigneeNames: [option],
      milestoneTitles: [option],
      illegalReasons: [option],
    });

    expect(fields.find((field) => field.key === 'illegalReason')?.labelGroupEnabled).toBeUndefined();
    expect(fields.find((field) => field.key === 'milestoneTitle')?.labelGroupValueType).toBe('STRING');
  });
});
