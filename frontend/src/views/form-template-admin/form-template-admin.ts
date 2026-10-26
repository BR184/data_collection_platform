import type {
  FormAdminPreviewView,
  FormDictionaryView,
  FormFieldOptionView,
  FormFieldSpecView,
  FormSpecConditionView,
  FormTemplateSpecView,
} from '../../types/api';

/** 业务可见的填写方式；控件到内部字段类型的唯一映射。 */
export const CONTROL_OPTIONS = [
  { value: 'input', label: '单行文本', type: 'string' },
  { value: 'textarea', label: '多行文本', type: 'string' },
  { value: 'number', label: '数字', type: 'integer' },
  { value: 'switch', label: '是 / 否', type: 'boolean' },
  { value: 'date', label: '日期', type: 'date' },
  { value: 'radio', label: '单选', type: 'enum' },
  { value: 'select', label: '下拉选择', type: 'enum' },
  { value: 'checkbox-group', label: '多选', type: 'enum-array' },
  { value: 'person-select', label: '人员选择', type: 'string' },
] as const;

export type ControlValue = (typeof CONTROL_OPTIONS)[number]['value'];

const CONTROL_BY_VALUE = new Map(CONTROL_OPTIONS.map((option) => [option.value, option]));

/** 管理页字段表格的一行；内部键与存储列由系统维护，不直接展示。 */
export interface FieldRow {
  name: string;
  apiKey: string;
  column: string;
  columnType: string;
  nameColumn: string;
  label: string;
  control: string;
  type: FormFieldSpecView['type'];
  required: boolean;
  maxLength: number | null;
  options: FormFieldOptionView[];
  /** 词典来源；非空时选项由标准词典提供，界面不再编辑内联选项。 */
  dictionary: string | null;
  isNew: boolean;
}

const TYPE_COLUMN: Record<FormFieldSpecView['type'], string> = {
  string: 'varchar(200)',
  integer: 'integer',
  boolean: 'boolean',
  date: 'date',
  enum: 'varchar(64)',
  'enum-array': 'text[]',
};

/** 业务填写方式名称。 */
export function controlLabel(control: string | undefined): string {
  return CONTROL_BY_VALUE.get(control as ControlValue)?.label ?? control ?? '未设置';
}

/** 当前数据类型可选的填写方式；既有字段不通过控件改变存储类型。 */
export function controlsForType(type: FormFieldSpecView['type']): ControlValue[] {
  return CONTROL_OPTIONS.filter((option) => option.type === type).map(
    (option) => option.value,
  );
}

/** 把冻结规格转换为业务编辑行；内部身份保持原值。 */
export function fieldRowsFromSpec(spec: FormTemplateSpecView): FieldRow[] {
  return spec.fields.map((field) => ({
    name: field.name,
    apiKey: field.apiKey,
    column: String(field.storage?.column ?? field.storage?.idColumn ?? ''),
    columnType: String(field.storage?.columnType ?? ''),
    nameColumn: String(field.storage?.nameColumn ?? ''),
    label: field.label,
    control: field.control ?? defaultControl(field.type),
    type: field.type,
    required: Boolean(field.required),
    maxLength: field.maxLength ?? null,
    options: (field.options ?? []).map((option) => ({ ...option })),
    dictionary: field.dictionary ?? null,
    isNew: false,
  }));
}

/**
 * 将业务编辑行写回冻结规格，保留条件、词典与人员双列绑定。
 *
 * @param spec 当前基础规格（不会被修改）
 * @param rows 业务编辑行，顺序即显示顺序
 * @returns 新规格；新增行由系统生成物理列与内部键
 */
