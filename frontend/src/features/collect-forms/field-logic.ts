import type {
  FormConditionView,
  FormFieldSpecView,
  FormTemplateSpecView,
} from '../../types/api';

export type FieldValue = string | number | boolean | string[] | null;
export type FieldValueMap = Record<string, FieldValue>;

export interface FieldOptionView {
  value: string;
  label: string;
  group: string | null;
}

export interface ClearedField {
  field: FormFieldSpecView;
  label: string;
  previous: FieldValue;
}

export interface FieldChangeOutcome {
  fields: FieldValueMap;
  cleared: ClearedField[];
}

export interface ValidationResult {
  errors: Record<string, string>;
  firstInvalidField: FormFieldSpecView | null;
}

const VERSION_BRANCH = /^CC\d{4}R[1-9]\d*$/;

/** 字段在页面出现时按 field_spec 顺序使用，空值统一为 null / 空数组。 */
export function emptyFieldValue(field: FormFieldSpecView): FieldValue {
  return field.type === 'enum-array' ? [] : null;
}

export function emptyFields(spec: FormTemplateSpecView): FieldValueMap {
  const fields: FieldValueMap = {};
  for (const field of spec.fields) {
    fields[field.apiKey] = emptyFieldValue(field);
  }
  return fields;
}

function normalizeString(raw: unknown): string | null {
  if (raw == null) {
    return null;
  }
  return typeof raw === 'string' ? raw : String(raw);
}

/** 将记录/草稿中的任意值收敛为当前字段类型的可编辑值。 */
export function normalizeFieldValue(field: FormFieldSpecView, raw: unknown): FieldValue {
  if (field.type === 'enum-array') {
    return Array.isArray(raw)
      ? raw.filter((item): item is string => typeof item === 'string')
      : [];
  }
  if (field.type === 'integer') {
    return typeof raw === 'number' && Number.isInteger(raw) ? raw : null;
  }
  if (field.type === 'boolean') {
    return typeof raw === 'boolean' ? raw : null;
  }
  if (field.type === 'date' || field.type === 'enum') {
    return typeof raw === 'string' && raw.length > 0 ? raw : null;
  }
  return normalizeString(raw);
}

export function normalizeFields(
  spec: FormTemplateSpecView,
  source: Record<string, unknown> | null | undefined,
): FieldValueMap {
  const fields: FieldValueMap = {};
  for (const field of spec.fields) {
    fields[field.apiKey] = normalizeFieldValue(field, source?.[field.apiKey]);
  }
  return fields;
}

export function cloneFields(fields: FieldValueMap): FieldValueMap {
  const cloned: FieldValueMap = {};
  for (const [key, value] of Object.entries(fields)) {
    cloned[key] = Array.isArray(value) ? [...value] : value;
  }
  return cloned;
}

