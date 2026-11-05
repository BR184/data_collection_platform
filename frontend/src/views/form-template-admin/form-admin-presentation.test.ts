import { describe, expect, it } from 'vitest';
import type { FormAdminPreviewView, FormTemplateSpecView } from '../../types/api';
import { comparisonSpec, fieldChanges, formTitle, publicationFailure } from './form-admin-presentation';

const base: FormTemplateSpecView = { spec_version: 1, template_key: 'issue_reply', fields: [{ name: 'note', apiKey: 'note', label: '补充说明', type: 'string', control: 'textarea', required: false }] };

describe('管理页业务变化投影', () => {
  it('returns just the changed name', () => {
    const next = { ...base, fields: [{ ...base.fields[0], label: '备注' }] };
    expect(fieldChanges(base, next).map(change => change.text)).toEqual(['补充说明：名称改为「备注」']);
  });
  it('does not display empty categories for unchanged content', () => { expect(fieldChanges(base, base)).toEqual([]); });
  it('keeps the published comparison baseline after a restored rename is reverted', () => {
    const edited = { ...base, fields: [{ ...base.fields[0], label: '备注' }] };
    const prior: FormAdminPreviewView = { id: 1, draftRevision: 2, targetVersionId: 3, previewHash: 'hash', publicationKind: 'MIGRATION', diff: { fields: [{ name: 'note', change: 'LABEL', oldLabel: '补充说明' }] }, ddlSummary: [], ddlHash: 'hash', impact: {}, blockingErrors: [], createdAt: '2026-10-27T00:00:00Z' };
    const restored = comparisonSpec(edited, prior);
    expect(fieldChanges(restored, base)).toEqual([]);
  });
  it('describes field removal with its actual effect', () => { expect(fieldChanges(base, { ...base, fields: [] })[0].text).toBe('补充说明：删除，发布后从当前表单移除'); });
  it('uses the business names of all three templates', () => { expect(['code_review','issue_response','issue_reply'].map(formTitle)).toEqual(['走查表','Issue 响应','Issue 回复']); });
  it('interprets a failed connection without exposing the backend log', () => { expect(publicationFailure('org.postgresql connection timeout')).toBe('连接暂时中断，修改已保留'); });
  it('describes the business values before and after a condition changes', () => {
    const before: FormTemplateSpecView = { ...base, fields: [...base.fields, { name: 'state', apiKey: 'state', label: '处理状态', type: 'enum', control: 'radio', options: [{ value: 'A', label: '处理中' }, { value: 'B', label: '已解决' }] }], conditions: [{ type: 'conditionalRequired', target: 'note', requiredWhen: { field: 'state', equals: 'A' } }] };
    const after = { ...before, conditions: [{ type: 'conditionalRequired', target: 'note', requiredWhen: { field: 'state', equals: 'B' } }] };
    const text = fieldChanges(before, after).map(item => item.text).join('');
    expect(text).toContain('处理中'); expect(text).toContain('已解决'); expect(text).toContain('补充说明'); expect(text).not.toContain('note');
  });
});