export function applyFieldRows(
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): FormTemplateSpecView {
  const original = new Map(spec.fields.map((field) => [field.name, field]));
  const fields: FormFieldSpecView[] = rows.map((row) => {
    const previous = original.get(row.name);
    const field: FormFieldSpecView = {
      ...(previous ?? {}),
      name: row.name,
      apiKey: row.apiKey,
      label: row.label,
      type: row.type,
      required: row.required,
      control: row.control,
      maxLength: row.type === 'string' ? (row.maxLength ?? undefined) : undefined,
      storage: storageFor(previous, row),
    };
    if (row.type === 'integer') {
      field.min = previous?.min ?? 0;
      field.max = previous?.max ?? 2147483647;
    } else {
      delete field.min;
      delete field.max;
    }
    if (row.type === 'enum' || row.type === 'enum-array') {
      if (row.dictionary) {
        delete field.options;
      } else {
        field.options = row.options.map((option) => ({ ...option }));
      }
    } else {
      delete field.options;
      delete field.dictionary;
    }
    if (row.control === 'person-select' && !row.nameColumn) {
      throw new Error(`人员选择字段「${row.label}」缺少姓名列`);
    }
    return field;
  });
  return { ...spec, fields };
}

function storageFor(
  previous: FormFieldSpecView | undefined,
  row: FieldRow,
): Record<string, unknown> {
  if (previous?.storage) {
    return {
      ...previous.storage,
      ...(row.control === 'person-select' ? { nameColumn: row.nameColumn } : {}),
    };
  }
  if (row.control === 'person-select') {
    return {
      idColumn: row.column,
      nameColumn: row.nameColumn,
      columnType: 'varchar(128)',
      nameColumnType: 'varchar(128)',
    };
  }
  return { column: row.column, columnType: row.columnType };
}

function defaultControl(type: FormFieldSpecView['type']): string {
  switch (type) {
    case 'integer':
      return 'number';
    case 'boolean':
      return 'switch';
    case 'date':
      return 'date';
    case 'enum':
      return 'radio';
    case 'enum-array':
      return 'checkbox-group';
    default:
      return 'input';
  }
}

/** 移动字段行；越界返回原数组副本。 */
export function moveFieldRow(rows: FieldRow[], index: number, delta: number): FieldRow[] {
  const target = index + delta;
  if (index < 0 || index >= rows.length || target < 0 || target >= rows.length) {
    return [...rows];
  }
  const next = [...rows];
  const [row] = next.splice(index, 1);
  next.splice(target, 0, row);
  return next;
}

/**
 * 生成字段/选项的稳定身份后缀。
 *
 * <p>身份一旦持久化就不能复用：删除字段或选项不会释放其键与存储列，否则新增项会继承
 * 已删除项的历史值。使用随机后缀而不是序号，跨保存、刷新和不同会话都不会回退到旧身份。
 */
function stableIdentitySuffix(): string {
  const uuid = globalThis.crypto?.randomUUID?.();
  const hex = (uuid ?? `${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`)
    .replace(/[^0-9a-f]/g, '')
    .slice(0, 12)
    .padEnd(12, '0');
  return hex;
}

/** 生成不与现有字段冲突的新字段行；内部键与列由系统自动分配且永不复用。 */
export function createFieldRow(rows: FieldRow[]): FieldRow {
  const used = new Set(rows.map((row) => row.name));
  let name = `f_${stableIdentitySuffix()}`;
  while (used.has(name)) {
    name = `f_${stableIdentitySuffix()}`;
  }
  const identity = name.slice(2);
  return {
    name,
    apiKey: `f${identity}`,
    column: name,
    columnType: TYPE_COLUMN.string,
    nameColumn: '',
    label: `新字段 ${rows.length + 1}`,
    control: 'input',
    type: 'string',
    required: false,
    maxLength: 200,
    options: [],
    dictionary: null,
    isNew: true,
  };
}

/** 切换填写方式；新增字段同步生成匹配的存储类型，既有字段保持原列类型。 */
export function changeRowControl(row: FieldRow, control: string): FieldRow {
  const meta = CONTROL_BY_VALUE.get(control as ControlValue);
  if (!meta) {
    return row;
  }
  if (!row.isNew && meta.type !== row.type) {
    return row;
  }
  if (row.isNew) {
    return {
      ...row,
      control,
      type: meta.type,
      columnType: TYPE_COLUMN[meta.type],
      maxLength: meta.type === 'string' ? 200 : null,
      nameColumn: control === 'person-select' ? `${row.column}_name` : '',
    };
  }
  return {
    ...row,
    control,
    nameColumn:
      control === 'person-select' && !row.nameColumn ? `${row.column}_name` : row.nameColumn,
  };
}