export function isFieldEmpty(value: FieldValue | undefined): boolean {
  if (value == null) {
    return true;
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  if (typeof value === 'string') {
    return value.trim().length === 0;
  }
  return false;
}

function comparableValue(value: FieldValue | undefined): string | number | boolean | string[] | null {
  if (Array.isArray(value)) {
    return [...value].sort();
  }
  return value ?? null;
}

export function fieldsEqual(left: FieldValueMap, right: FieldValueMap): boolean {
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  for (const key of keys) {
    const leftValue = comparableValue(left[key]);
    const rightValue = comparableValue(right[key]);
    if (Array.isArray(leftValue) && Array.isArray(rightValue)) {
      if (leftValue.length !== rightValue.length) {
        return false;
      }
      if (leftValue.some((item, index) => item !== rightValue[index])) {
        return false;
      }
      continue;
    }
    if (leftValue !== rightValue) {
      return false;
    }
  }
  return true;
}

/** 新建块是否存在实际输入；请求标识与系统默认值不参与判断。 */
export function hasFieldContent(fields: FieldValueMap): boolean {
  return Object.values(fields).some((value) => !isFieldEmpty(value));
}

export function hasFieldChanges(fields: FieldValueMap, baseFields: FieldValueMap): boolean {
  return !fieldsEqual(fields, baseFields);
}

export function fieldOptions(
  spec: FormTemplateSpecView,
  field: FormFieldSpecView,
): FieldOptionView[] {
  if (field.options && field.options.length > 0) {
    return field.options.map((option) => ({
      value: option.value,
      label: option.label,
      group: null,
    }));
  }
  if (field.dictionary) {
    const dictionary = (spec.dictionaries ?? []).find((item) => item.name === field.dictionary);
    return (dictionary?.items ?? []).map((item) => ({
      value: item.key,
      label: item.label,
      group: item.group ?? null,
    }));
  }
  return [];
}

function fieldByName(spec: FormTemplateSpecView, name: string) {
  return spec.fields.find((field) => field.name === name) ?? null;
}

function equalsConditionValue(value: FieldValue | undefined, expected: unknown): boolean {
  if (expected == null) {
    return isFieldEmpty(value);
  }
  if (typeof expected === 'boolean') {
    return value === expected;
  }
  if (typeof expected === 'number') {
    return typeof value === 'number' && value === expected;
  }
  if (typeof expected === 'string') {
    return value === expected;
  }
  return false;
}

/**
 * 按冻结条件求值；resourceSeverityLevel 来自已解析资源，其余条件引用字段 name。
 */
export function evaluateCondition(
  spec: FormTemplateSpecView,
  condition: FormConditionView | null | undefined,
  fields: FieldValueMap,
  severityLevel: string | null,
): boolean {
  if (!condition) {
    return false;
  }
  if (condition.resourceSeverityLevel !== undefined) {
    const expected = condition.resourceSeverityLevel;
    if (Array.isArray(expected)) {
      return expected.some((item) => String(item) === severityLevel);
    }
    return severityLevel != null && String(expected) === severityLevel;
  }
  const sourceField = fieldByName(spec, String(condition.field ?? ''));
  const value = sourceField ? fields[sourceField.apiKey] : undefined;
  if (condition.contains !== undefined) {
    return Array.isArray(value) && value.includes(String(condition.contains));
  }
  if (condition.notContains !== undefined) {
    return !(Array.isArray(value) && value.includes(String(condition.notContains)));
  }
  if ('equals' in condition) {
    return equalsConditionValue(value, condition.equals);
  }
  return false;
}

function conditionRule(
  spec: FormTemplateSpecView,
  targetName: string,
  rule: 'requiredWhen' | 'clearWhen',
): FormConditionView | null {
  for (const condition of spec.conditions ?? []) {
    if (condition.type !== 'conditionalRequired' || String(condition.target ?? '') !== targetName) {
      continue;
    }
    const configured = condition[rule];
    if (configured && typeof configured === 'object') {
      return configured as FormConditionView;
    }
  }
  return null;
}

export function isFieldRequired(
  spec: FormTemplateSpecView,
  field: FormFieldSpecView,
  fields: FieldValueMap,
  severityLevel: string | null,
): boolean {
  if (field.required) {
    return true;
  }
  return evaluateCondition(spec, field.requiredWhen, fields, severityLevel)
    || evaluateCondition(spec, conditionRule(spec, field.name, 'requiredWhen'), fields, severityLevel);
}

export function isFieldCleared(
  spec: FormTemplateSpecView,
  field: FormFieldSpecView,
  fields: FieldValueMap,
  severityLevel: string | null,
): boolean {
  return evaluateCondition(spec, field.clearWhen, fields, severityLevel)
    || evaluateCondition(spec, conditionRule(spec, field.name, 'clearWhen'), fields, severityLevel);
}

function resolveMutuallyExclusive(
  spec: FormTemplateSpecView,
  fields: FieldValueMap,
  previous: FieldValueMap,
): FieldValueMap {
  const next = { ...fields };
  for (const condition of spec.conditions ?? []) {
    if (condition.type !== 'mutuallyExclusive') {
      continue;
    }
    const target = fieldByName(spec, String(condition.field ?? ''));
    if (!target) {
      continue;
    }
    const selected = next[target.apiKey];
    if (!Array.isArray(selected)) {
      continue;
    }
    const whenContains = String(condition.whenContains ?? '');
    const withAnyOf = Array.isArray(condition.withAnyOf)
      ? condition.withAnyOf.map(String)
      : [];
    if (!selected.includes(whenContains) || !selected.some((value) => withAnyOf.includes(value))) {
      continue;
    }
    const previousSelected = previous[target.apiKey];
    const before = Array.isArray(previousSelected) ? previousSelected : [];
    const added = selected.filter((value) => !before.includes(value));
    const removed = added.includes(whenContains)
      ? withAnyOf
      : added.some((value) => withAnyOf.includes(value))
        ? [whenContains]
        : withAnyOf;
    next[target.apiKey] = selected.filter((value) => !removed.includes(value));
  }
  return next;
}

/**
 * 计算一次字段变更后的输入，并按冻结 clearWhen / 互斥规则清除不再适用的内容。
 *
 * 返回的 cleared 表示原值非空、需要向用户确认后才能丢弃的字段；字段本身仍保留在结果中，
 * 调用方确认后才应使用该结果。
 */
export function resolveFieldChange(
  spec: FormTemplateSpecView,
  fields: FieldValueMap,
  apiKey: string,
  rawValue: unknown,
  severityLevel: string | null,
): FieldChangeOutcome {
  const field = spec.fields.find((item) => item.apiKey === apiKey);
  if (!field) {
    return { fields: cloneFields(fields), cleared: [] };
  }
  const next: FieldValueMap = {
    ...cloneFields(fields),
    [apiKey]: normalizeFieldValue(field, rawValue),
  };
  const resolved = resolveMutuallyExclusive(spec, next, fields);
  const cleared: ClearedField[] = [];
  for (const candidate of spec.fields) {
    const value = resolved[candidate.apiKey];
    if (isFieldEmpty(value)) {
      continue;
    }
    if (isFieldCleared(spec, candidate, resolved, severityLevel)) {
      cleared.push({
        field: candidate,
        label: candidate.label || candidate.name,
        previous: value,
      });
      resolved[candidate.apiKey] = emptyFieldValue(candidate);
    }
  }
  return { fields: resolved, cleared };
}

export function formatFieldValue(
  spec: FormTemplateSpecView,
  field: FormFieldSpecView,
  value: FieldValue | undefined,
): string {
  if (isFieldEmpty(value)) {
    return '—';
  }
  if (field.type === 'boolean') {
    return value === true ? '是' : '否';
  }
  if (Array.isArray(value)) {
    const options = fieldOptions(spec, field);
    const labels = options.length
      ? options.filter((option) => value.includes(option.value)).map((option) => option.label)
      : value;
    return labels.length ? labels.join('、') : '—';
  }
  if (field.type === 'enum') {
    const option = fieldOptions(spec, field).find((item) => item.value === value);
    return option?.label ?? String(value);
  }
  return String(value);
}

function optionValueSet(spec: FormTemplateSpecView, field: FormFieldSpecView): Set<string> {
  return new Set(fieldOptions(spec, field).map((option) => option.value));
}

function isValidDate(value: string, field: FormFieldSpecView): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() !== year
    || date.getUTCMonth() !== month - 1
    || date.getUTCDate() !== day
  ) {
    return false;
  }
  if (field.minYear != null && year < field.minYear) {
    return false;
  }
  return !(field.maxYear != null && year > field.maxYear);
}

