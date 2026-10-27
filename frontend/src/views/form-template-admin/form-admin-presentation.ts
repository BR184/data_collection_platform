import type { FormAdminPreviewView, FormFieldSpecView, FormTemplateSpecView } from '../../types/api';
import { controlLabel, conditionRowsFromSpec, describeConditionRow, fieldRowsFromSpec } from './form-template-admin';

export interface FieldChange {
  key: string;
  name: string;
  label: string;
  kind: 'added' | 'changed' | 'removed';
  text: string;
}

/** 模板的内部身份只用于映射，界面始终使用业务名称。 */
export function formTitle(key: string): string {
  return ({ code_review: '走查表', issue_response: 'Issue 响应', issue_reply: 'Issue 回复' } as Record<string, string>)[key] ?? '表单';
}

/** 由真实字段差异生成简短变化，不输出物理字段或未变化的属性。 */
export function fieldChanges(
  baseline: FormTemplateSpecView | null,
  current: FormTemplateSpecView | null,
  prior: FormAdminPreviewView | null = null,
): FieldChange[] {
  if (!current || !baseline) return [];
  const originals = new Map(comparisonSpec(baseline, prior).fields.map(field => [field.name, field]));
  const changes: FieldChange[] = [];
  return compareFields(baseline, current, originals, changes);
}

/** 从预览保留的修改前内容恢复比较基准；仅供展示，不作为写入规格。 */
export function comparisonSpec(baseline: FormTemplateSpecView, prior: FormAdminPreviewView | null): FormTemplateSpecView {
  const originals = new Map(baseline.fields.map(field => [field.name, { ...field }]));
  for (const raw of prior?.diff.fields ?? []) {
    const name = String(raw.name ?? '');
    if (!name) continue;
    if (raw.change === 'ADDED') { originals.delete(name); continue; }
    const source = originals.get(name);
    if (source || raw.oldLabel) {
      originals.set(name, {
        ...(source ?? { name, apiKey: name, type: 'string' as const, label: '字段' }),
        ...(raw.oldLabel ? { label: String(raw.oldLabel) } : {}),
        ...(raw.oldControl ? { control: String(raw.oldControl) } : {}),
        ...(typeof raw.oldRequired === 'boolean' ? { required: raw.oldRequired } : {}),
        ...(Array.isArray(raw.oldOptions) ? { options: raw.oldOptions as FormFieldSpecView['options'] } : {}),
      });
    }
  }
  return { ...baseline, fields: [...originals.values()] };
}

function compareFields(baseline: FormTemplateSpecView, current: FormTemplateSpecView, originals: Map<string, FormFieldSpecView>, changes: FieldChange[]): FieldChange[] {
  const push = (field: FormFieldSpecView, kind: FieldChange['kind'], part: string, text: string) => {
    changes.push({ key: `${field.name}:${part}`, name: field.name, label: field.label, kind, text: `${field.label}：${text}` });
  };
  for (const field of current.fields) {
    const old = originals.get(field.name);
    if (!old) { push(field, 'added', 'add', `新增${controlLabel(field.control)}字段`); continue; }
    if (old.label !== field.label) {
      changes.push({ key: `${field.name}:label`, name: field.name, label: field.label, kind: 'changed', text: `${old.label}：名称改为「${field.label}」` });
    }
    if (old.control !== field.control) push(field, 'changed', 'control', `由${controlLabel(old.control)}改为${controlLabel(field.control)}`);
    if (Boolean(old.required) !== Boolean(field.required)) push(field, 'changed', 'required', field.required ? '改为必填' : '改为选填');
    const oldOptions = new Map((old.options ?? []).map(option => [option.value, option.label]));
    const newOptions = new Map((field.options ?? []).map(option => [option.value, option.label]));
    const added = [...newOptions].filter(([value]) => !oldOptions.has(value)).map(([, label]) => label);
    const removed = [...oldOptions].filter(([value]) => !newOptions.has(value)).map(([, label]) => label);
    const renamed = [...newOptions].filter(([value, label]) => oldOptions.has(value) && oldOptions.get(value) !== label).map(([value, label]) => `「${oldOptions.get(value)}」改为「${label}」`);
    if (added.length) push(field, 'changed', 'options-add', `增加选项${added.map(label => `「${label}」`).join('、')}`);
    if (removed.length) push(field, 'changed', 'options-remove', `移除选项${removed.map(label => `「${label}」`).join('、')}`);
    if (renamed.length) push(field, 'changed', 'options-label', renamed.join('、'));
  }
  for (const field of originals.values()) {
    if (!current.fields.some(item => item.name === field.name)) push(field, 'removed', 'remove', '删除，发布后从当前表单移除');
  }
  const commonOld = baseline.fields.filter(field => current.fields.some(item => item.name === field.name)).map(field => field.name);
  const commonNew = current.fields.filter(field => baseline.fields.some(item => item.name === field.name)).map(field => field.name);
  if (JSON.stringify(commonOld) !== JSON.stringify(commonNew)) {
    changes.push({ key: 'order', name: '', label: '字段顺序', kind: 'changed', text: '字段顺序：已重新排列' });
  }
  const describeRules = (spec: FormTemplateSpecView) => {
    const rows = fieldRowsFromSpec(spec);
    const occurrences = new Map<string, number>();
    return new Map(conditionRowsFromSpec(spec, rows).map(rule => {
      const identity = `${rule.kind}:${rule.target}:${rule.triggerField}`;
      const index = occurrences.get(identity) ?? 0;
      occurrences.set(identity, index + 1);
      return [`${identity}:${index}`, { rule, text: describeConditionRow(rule, spec, rows) }];
    }));
  };
  const oldRules = describeRules(baseline);
  const newRules = describeRules(current);
  for (const key of new Set([...oldRules.keys(), ...newRules.keys()])) {
    const old = oldRules.get(key); const next = newRules.get(key);
    // 显示名称变化不构成条件变化；比较实际条件身份与取值。
    const signature = (entry: typeof old) => entry ? JSON.stringify([entry.rule.operator, entry.rule.values, entry.rule.conflictValues, entry.rule.clearWhenOff]) : '';
    if (signature(old) === signature(next)) continue;
    changes.push({ key: `condition:${key}`, name: next?.rule.target || old?.rule.target || next?.rule.triggerField || '', label: '填写条件', kind: 'changed',
      text: old && next ? `填写条件：由“${old.text}”改为“${next.text}”` : next ? `新增条件：${next.text}` : `移除条件：${old!.text}` });
  }
  return changes;
}

/** 发布失败只给可行动的业务原因，不输出服务端工程日志。 */
export function publicationFailure(message: string | null): string {
  if (!message) return '暂时未能完成发布';
  if (/timeout|connect|连接|超时/i.test(message)) return '连接暂时中断，修改已保留';
  if (/lease|fenc|deadlock|40P01|繁忙/i.test(message)) return '服务暂时繁忙，修改已保留';
  if (/条件|必填|选项/.test(message)) return '填写条件或选项需要检查';
  return '暂时未能完成发布';
}

/** 把接口错误中的字段身份替换为业务名称；无法安全解释时使用业务提示。 */
export function businessError(message: string, spec: FormTemplateSpecView | null, fallback: string): string {
  let result = message;
  for (const field of [...(spec?.fields ?? [])].sort((a, b) => b.name.length - a.name.length)) {
    result = result.split(field.name).join(field.label).split(field.apiKey).join(field.label);
  }
  return /\b(?:SQL|DDL|JSON|CAS|scope|storage|column|revision|request|deploy|exception)\b|[a-z]+_[a-z]+/i.test(result) ? fallback : result;
}