/** 新增一个选项；既有选项值不可变，新选项使用永不复用的稳定值。 */
export function addOption(rows: FieldRow[], index: number): FieldRow[] {
  const next = [...rows];
  const row = next[index];
  const used = new Set(row.options.map((option) => option.value));
  let value = `OPT_${stableIdentitySuffix().toUpperCase()}`;
  while (used.has(value)) {
    value = `OPT_${stableIdentitySuffix().toUpperCase()}`;
  }
  next[index] = {
    ...row,
    options: [...row.options, { value, label: `选项 ${row.options.length + 1}` }],
  };
  return next;
}

export function removeOption(
  rows: FieldRow[],
  rowIndex: number,
  optionIndex: number,
): FieldRow[] {
  const next = [...rows];
  const row = next[rowIndex];
  next[rowIndex] = {
    ...row,
    options: row.options.filter((_, index) => index !== optionIndex),
  };
  return next;
}

export function updateOption(
  rows: FieldRow[],
  rowIndex: number,
  optionIndex: number,
  label: string,
): FieldRow[] {
  const next = [...rows];
  const row = next[rowIndex];
  next[rowIndex] = {
    ...row,
    options: row.options.map((option, index) =>
      index === optionIndex ? { ...option, label } : option,
    ),
  };
  return next;
}

/** 字段行本地校验；服务端仍是最终判定者。 */
export function validateFieldRows(rows: FieldRow[]): string[] {
  const errors: string[] = [];
  const names = new Set<string>();
  const columns = new Set<string>();
  const labels = new Set<string>();
  for (const row of rows) {
    if (!/^[a-z][a-z0-9_]*$/.test(row.name)) {
      errors.push(`系统字段键 ${row.name} 不合法`);
    }
    if (!/^[a-z][A-Za-z0-9]*$/.test(row.apiKey)) {
      errors.push(`系统 API 键 ${row.apiKey} 不合法`);
    }
    if (!/^[a-z][a-z0-9_]*$/.test(row.column)) {
      errors.push(`系统列名 ${row.column} 不合法`);
    }
    if (row.control === 'person-select' && !/^[a-z][a-z0-9_]*$/.test(row.nameColumn)) {
      errors.push(`人员选择字段「${row.label}」的姓名列不合法`);
    }
    if (!row.label.trim()) {
      errors.push(`字段 ${row.name} 缺少显示名称`);
    }
    if (labels.has(row.label.trim())) {
      errors.push(`显示名称重复：${row.label.trim()}`);
    }
    if (names.has(row.name)) {
      errors.push(`系统字段键重复：${row.name}`);
    }
    if (columns.has(row.column)) {
      errors.push(`系统列名重复：${row.column}`);
    }
    if ((row.type === 'enum' || row.type === 'enum-array') && !row.dictionary) {
      if (!row.options.length) {
        errors.push(`字段「${row.label}」至少需要一个选项`);
      }
      const values = new Set<string>();
      for (const option of row.options) {
        if (!option.label.trim()) {
          errors.push(`字段「${row.label}」存在空选项名称`);
        }
        if (values.has(option.value)) {
          errors.push(`字段「${row.label}」存在重复选项值`);
        }
        values.add(option.value);
      }
    }
    names.add(row.name);
    columns.add(row.column);
    labels.add(row.label.trim());
  }
  return errors;
}

/** 业务条件说明；内部字段键与条件 JSON 不出现在界面上。 */
export const RESOURCE_SEVERITY_FIELD = '__resource_severity__';
export const RESOURCE_SEVERITY_VALUES = ['一级缺陷', '二级缺陷', '三级缺陷', '建议类', '未设定'] as const;

export type ConditionRowKind = 'required' | 'mutual';
export type ConditionOperator = 'equals' | 'contains' | 'notContains';

