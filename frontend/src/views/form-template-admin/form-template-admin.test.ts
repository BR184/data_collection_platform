import { describe, expect, it } from 'vitest';
import type { FormAdminPreviewView, FormTemplateSpecView } from '../../types/api';
import {
  addOption,
  applyFieldRows,
  businessChanges,
  changeRowControl,
  applyConditionRows,
  conditionRowsFromSpec,
  conditionSummaries,
  createFieldRow,
  describeConditionRow,
  fieldRowsFromSpec,
  hasUnsupportedConditions,
  historyImpactText,
  moveFieldRow,
  removeOption,
  updateOption,
  validateFieldRows,
} from './form-template-admin';

function spec(): FormTemplateSpecView {
  return {
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
          { value: 'FIXED', label: '已修复' },
          { value: 'REJECTED', label: '已驳回' },
        ],
        storage: { column: 'fix_status', columnType: 'varchar(64)' },
      },
      {
        name: 'supplement_note',
        apiKey: 'supplementNote',
        label: '补充说明',
        type: 'string',
        control: 'textarea',
        required: false,
        maxLength: 8192,
        storage: { column: 'supplement_note', columnType: 'text' },
      },
    ],
    conditions: [
      {
        type: 'conditionalRequired',
        target: 'supplement_note',
        requiredWhen: { field: 'fix_status', equals: 'REJECTED' },
        clearWhen: { field: 'fix_status', equals: 'FIXED' },
      },
    ],
    dictionaries: [{ name: 'cause', version: '1', items: [{ key: 'a', label: 'A' }] }],
  };
}

