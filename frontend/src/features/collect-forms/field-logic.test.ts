import { describe, expect, it } from 'vitest';
import type { FormTemplateSpecView } from '../../types/api';
import {
  emptyFields,
  formatFieldValue,
  hasFieldChanges,
  hasFieldContent,
  isFieldCleared,
  isFieldRequired,
  normalizeFields,
  resolveFieldChange,
  validateFields,
} from './field-logic';

const issueReplySpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'issue_reply',
  fields: [
    {
      name: 'fix_status',
      apiKey: 'fixStatus',
      label: '修复状态',
      type: 'enum',
      control: 'radio',
      required: true,
      options: [
        { value: 'RESOLVED', label: '已解决' },
        { value: 'DEFERRED', label: '申请延期' },
      ],
    },
    {
      name: 'cause_keys',
      apiKey: 'causeKeys',
      label: '缺陷原因',
      type: 'enum-array',
      control: 'multi-select',
      required: true,
      dictionary: 'DEFECT_CAUSE',
      maxItems: 2,
    },
    { name: 'specific_reason', apiKey: 'specificReason', label: '具体原因', type: 'string', control: 'textarea', required: true, maxLength: 8 },
    { name: 'modification_plan', apiKey: 'modificationPlan', label: '修改方案', type: 'string', control: 'textarea', required: true, maxLength: 8192 },
    { name: 'caused_by_other_defect', apiKey: 'causedByOtherDefect', label: '由修改其他缺陷造成', type: 'boolean', control: 'radio', required: true },
    {
      name: 'affected_kinds',
      apiKey: 'affectedKinds',
      label: '修改该缺陷可能影响的功能',
      type: 'enum-array',
      control: 'checkbox',
      required: true,
      options: [
        { value: 'KNOWN', label: '已知的受影响功能' },
        { value: 'NEW', label: '新识别的受影响功能' },
        { value: 'NONE', label: '无' },
      ],
    },
    {
      name: 'known_affected_functions',
      apiKey: 'knownAffectedFunctions',
      label: '已知的受影响功能说明',
      type: 'string',
      control: 'textarea',
      required: false,
      requiredWhen: { field: 'affected_kinds', contains: 'KNOWN' },
      clearWhen: { field: 'affected_kinds', notContains: 'KNOWN' },
      maxLength: 8192,
    },
    {
      name: 'new_affected_functions',
      apiKey: 'newAffectedFunctions',
      label: '新识别的受影响功能说明',
      type: 'string',
      control: 'textarea',
      required: false,
      requiredWhen: { field: 'affected_kinds', contains: 'NEW' },
      clearWhen: { field: 'affected_kinds', notContains: 'NEW' },
      maxLength: 8192,
    },
    {
      name: 'impact_test_status',
      apiKey: 'impactTestStatus',
      label: '是否对可能影响的功能进行了测试',
      type: 'enum',
      control: 'radio',
      required: true,
      options: [
        { value: 'YES', label: '是' },
        { value: 'NO', label: '否' },
        { value: 'NOT_APPLICABLE', label: '不涉及' },
      ],
    },
    { name: 'has_residual_impact', apiKey: 'hasResidualImpact', label: '有无遗留问题或潜在影响', type: 'boolean', control: 'radio', required: true },
    {
      name: 'residual_impact_detail',
      apiKey: 'residualImpactDetail',
      label: '遗留问题或潜在影响说明',
      type: 'string',
      control: 'textarea',
      required: false,
      requiredWhen: { field: 'has_residual_impact', equals: true },
      clearWhen: { field: 'has_residual_impact', equals: false },
      maxLength: 8192,
    },
    {
      name: 'relation_table_updated',
      apiKey: 'relationTableUpdated',
      label: '是否更新了关联关系表',
      type: 'enum',
      control: 'radio',
      required: true,
      options: [
        { value: 'YES', label: '是' },
        { value: 'NO', label: '否' },
        { value: 'NOT_APPLICABLE', label: '不涉及' },
      ],
    },
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [
    { type: 'mutuallyExclusive', field: 'affected_kinds', whenContains: 'NONE', withAnyOf: ['KNOWN', 'NEW'] },
    { type: 'conditionalRequired', target: 'known_affected_functions', requiredWhen: { field: 'affected_kinds', contains: 'KNOWN' }, clearWhen: { field: 'affected_kinds', notContains: 'KNOWN' } },
    { type: 'conditionalRequired', target: 'new_affected_functions', requiredWhen: { field: 'affected_kinds', contains: 'NEW' }, clearWhen: { field: 'affected_kinds', notContains: 'NEW' } },
    { type: 'conditionalRequired', target: 'residual_impact_detail', requiredWhen: { field: 'has_residual_impact', equals: true }, clearWhen: { field: 'has_residual_impact', equals: false } },
  ],
  dictionaries: [
    {
      name: 'DEFECT_CAUSE',
      items: [
        { key: 'design_forget', label: '功能设计遗漏', group: '设计问题' },
        { key: 'logic_flow_control_error', label: '流程控制错误', group: '编码规范' },
      ],
    },
  ],
};

const issueResponseSpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'issue_response',
  fields: [
    {
      name: 'problem_type',
      apiKey: 'problemType',
      label: '问题类型',
      type: 'enum',
      control: 'radio',
      required: true,
      options: [
        { value: 'DEFECT', label: '缺陷' },
        { value: 'REQUIREMENT', label: '需求' },
      ],
    },
    { name: 'problem_cause', apiKey: 'problemCause', label: '问题原因', type: 'string', control: 'textarea', required: true, maxLength: 8192 },
    { name: 'modification_plan', apiKey: 'modificationPlan', label: '修改方案', type: 'string', control: 'textarea', required: true, maxLength: 8192 },
    {
      name: 'owner_confirmation',
      apiKey: 'ownerConfirmation',
      label: '模块负责人确认',
      type: 'string',
      control: 'textarea',
      required: false,
      requiredWhen: { resourceSeverityLevel: '一级缺陷' },
      clearWhen: { resourceSeverityLevel: ['二级缺陷', '三级缺陷', '建议类', '未设定'] },
      maxLength: 8192,
    },
    {
      name: 'planned_resolution_date',
      apiKey: 'plannedResolutionDate',
      label: '计划解决时间',
      type: 'date',
      control: 'date',
      required: true,
      minYear: 2020,
      maxYear: 2040,
    },
    {
      name: 'planned_merge_version_branch',
      apiKey: 'plannedMergeVersionBranch',
      label: '计划合并的版本分支',
      type: 'string',
      control: 'version-members',
      required: true,
      maxLength: 255,
    },
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [
    {
      type: 'conditionalRequired',
      target: 'owner_confirmation',
      requiredWhen: { resourceSeverityLevel: '一级缺陷' },
      clearWhen: { resourceSeverityLevel: ['二级缺陷', '三级缺陷', '建议类', '未设定'] },
      snapshotColumn: 'owner_confirmation_applicable',
    },
  ],
  dictionaries: [],
};