/** 管理页可编辑的条件行；字段与选项一律用业务名称显示。 */
export interface ConditionRow {
  key: string;
  kind: ConditionRowKind;
  origin: 'field' | 'root' | 'new';
  originIndex: number;
  triggerField: string;
  operator: ConditionOperator;
  values: string[];
  target: string;
  clearWhenOff: boolean;
  conflictValues: string[];
  snapshotColumn: string | null;
  supported: boolean;
}

function stringList(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.map((item) => String(item));
  }
  if (value === null || value === undefined) {
    return [];
  }
  return [String(value)];
}

function triggerShape(
  node: unknown,
): { triggerField: string; operator: ConditionOperator; values: string[] } | null {
  if (!node || typeof node !== 'object' || Array.isArray(node)) {
    return null;
  }
  const record = node as Record<string, unknown>;
  if (typeof record.field === 'string') {
    if (typeof record.contains === 'string') {
      return { triggerField: record.field, operator: 'contains', values: [record.contains] };
    }
    if (typeof record.notContains === 'string') {
      return { triggerField: record.field, operator: 'notContains', values: [record.notContains] };
    }
    if ('equals' in record) {
      return {
        triggerField: record.field,
        operator: 'equals',
        values: stringList(record.equals),
      };
    }
    return null;
  }
  if ('resourceSeverityLevel' in record) {
    return {
      triggerField: RESOURCE_SEVERITY_FIELD,
      operator: 'equals',
      values: stringList(record.resourceSeverityLevel),
    };
  }
  return null;
}

function conditionSignature(row: ConditionRow): string {
  return [row.kind, row.triggerField, row.operator, row.values.join('\u0001'), row.target].join('|');
}

/**
 * 从冻结规格提取可编辑条件；字段级与根级重复条件按同一业务条件合并。
 *
 * <p>真实种子中字段级`requiredWhen`与根级`conditionalRequired`表达同一规则，编辑器只展示一份，
 * 保存时统一写回根级条件，避免两处规则漂移。
 */
export function conditionRowsFromSpec(
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): ConditionRow[] {
  const fieldNames = new Set(rows.map((row) => row.name));
  const result: ConditionRow[] = [];
  const seen = new Set<string>();
  const push = (row: ConditionRow) => {
    const signature = conditionSignature(row);
    if (seen.has(signature)) {
      return;
    }
    seen.add(signature);
    result.push(row);
  };
  (spec.conditions ?? []).forEach((condition, index) => {
    if (!condition || typeof condition !== 'object') {
      return;
    }
    if (condition.type === 'conditionalRequired') {
      const shape = triggerShape(condition.requiredWhen);
      const target = String(condition.target ?? '');
      if (!shape || !fieldNames.has(target)) {
        return;
      }
      push({
        key: `root-${index}`,
        kind: 'required',
        origin: 'root',
        originIndex: index,
        triggerField: shape.triggerField,
        operator: shape.operator,
        values: shape.values,
        target,
        clearWhenOff: Boolean(condition.clearWhen && triggerShape(condition.clearWhen)),
        conflictValues: [],
        snapshotColumn:
          typeof condition.snapshotColumn === 'string' ? condition.snapshotColumn : null,
        supported: true,
      });
      return;
    }
    if (condition.type === 'mutuallyExclusive') {
      const field = String(condition.field ?? '');
      const conflictValues = stringList(condition.withAnyOf);
      if (!fieldNames.has(field) || !condition.whenContains || !conflictValues.length) {
        return;
      }
      push({
        key: `root-${index}`,
        kind: 'mutual',
        origin: 'root',
        originIndex: index,
        triggerField: field,
        operator: 'contains',
        values: [String(condition.whenContains)],
        target: '',
        clearWhenOff: false,
        conflictValues,
        snapshotColumn: null,
        supported: true,
      });
    }
  });
  spec.fields.forEach((field, index) => {
    const shape = triggerShape(field.requiredWhen);
    if (!shape) {
      return;
    }
    push({
      key: `field-${index}`,
      kind: 'required',
      origin: 'field',
      originIndex: index,
      triggerField: shape.triggerField,
      operator: shape.operator,
      values: shape.values,
      target: field.name,
      clearWhenOff: Boolean(field.clearWhen && triggerShape(field.clearWhen)),
      conflictValues: [],
      snapshotColumn: null,
      supported: true,
    });
  });
  return result;
}