describe('form template admin helpers', () => {
  it('renames and reorders business fields without changing identity or history', () => {
    const source = spec();
    const rows = fieldRowsFromSpec(source);
    rows[1].label = '补充说明（新）';
    const moved = moveFieldRow(rows, 1, -1);
    const updated = applyFieldRows(source, moved);

    expect(updated.fields.map((field) => field.name)).toEqual([
      'supplement_note',
      'fix_status',
    ]);
    expect(updated.fields[0].label).toBe('补充说明（新）');
    expect(updated.fields[0].storage?.column).toBe('supplement_note');
    expect(updated.conditions).toEqual(source.conditions);
    expect(updated.dictionaries).toEqual(source.dictionaries);
  });

  it('generates independent internal identity for new fields', () => {
    const source = spec();
    const rows = fieldRowsFromSpec(source);
    const added = createFieldRow(rows);
    const updated = applyFieldRows(source, [...rows, added]);

    expect(added.isNew).toBe(true);
    expect(updated.fields[2].name).toBe(added.name);
    expect(updated.fields[2].apiKey).toBe(added.apiKey);
    expect(updated.fields[0].name).toBe('fix_status');
  });

  it('maps business controls to compatible internal types', () => {
    const rows = fieldRowsFromSpec(spec());
    const added = createFieldRow(rows);
    const numeric = changeRowControl(added, 'number');
    expect(numeric.type).toBe('integer');
    expect(numeric.columnType).toBe('integer');

    const existing = changeRowControl(rows[0], 'input');
    expect(existing.type).toBe('enum');
    expect(existing.columnType).toBe('varchar(64)');
  });

  it('edits option labels while keeping stored option values stable', () => {
    let rows = fieldRowsFromSpec(spec());
    rows = updateOption(rows, 0, 0, '修复完成');
    rows = addOption(rows, 0);
    const addedValue = rows[0].options[2].value;
    expect(rows[0].options.map((option) => option.value)).toEqual([
      'FIXED',
      'REJECTED',
      addedValue,
    ]);
    expect(addedValue).toMatch(/^OPT_[0-9A-F]{12}$/);
    rows = removeOption(rows, 0, 1);
    expect(rows[0].options.map((option) => option.value)).toEqual(['FIXED', addedValue]);
    const updated = applyFieldRows(spec(), rows);
    expect(updated.fields[0].options?.[0].label).toBe('修复完成');
  });

  it('never reuses field or option identity after deletion', () => {
    const original = spec();
    const removedFieldKey = original.fields[1].name;
    const removedColumn = original.fields[1].storage?.column;
    let rows = fieldRowsFromSpec(original).filter((row) => row.name !== removedFieldKey);
    const addedField = createFieldRow(rows);
    rows = [...rows, addedField];
    expect(addedField.name).not.toBe(removedFieldKey);
    expect(addedField.name).toMatch(/^f_[0-9a-f]{12}$/);
    expect(addedField.apiKey).toMatch(/^f[0-9a-f]{12}$/);
    expect(addedField.column).not.toBe(removedColumn);
    const updated = applyFieldRows(original, rows);
    expect(updated.fields.map((field) => field.name)).toEqual([
      original.fields[0].name,
      addedField.name,
    ]);

    const removedOptionValue = original.fields[0].options?.[0].value;
    let optionRows = fieldRowsFromSpec(original);
    optionRows = removeOption(optionRows, 0, 0);
    optionRows = addOption(optionRows, 0);
    const addedOptionValue = optionRows[0].options.find((option) =>
      option.value.startsWith('OPT_'),
    )!.value;
    expect(addedOptionValue).not.toBe(removedOptionValue);
    expect(addedOptionValue).toMatch(/^OPT_[0-9A-F]{12}$/);
  });

  it('keeps dictionary-backed enum fields without inline options', () => {
    const source = spec();
    source.fields.push({
      name: 'cause_keys',
      apiKey: 'causeKeys',
      label: '缺陷原因',
      type: 'enum-array',
      control: 'multi-select',
      required: true,
      dictionary: 'cause',
      storage: { column: 'cause_keys', columnType: 'text[]' },
    });
    const rows = fieldRowsFromSpec(source);
    expect(rows[2].dictionary).toBe('cause');
    expect(rows[2].options).toEqual([]);
    expect(validateFieldRows(rows)).toEqual([]);

    const updated = applyFieldRows(source, rows);
    expect(updated.fields[2].dictionary).toBe('cause');
    expect(updated.fields[2].options).toBeUndefined();
    expect(updated.dictionaries).toEqual(source.dictionaries);
  });

  it('rejects empty labels and enum fields without options', () => {
    const rows = fieldRowsFromSpec(spec());
    rows[0].label = '';
    rows[1].options = [];
    rows[1].type = 'enum';
    const errors = validateFieldRows(rows);
    expect(errors.some((error) => error.includes('缺少显示名称'))).toBe(true);
    expect(errors.some((error) => error.includes('至少需要一个选项'))).toBe(true);
  });

  it('explains conditions using business labels', () => {
    const summaries = conditionSummaries(spec(), fieldRowsFromSpec(spec()));
    expect(summaries[0].text).toContain('修复状态');
    expect(summaries[0].text).toContain('已驳回');
    expect(summaries[0].text).toContain('补充说明');
    expect(summaries[0].text).not.toContain('fix_status');
  });

  it('renders Chinese business change rows with history impact', () => {
    const preview = {
      id: 1,
      draftRevision: 2,
      targetVersionId: 3,
      previewHash: 'hash',
      publicationKind: 'MIGRATION',
      diff: {
        added: 1,
        removed: 1,
        modified: 1,
        fields: [
          {
            name: 'new_opinion',
            change: 'ADDED',
            newLabel: '走查意见',
            newControl: 'textarea',
            newRequired: true,
          },
          {
            name: 'supplement_note',
            change: 'REMOVED',
            oldLabel: '补充说明',
            oldControl: 'textarea',
          },
          {
            name: 'fix_status',
            change: 'LABEL',
            changes: ['LABEL'],
            oldLabel: '修复状态',
            newLabel: '修复状态说明',
            oldControl: 'radio',
            newControl: 'select',
          },
        ],
      },
      ddlSummary: [],
      ddlHash: 'ddl',
      impact: { records: 12, instances: 4 },
      blockingErrors: [],
      createdAt: '2026-10-26T00:00:00',
    } as FormAdminPreviewView;

    const rows = businessChanges(preview);
    expect(rows.map((row) => row.changeLabel)).toEqual(['新增', '删除', '名称调整']);
    expect(rows[0].history).toContain('待补充');
    expect(rows[1].history).toContain('历史版本仍保留原值');
    expect(rows[2].history).toContain('字段身份与历史值不变');
    expect(historyImpactText(preview)).toContain('12 条历史记录');
  });
});