describe('field logic', () => {
  it('normalizes typed defaults and compares independently of array order', () => {
    const fields = emptyFields(issueReplySpec);
    expect(fields.causeKeys).toEqual([]);
    expect(fields.fixStatus).toBeNull();
    expect(hasFieldContent(fields)).toBe(false);

    const first = normalizeFields(issueReplySpec, {
      fixStatus: 'RESOLVED',
      causeKeys: ['design_forget', 'logic_flow_control_error'],
      hasResidualImpact: false,
    });
    const second = normalizeFields(issueReplySpec, {
      fixStatus: 'RESOLVED',
      causeKeys: ['logic_flow_control_error', 'design_forget'],
      hasResidualImpact: false,
    });
    expect(hasFieldChanges(first, second)).toBe(false);
    expect(hasFieldContent(second)).toBe(true);
  });

  it('validates required, dictionary, conditional and cleared fields', () => {
    const fields = emptyFields(issueReplySpec);
    let result = validateFields(issueReplySpec, fields, null);
    expect(result.errors.fixStatus).toBe('修复状态不能为空');
    expect(result.firstInvalidField?.apiKey).toBe('fixStatus');

    Object.assign(fields, {
      fixStatus: 'RESOLVED',
      causeKeys: ['design_forget'],
      specificReason: '原因',
      modificationPlan: '方案',
      causedByOtherDefect: false,
      affectedKinds: ['KNOWN'],
      impactTestStatus: 'YES',
      hasResidualImpact: false,
      relationTableUpdated: 'NO',
    });
    result = validateFields(issueReplySpec, fields, null);
    expect(result.errors.knownAffectedFunctions).toBe('已知的受影响功能说明不能为空');

    fields.knownAffectedFunctions = '受影响';
    fields.affectedKinds = ['NONE'];
    result = validateFields(issueReplySpec, fields, null);
    expect(result.errors.knownAffectedFunctions).toBe('已知的受影响功能说明在当前条件下必须为空');

    fields.affectedKinds = ['NONE', 'KNOWN'];
    result = validateFields(issueReplySpec, fields, null);
    expect(result.errors.affectedKinds).toBe('修改该缺陷可能影响的功能的选项互斥');

    fields.affectedKinds = ['NONE'];
    fields.knownAffectedFunctions = null;
    fields.causeKeys = ['unknown_key'];
    result = validateFields(issueReplySpec, fields, null);
    expect(result.errors.causeKeys).toBe('缺陷原因包含不允许的取值');
  });

  it('applies residual-impact clearing and reports discarded input', () => {
    const fields = emptyFields(issueReplySpec);
    fields.hasResidualImpact = true;
    fields.residualImpactDetail = '仍有潜在影响';

    const outcome = resolveFieldChange(issueReplySpec, fields, 'hasResidualImpact', false, null);
    expect(outcome.fields.hasResidualImpact).toBe(false);
    expect(outcome.fields.residualImpactDetail).toBeNull();
    expect(outcome.cleared).toHaveLength(1);
    expect(outcome.cleared[0].label).toBe('遗留问题或潜在影响说明');
    expect(outcome.cleared[0].previous).toBe('仍有潜在影响');
    expect(isFieldCleared(issueReplySpec, issueReplySpec.fields[10], outcome.fields, null)).toBe(true);
  });

  it('resolves mutually exclusive options and keeps details when no input would be lost', () => {
    const fields = emptyFields(issueReplySpec);
    fields.affectedKinds = ['NONE'];
    const switched = resolveFieldChange(issueReplySpec, fields, 'affectedKinds', ['NONE', 'KNOWN'], null);
    expect(switched.fields.affectedKinds).toEqual(['KNOWN']);
    expect(switched.cleared).toHaveLength(0);
    expect(isFieldRequired(issueReplySpec, issueReplySpec.fields[6], switched.fields, null)).toBe(true);

    switched.fields.knownAffectedFunctions = '已有说明';
    const back = resolveFieldChange(issueReplySpec, switched.fields, 'affectedKinds', ['NONE'], null);
    expect(back.fields.affectedKinds).toEqual(['NONE']);
    expect(back.fields.knownAffectedFunctions).toBeNull();
    expect(back.cleared.map((item) => item.label)).toEqual(['已知的受影响功能说明']);
  });

  it('applies resource severity conditions to owner confirmation', () => {
    const fields = emptyFields(issueResponseSpec);
    fields.problemType = 'DEFECT';
    fields.problemCause = '原因';
    fields.modificationPlan = '方案';
    fields.plannedResolutionDate = '2026-12-01';
    fields.plannedMergeVersionBranch = 'CC2026R1&CC2026R2';

    let result = validateFields(issueResponseSpec, fields, '一级缺陷');
    expect(result.errors.ownerConfirmation).toBe('模块负责人确认不能为空');

    fields.ownerConfirmation = '负责人已确认';
    result = validateFields(issueResponseSpec, fields, '一级缺陷');
    expect(result.errors.ownerConfirmation).toBeUndefined();

    result = validateFields(issueResponseSpec, fields, '二级缺陷');
    expect(result.errors.ownerConfirmation).toBe('模块负责人确认在当前条件下必须为空');
  });

  it('validates dates, integers, version members and text length', () => {
    const fields = emptyFields(issueResponseSpec);
    fields.problemType = 'DEFECT';
    fields.problemCause = '原因';
    fields.modificationPlan = '方案';
    fields.plannedResolutionDate = '2019-01-01';
    fields.plannedMergeVersionBranch = 'R1';
    let result = validateFields(issueResponseSpec, fields, null);
    expect(result.errors.plannedResolutionDate).toBe('计划解决时间必须是合法日期');
    expect(result.errors.plannedMergeVersionBranch).toBe('计划合并的版本分支必须是一个或多个 CCyyyyRn，多个以 & 分隔');

    fields.plannedResolutionDate = '2026-02-30';
    result = validateFields(issueResponseSpec, fields, null);
    expect(result.errors.plannedResolutionDate).toBe('计划解决时间必须是合法日期');

    fields.plannedResolutionDate = '2026-12-01';
    fields.plannedMergeVersionBranch = 'CC2026R1 & CC2026R2';
    result = validateFields(issueResponseSpec, fields, null);
    expect(result.errors.plannedMergeVersionBranch).toBeUndefined();

    fields.supplementNote = '123456789';
    result = validateFields(
      { ...issueResponseSpec, fields: issueResponseSpec.fields.map((field) => field.apiKey === 'supplementNote' ? { ...field, maxLength: 8 } : field) },
      fields,
      null,
    );
    expect(result.errors.supplementNote).toBe('补充说明不能超过8个字符');
  });

  it('formats dictionary and boolean values for read-only display', () => {
    const causeField = issueReplySpec.fields[1];
    expect(formatFieldValue(issueReplySpec, causeField, ['logic_flow_control_error', 'design_forget'])).toBe(
      '功能设计遗漏、流程控制错误',
    );
    expect(formatFieldValue(issueReplySpec, issueReplySpec.fields[2], '说明')).toBe('说明');
    const booleanField = issueReplySpec.fields[4];
    expect(formatFieldValue(issueReplySpec, booleanField, true)).toBe('是');
    expect(formatFieldValue(issueReplySpec, booleanField, false)).toBe('否');
    expect(formatFieldValue(issueReplySpec, booleanField, null)).toBe('—');
  });
});