/** 是否存在当前界面不支持编辑、但保存时必须原样保留的条件。 */
export function hasUnsupportedConditions(spec: FormTemplateSpecView): boolean {
  const supportedTypes = new Set(['conditionalRequired', 'mutuallyExclusive']);
  const unsupportedRoot = (spec.conditions ?? []).some((condition) => {
    if (!condition || !condition.type) {
      return true;
    }
    if (!supportedTypes.has(condition.type)) {
      return true;
    }
    if (condition.type === 'conditionalRequired') {
      return !triggerShape(condition.requiredWhen) || !condition.target;
    }
    return (
      typeof condition.field !== 'string'
      || !condition.whenContains
      || !stringList(condition.withAnyOf).length
    );
  });
  const unsupportedField = spec.fields.some(
    (field) =>
      (field.requiredWhen && !triggerShape(field.requiredWhen))
      || (field.clearWhen && !triggerShape(field.clearWhen)),
  );
  return unsupportedRoot || unsupportedField;
}

function buildRequiredWhen(row: ConditionRow): Record<string, unknown> {
  const value = row.values[0] ?? '';
  if (row.triggerField === RESOURCE_SEVERITY_FIELD) {
    return row.values.length > 1
      ? { resourceSeverityLevel: row.values }
      : { resourceSeverityLevel: value };
  }
  if (row.operator === 'contains') {
    return { field: row.triggerField, contains: value };
  }
  if (row.operator === 'notContains') {
    return { field: row.triggerField, notContains: value };
  }
  const booleanField = value === 'true' || value === 'false';
  return { field: row.triggerField, equals: booleanField ? value === 'true' : value };
}

function buildClearWhen(
  row: ConditionRow,
  spec: FormTemplateSpecView,
): Record<string, unknown> | null {
  if (row.triggerField === RESOURCE_SEVERITY_FIELD) {
    const remaining = RESOURCE_SEVERITY_VALUES.filter((value) => !row.values.includes(value));
    return remaining.length ? { resourceSeverityLevel: remaining } : null;
  }
  const field = spec.fields.find((item) => item.name === row.triggerField);
  if (!field) {
    return null;
  }
  if (row.operator === 'contains') {
    return { field: row.triggerField, notContains: row.values[0] ?? '' };
  }
  if (row.operator === 'equals' && field.type === 'boolean') {
    return { field: row.triggerField, equals: row.values[0] !== 'true' };
  }
  return null;
}

/** 是否可为该条件配置“否则自动清空”。 */
export function canClearWhen(row: ConditionRow, spec: FormTemplateSpecView): boolean {
  if (row.kind !== 'required') {
    return false;
  }
  if (row.triggerField === RESOURCE_SEVERITY_FIELD || row.operator === 'contains') {
    return true;
  }
  const field = spec.fields.find((item) => item.name === row.triggerField);
  return Boolean(field && field.type === 'boolean' && row.operator === 'equals');
}

/**
 * 将编辑行写回规格。
 *
 * <p>保留未支持类型；字段级条件统一写回根级`conditionalRequired`，避免同一规则在字段与根级各存一份。
 */