function normalizeVersionMembers(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

function isValidVersionMembers(value: string): boolean {
  const normalized = normalizeVersionMembers(value);
  if (!normalized) {
    return false;
  }
  return normalized.split('&').every((branch) => VERSION_BRANCH.test(branch.trim()));
}

function validateSingleField(
  spec: FormTemplateSpecView,
  field: FormFieldSpecView,
  value: FieldValue | undefined,
): string | null {
  if (isFieldEmpty(value)) {
    return null;
  }
  const label = field.label || field.name;
  if (field.type === 'string') {
    const text = String(value);
    if (field.control === 'version-members' && !isValidVersionMembers(text)) {
      return `${label}必须是一个或多个 CCyyyyRn，多个以 & 分隔`;
    }
    if (field.maxLength != null && [...text].length > field.maxLength) {
      return `${label}不能超过${field.maxLength}个字符`;
    }
    return null;
  }
  if (field.type === 'integer') {
    if (typeof value !== 'number' || !Number.isInteger(value)) {
      return `${label}必须是整数`;
    }
    const min = field.min ?? -2147483648;
    const max = field.max ?? 2147483647;
    return value < min || value > max ? `${label}必须在${min}到${max}之间` : null;
  }
  if (field.type === 'boolean') {
    return typeof value === 'boolean' ? null : `${label}必须是布尔值`;
  }
  if (field.type === 'date') {
    return typeof value === 'string' && isValidDate(value, field)
      ? null
      : `${label}必须是合法日期`;
  }
  const allowed = optionValueSet(spec, field);
  if (field.type === 'enum') {
    return typeof value === 'string' && allowed.has(value)
      ? null
      : `${label}取值不在允许范围内`;
  }
  if (field.type === 'enum-array') {
    if (!Array.isArray(value)) {
      return `${label}必须是多选值`;
    }
    if (value.some((item) => !allowed.has(item))) {
      return `${label}包含不允许的取值`;
    }
    if (field.maxItems != null && value.length > field.maxItems) {
      return `${label}最多选择${field.maxItems}项`;
    }
  }
  return null;
}

function validateMutualExclusion(
  spec: FormTemplateSpecView,
  fields: FieldValueMap,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const condition of spec.conditions ?? []) {
    if (condition.type !== 'mutuallyExclusive') {
      continue;
    }
    const target = fieldByName(spec, String(condition.field ?? ''));
    if (!target) {
      continue;
    }
    const selected = fields[target.apiKey];
    if (!Array.isArray(selected)) {
      continue;
    }
    const whenContains = String(condition.whenContains ?? '');
    const withAnyOf = Array.isArray(condition.withAnyOf)
      ? condition.withAnyOf.map(String)
      : [];
    if (selected.includes(whenContains) && selected.some((value) => withAnyOf.includes(value))) {
      errors[target.apiKey] = `${target.label || target.name}的选项互斥`;
    }
  }
  return errors;
}

