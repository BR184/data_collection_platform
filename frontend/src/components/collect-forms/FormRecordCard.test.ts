import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';
import type { FormRecordView, FormTemplateSpecView } from '../../types/api';
import FormRecordCard from './FormRecordCard.vue';

const spec: FormTemplateSpecView = {
  spec_version: 1, template_key: 'custom_template', fields: [
    { name: 'amount', apiKey: 'amount', label: '数量', type: 'integer', required: true },
    { name: 'owner', apiKey: 'owner', label: '负责人', type: 'string', control: 'person-select' },
    { name: 'decision', apiKey: 'decision', label: '是否确认', type: 'boolean' },
    { name: 'note', apiKey: 'note', label: '说明', type: 'string', control: 'textarea' },
    { name: 'extra', apiKey: 'extra', label: '备注', type: 'string', control: 'text' },
  ],
};
const record: FormRecordView = {
  id: '9007199254740993', recordVersion: 1, deleted: false, legacyIncomplete: false,
  legacyMissingFields: [], fields: { amount: 0, owner: 'alice', decision: false, note: '第一行\n第二行', extra: null },
  fieldNames: { owner: '张明' }, snapshotValues: {}, createdBy: 'alice', createdByName: '张明',
  templateVersion: '2026.10', originVersionId: '1', storageVersionId: '1',
  createdAt: '2026-10-28T07:25:43', updatedAt: '2026-10-28T07:25:43', canEdit: true, canVoid: true,
};
function render(overrides: Partial<FormRecordView> = {}, writable = true) {
  return mount(FormRecordCard, {
    props: { spec, record: { ...record, ...overrides }, templateTitle: '不重复的表单名称', latest: false, editing: false, writable },
    global: { plugins: [ElementPlus] },
  });
}

describe('FormRecordCard 展示', () => {
  it('uses the author as heading and keeps record/version identity without repeating the template title', () => {
    const wrapper = render();
    expect(wrapper.find('.form-record-author').text()).toBe('张明');
    expect(wrapper.find('.form-record-id').text()).toContain(record.id);
    expect(wrapper.find('.form-record-version').text()).toBe('2026.10');
    expect(wrapper.text()).not.toContain('不重复的表单名称');
    expect(wrapper.attributes('data-record-id')).toBe(record.id);
  });
  it('groups arbitrary integer fields and preserves zero and false as filled values', () => {
    const wrapper = render();
    expect(wrapper.find('.form-record-numbers [data-display-field="amount"]').text()).toContain('0');
    expect(wrapper.find('.form-record-short-fields [data-display-field="decision"]').text()).toContain('否');
    expect(wrapper.find('.form-record-empty-fields').text()).toBe('未填写：备注');
  });
  it('keeps person names and all empty optional fields visible in the summary', () => {
    const wrapper = render({ fields: { amount: 1, owner: 'alice', decision: true, note: '', extra: null } });
    expect(wrapper.find('[data-display-field="owner"]').text()).toContain('张明');
    expect(wrapper.find('.form-record-empty-fields').text()).toBe('未填写：说明、备注');
    expect(wrapper.find('[data-display-field="extra"]').exists()).toBe(false);
  });
  it('keeps missing required values in their field group', () => {
    const wrapper = render({ fields: { ...record.fields, amount: null } });
    expect(wrapper.find('[data-display-field="amount"]').text()).toContain('—');
    expect(wrapper.find('.form-record-empty-fields').text()).not.toContain('数量');
  });
  it('shows identical timestamps once and retains both full timestamps in the tooltip', () => {
    const wrapper = render();
    expect(wrapper.find('.form-record-time').text()).toBe('10/28 07:25');
    const title = wrapper.find('.form-record-time').attributes('title');
    expect(title).toContain('创建：'); expect(title).toContain('更新：'); expect(title).toContain('2026');
  });
  it('shows changed update time without losing creation time', () => {
    const wrapper = render({ updatedAt: '2026-10-28T08:30:00' });
    expect(wrapper.find('.form-record-time').text()).toBe('10/28 07:25 · 更新 10/28 08:30');
  });
  it('expands long text without changing the record payload', async () => {
    const note = '第一段\n第二段\n第三段\n第四段';
    const wrapper = render({ fields: { ...record.fields, note } });
    expect(wrapper.find('.form-record-long .form-record-value').classes()).toContain('is-collapsed');
    await wrapper.find('.form-record-expand').trigger('click');
    expect(wrapper.find('.form-record-long .form-record-value').classes()).not.toContain('is-collapsed');
    expect(wrapper.find('.form-record-expand').attributes('aria-expanded')).toBe('true');
    expect(wrapper.props('record').fields.note).toBe(note);
  });
  it('preserves permission gates and separate edit/void events', async () => {
    const wrapper = render();
    await wrapper.find('.el-button--primary').trigger('click');
    await wrapper.find('.el-button--danger').trigger('click');
    expect(wrapper.emitted('edit')).toHaveLength(1); expect(wrapper.emitted('void')).toHaveLength(1);
    expect(render({}, false).find('.form-record-actions button').exists()).toBe(false);
    expect(render({ canEdit: false, canVoid: false }).find('.form-record-actions button').exists()).toBe(false);
  });
});