export function applyConditionRows(
  spec: FormTemplateSpecView,
  rows: ConditionRow[],
): FormTemplateSpecView {
  const fields = spec.fields.map((field) => {
    if (!triggerShape(field.requiredWhen) && !triggerShape(field.clearWhen)) {
      return field;
    }
    const next = { ...field };
    delete next.requiredWhen;
    delete next.clearWhen;
    return next;
  });
  const kept: FormSpecConditionView[] = [];
  (spec.conditions ?? []).forEach((condition) => {
    if (!condition || !condition.type) {
      return;
    }
    if (condition.type === 'conditionalRequired') {
      if (!triggerShape(condition.requiredWhen) || !condition.target) {
        kept.push(condition);
      }
      return;
    }
    if (condition.type === 'mutuallyExclusive') {
      if (
        typeof condition.field !== 'string'
        || !condition.whenContains
        || !stringList(condition.withAnyOf).length
      ) {
        kept.push(condition);
      }
      return;
    }
    kept.push(condition);
  });
  const built: FormSpecConditionView[] = [];
  rows.forEach((row) => {
    if (!row.supported) {
      return;
    }
    if (row.kind === 'mutual') {
      built.push({
        type: 'mutuallyExclusive',
        field: row.triggerField,
        whenContains: row.values[0] ?? '',
        withAnyOf: [...row.conflictValues],
      });
      return;
    }
    const condition: FormSpecConditionView = {
      type: 'conditionalRequired',
      target: row.target,
      requiredWhen: buildRequiredWhen(row),
    };
    const clearWhen = row.clearWhenOff ? buildClearWhen(row, spec) : null;
    if (clearWhen) {
      condition.clearWhen = clearWhen;
    }
    if (row.snapshotColumn) {
      condition.snapshotColumn = row.snapshotColumn;
    }
    built.push(condition);
  });
  return { ...spec, fields, conditions: [...kept, ...built] };
}

/** 条件编辑器的字段选项（业务名称）。 */
export function conditionFieldOptions(
  rows: FieldRow[],
): Array<{ value: string; label: string }> {
  return [
    ...rows.map((row) => ({ value: row.name, label: row.label || row.name })),
    { value: RESOURCE_SEVERITY_FIELD, label: '议题严重级别' },
  ];
}

/** 条件可用的比较方式；由触发字段类型决定。 */
export function conditionOperators(
  row: ConditionRow,
  spec: FormTemplateSpecView,
): Array<{ value: ConditionOperator; label: string }> {
  if (row.triggerField === RESOURCE_SEVERITY_FIELD) {
    return [{ value: 'equals', label: '为' }];
  }
  const field = spec.fields.find((item) => item.name === row.triggerField);
  if (!field || field.type === 'boolean') {
    return [{ value: 'equals', label: '为' }];
  }
  if (field.type === 'enum-array') {
    return [
      { value: 'contains', label: '包含' },
      { value: 'notContains', label: '不包含' },
    ];
  }
  return [{ value: 'equals', label: '为' }];
}

/** 条件取值选项（选项/词典/布尔/严重级别），全部为业务名称。 */
export function conditionValueOptions(
  row: ConditionRow,
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): Array<{ value: string; label: string }> {
  if (row.triggerField === RESOURCE_SEVERITY_FIELD) {
    return RESOURCE_SEVERITY_VALUES.map((value) => ({ value, label: value }));
  }
  const rowField = rows.find((item) => item.name === row.triggerField);
  const field = spec.fields.find((item) => item.name === row.triggerField);
  if (!field) {
    return [];
  }
  if (field.type === 'boolean') {
    return [
      { value: 'true', label: '是' },
      { value: 'false', label: '否' },
    ];
  }
  if (rowField?.options.length) {
    return rowField.options.map((option) => ({ value: option.value, label: option.label }));
  }
  if (field.options?.length) {
    return field.options.map((option) => ({ value: option.value, label: option.label }));
  }
  const dictionary: FormDictionaryView | undefined = field.dictionary
    ? spec.dictionaries?.find((item) => item.name === field.dictionary)
    : undefined;
  return (dictionary?.items ?? []).map((item) => ({ value: item.key, label: item.label }));
}

function labelOfField(name: string, rows: FieldRow[]): string {
  if (name === RESOURCE_SEVERITY_FIELD) {
    return '议题严重级别';
  }
  return rows.find((row) => row.name === name)?.label || '未命名字段';
}

function labelOfValue(
  row: ConditionRow,
  value: string,
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): string {
  return (
    conditionValueOptions(row, spec, rows).find((option) => option.value === value)?.label
    ?? (row.triggerField === RESOURCE_SEVERITY_FIELD ? value : '已删除或未知选项')
  );
}