/**
 * 按冻结规格校验一块待提交内容；草稿保存不调用本函数，允许未填完。
 */
export function validateFields(
  spec: FormTemplateSpecView,
  fields: FieldValueMap,
  severityLevel: string | null,
): ValidationResult {
  const errors: Record<string, string> = {};
  for (const field of spec.fields) {
    const label = field.label || field.name;
    const value = fields[field.apiKey];
    if (isFieldRequired(spec, field, fields, severityLevel) && isFieldEmpty(value)) {
      errors[field.apiKey] = `${label}不能为空`;
      continue;
    }
    if (isFieldCleared(spec, field, fields, severityLevel) && !isFieldEmpty(value)) {
      errors[field.apiKey] = `${label}在当前条件下必须为空`;
      continue;
    }
    const message = validateSingleField(spec, field, value);
    if (message) {
      errors[field.apiKey] = message;
    }
  }
  Object.assign(errors, validateMutualExclusion(spec, fields));
  const firstInvalidField = spec.fields.find((field) => errors[field.apiKey] != null) ?? null;
  return { errors, firstInvalidField };
}

export function isDateDisabled(field: FormFieldSpecView): (date: Date) => boolean {
  return (date: Date) => {
    const year = date.getFullYear();
    if (field.minYear != null && year < field.minYear) {
      return true;
    }
    return field.maxYear != null && year > field.maxYear;
  };
}