function issueReplySpec(): FormTemplateSpecView {
  return {
    spec_version: 1,
    template_key: 'issue_reply',
    fields: [
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
        storage: { column: 'affected_kinds', columnType: 'text[]' },
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
        storage: { column: 'known_affected_functions', columnType: 'text' },
      },
      {
        name: 'has_residual_impact',
        apiKey: 'hasResidualImpact',
        label: '有无遗留问题或潜在影响',
        type: 'boolean',
        control: 'radio',
        required: true,
        storage: { column: 'has_residual_impact', columnType: 'boolean' },
      },
      {
        name: 'residual_impact_detail',
        apiKey: 'residualImpactDetail',
        label: '遗留问题或潜在影响说明',
        type: 'string',
        control: 'textarea',
        required: false,
        requiredWhen: { field: 'has_residual_impact', equals: true },
        clearWhen: { field: 'has_residual_impact', equals: false },
        storage: { column: 'residual_impact_detail', columnType: 'text' },
      },
    ],
    conditions: [
      {
        type: 'mutuallyExclusive',
        field: 'affected_kinds',
        whenContains: 'NONE',
        withAnyOf: ['KNOWN', 'NEW'],
      },
      {
        type: 'conditionalRequired',
        target: 'known_affected_functions',
        requiredWhen: { field: 'affected_kinds', contains: 'KNOWN' },
        clearWhen: { field: 'affected_kinds', notContains: 'KNOWN' },
      },
      {
        type: 'conditionalRequired',
        target: 'residual_impact_detail',
        requiredWhen: { field: 'has_residual_impact', equals: true },
        clearWhen: { field: 'has_residual_impact', equals: false },
      },
    ],
    dictionaries: [],
  };
}

describe('form condition business model', () => {
  it('translates real issue_reply conditions without internal keys and merges duplicates', () => {
    const source = issueReplySpec();
    const rows = fieldRowsFromSpec(source);
    const conditions = conditionRowsFromSpec(source, rows);
    expect(conditions).toHaveLength(3);
    const texts = conditionSummaries(source, rows).map((item) => item.text);
    expect(texts[0]).toContain('修改该缺陷可能影响的功能');
    expect(texts[0]).toContain('无');
    expect(texts[0]).toContain('已知的受影响功能');
    expect(texts[1]).toContain('已知的受影响功能说明');
    expect(texts[1]).toContain('否则自动清空');
    expect(texts[2]).toContain('有无遗留问题或潜在影响');
    expect(texts.join(' ')).not.toContain('affected_kinds');
    expect(texts.join(' ')).not.toContain('KNOWN');
    expect(texts.join(' ')).not.toContain('has_residual_impact');
    expect(hasUnsupportedConditions(source)).toBe(false);
  });

  it('explains issue_response resource severity condition in business terms', () => {
    const source: FormTemplateSpecView = {
      spec_version: 1,
      template_key: 'issue_response',
      fields: [
        {
          name: 'owner_confirmation',
          apiKey: 'ownerConfirmation',
          label: '模块负责人确认',
          type: 'string',
          control: 'textarea',
          required: false,
          storage: { column: 'owner_confirmation', columnType: 'text' },
        },
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
    const rows = fieldRowsFromSpec(source);
    const text = conditionSummaries(source, rows)[0].text;
    expect(text).toContain('议题严重级别');
    expect(text).toContain('一级缺陷');
    expect(text).toContain('模块负责人确认');
    expect(text).not.toContain('resourceSeverityLevel');
    const applied = applyConditionRows(source, conditionRowsFromSpec(source, rows));
    expect(applied.conditions?.[0]).toMatchObject({
      snapshotColumn: 'owner_confirmation_applicable',
      requiredWhen: { resourceSeverityLevel: '一级缺陷' },
    });
  });

  it('writes edited conditions back to root DSL and keeps unsupported rules', () => {
    const source = issueReplySpec();
    const unsupported = { type: 'futureRule', field: 'affected_kinds' };
    source.conditions = [...(source.conditions ?? []), unsupported];
    const rows = fieldRowsFromSpec(source);
    const conditions = conditionRowsFromSpec(source, rows);
    const residual = conditions.find((row) => row.target === 'residual_impact_detail')!;
    residual.values = ['false'];
    residual.clearWhenOff = true;
    const applied = applyConditionRows(source, conditions);

    expect(applied.fields.every((field) => !field.requiredWhen && !field.clearWhen)).toBe(true);
    const required = (applied.conditions ?? []).filter(
      (condition) => condition.type === 'conditionalRequired',
    );
    expect(required).toHaveLength(2);
    expect(required[1]).toMatchObject({
      target: 'residual_impact_detail',
      requiredWhen: { field: 'has_residual_impact', equals: false },
      clearWhen: { field: 'has_residual_impact', equals: true },
    });
    expect(
      (applied.conditions ?? []).some((condition) => condition.type === 'futureRule'),
    ).toBe(true);
    expect(hasUnsupportedConditions(source)).toBe(true);
    expect(
      describeConditionRow(
        { ...residual, supported: false },
        applied,
        fieldRowsFromSpec(applied),
      ),
    ).toContain('暂不支持');
  });
});