/** 业务化条件说明；不输出内部字段键或原始 JSON 键。 */
export function describeConditionRow(
  row: ConditionRow,
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): string {
  if (!row.supported) {
    return '存在暂不支持在此编辑的条件规则，请在发布预览中核对。';
  }
  const trigger = `「${labelOfField(row.triggerField, rows)}」`;
  const values = row.values
    .map((value) => `「${labelOfValue(row, value, spec, rows)}」`)
    .join('、');
  if (row.kind === 'mutual') {
    const conflicts = row.conflictValues
      .map((value) => `「${labelOfValue(row, value, spec, rows)}」`)
      .join('、');
    return `当${trigger}选择${values}时，不能同时选择${conflicts}。`;
  }
  const operator =
    row.operator === 'contains' ? '包含' : row.operator === 'notContains' ? '不包含' : '为';
  const clear = row.clearWhenOff ? '；否则自动清空' : '';
  return `当${trigger}${operator}${values}时，「${labelOfField(row.target, rows)}」必填${clear}。`;
}

/** 新增一条业务条件；触发字段与取值优先选择有选项的字段。 */
export function createConditionRow(
  rows: FieldRow[],
  spec: FormTemplateSpecView,
  kind: ConditionRowKind,
): ConditionRow {
  const triggerRow =
    rows.find(
      (row) => row.type === 'enum-array' || row.type === 'enum' || row.type === 'boolean',
    ) ?? rows[0];
  const targetRow = rows.find((row) => row.name !== triggerRow?.name) ?? triggerRow;
  const base: ConditionRow = {
    key: `new-${stableIdentitySuffix()}`,
    kind,
    origin: 'new',
    originIndex: -1,
    triggerField: triggerRow?.name ?? '',
    operator: triggerRow?.type === 'enum-array' ? 'contains' : 'equals',
    values: [],
    target: targetRow?.name ?? '',
    clearWhenOff: false,
    conflictValues: [],
    snapshotColumn: null,
    supported: true,
  };
  const valueOptions = triggerRow ? conditionValueOptions(base, spec, rows) : [];
  if (kind === 'mutual') {
    return {
      ...base,
      target: '',
      values: valueOptions.length ? [valueOptions[0].value] : [],
      conflictValues: valueOptions.length > 1 ? [valueOptions[1].value] : [],
    };
  }
  return {
    ...base,
    values: valueOptions.length ? [valueOptions[0].value] : [],
  };
}

/** 条件说明列表；兼容旧调用方，未知条件只给业务提示。 */
export function conditionSummaries(
  spec: FormTemplateSpecView,
  rows: FieldRow[],
): Array<{ key: string; text: string }> {
  const summaries = conditionRowsFromSpec(spec, rows).map((row) => ({
    key: row.key,
    text: describeConditionRow(row, spec, rows),
  }));
  if (hasUnsupportedConditions(spec)) {
    summaries.push({
      key: 'unsupported',
      text: '存在暂不支持在此编辑的条件规则，请在发布预览中核对。',
    });
  }
  return summaries;
}

/** 预览中的业务变化行；工程字段（列、SQL）只作折叠证据。 */
export interface BusinessChangeRow {
  key: string;
  fieldLabel: string;
  changeLabel: string;
  before: string;
  after: string;
  history: string;
}

export function businessChanges(preview: FormAdminPreviewView): BusinessChangeRow[] {
  const rows = preview.diff?.fields ?? [];
  return rows.map((raw, index) => mapBusinessChange(raw, index));
}

function mapBusinessChange(raw: Record<string, unknown>, index: number): BusinessChangeRow {
  const change = String(raw.change ?? '');
  const oldLabel = text(raw.oldLabel);
  const newLabel = text(raw.newLabel);
  const fieldLabel = newLabel || oldLabel || '未命名字段';
  const changes = Array.isArray(raw.changes)
    ? raw.changes.map(String)
    : [change];
  if (change === 'ADDED') {
    return {
      key: `change-${index}`,
      fieldLabel,
      changeLabel: '新增',
      before: '—',
      after: describeField(raw, 'new'),
      history: '历史记录在该字段上为空，并按迁移规则标记为待补充，不自动补默认值。',
    };
  }
  if (change === 'REMOVED') {
    return {
      key: `change-${index}`,
      fieldLabel,
      changeLabel: '删除',
      before: describeField(raw, 'old'),
      after: '不再显示',
      history: '新版本不再读取该字段；历史版本仍保留原值。',
    };
  }
  const labels = changes.map(changeLabelOf);
  const history = new Set<string>();
  if (changes.includes('LABEL')) {
    history.add('仅调整显示名称，字段身份与历史值不变。');
  }
  if (changes.includes('ADDED') || changes.includes('REMOVED')) {
    history.add('字段结构调整后，历史记录按新增/删除规则处理。');
  }
  if (changes.some((item) => item === 'OPTIONS' || item === 'CONTROL')) {
    history.add('历史记录保留原值；已删除的选项不可再选。');
  }
  if (changes.includes('REQUIRED')) {
    history.add(
      raw.newRequired
        ? '历史缺值记录将标记为待补充，不自动补默认值。'
        : '历史记录保持原值，后续填写不再强制。',
    );
  }
  if (!history.size) {
    history.add('历史记录保留原值，字段身份不变。');
  }
  return {
    key: `change-${index}`,
    fieldLabel,
    changeLabel: labels.length ? labels.join('、') : '调整',
    before: describeField(raw, 'old'),
    after: describeField(raw, 'new'),
    history: [...history].join(' '),
  };
}

function changeLabelOf(kind: string): string {
  switch (kind) {
    case 'LABEL':
      return '名称调整';
    case 'CONTROL':
      return '填写方式调整';
    case 'REQUIRED':
      return '必填调整';
    case 'OPTIONS':
      return '选项调整';
    case 'TYPE':
      return '数据类型调整';
    case 'COLUMN':
      return '存储调整';
    default:
      return '调整';
  }
}

function describeField(raw: Record<string, unknown>, side: 'old' | 'new'): string {
  const parts: string[] = [];
  const label = text(raw[`${side}Label`]);
  if (label) {
    parts.push(`名称「${label}」`);
  }
  const control = text(raw[`${side}Control`]);
  if (control) {
    parts.push(`填写方式「${controlLabel(control)}」`);
  }
  if (raw[`${side}Required`] !== undefined) {
    parts.push(raw[`${side}Required`] ? '必填' : '选填');
  }
  const options = raw[`${side}Options`];
  if (Array.isArray(options) && options.length) {
    parts.push(
      `选项「${options
        .map((option) => String((option as Record<string, unknown>).label ?? ''))
        .join(' / ')}」`,
    );
  }
  return parts.length ? parts.join('，') : '—';
}

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value);
}

/** 业务预览中的迁移影响说明；不展示 SQL。 */
export function historyImpactText(preview: FormAdminPreviewView): string {
  const records = Number(preview.impact?.records ?? 0);
  const kind = String(preview.publicationKind ?? '');
  if (kind === 'INITIAL_MIGRATION') {
    return `本次为旧表首次迁移，将纳入 ${records} 条历史记录；缺失的新必填字段会标记为待补充。`;
  }
  if (kind === 'INITIAL_EMPTY') {
    return '本次为首次启用，当前没有历史记录需要迁移。';
  }
  if (records === 0) {
    return '当前版本没有历史记录，本次结构调整不产生数据迁移。';
  }
  return `本次将迁移 ${records} 条历史记录；字段身份保持不变时保留原值，删除或新增字段按业务变化说明处理。`;
}

/** 条件 JSON 仅供只读检查；确认未被界面编辑。 */
export function conditionCount(spec: FormTemplateSpecView): number {
  return (spec.conditions ?? []).filter(
    (condition: FormSpecConditionView) => condition && condition.type,
  ).length;
}
