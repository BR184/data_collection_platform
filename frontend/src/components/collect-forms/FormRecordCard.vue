<script setup lang="ts">
import { computed, ref } from 'vue';
import type { FormFieldSpecView, FormRecordView, FormTemplateSpecView } from '../../types/api';
import { formatFieldValue } from '../../features/collect-forms/field-logic';

// 展示约定：以填写人识别记录，按控件类型分组；不改变冻结字段与业务载荷。
const props = defineProps<{
  spec: FormTemplateSpecView;
  record: FormRecordView;
  templateTitle: string;
  latest: boolean;
  writable: boolean;
  editing: boolean;
}>();
const emit = defineEmits<{ (event: 'edit'): void; (event: 'void'): void }>();
const expandedFields = ref<Record<string, boolean>>({});
const author = computed(() => props.record.createdByName || '未知');
const authorInitial = computed(() => Array.from(author.value)[0]);
const timeTitle = computed(() => `创建：${formatDateTime(props.record.createdAt)}\n更新：${formatDateTime(props.record.updatedAt)}`);
const compactTime = computed(() => {
  const created = compactDateTime(props.record.createdAt);
  return props.record.updatedAt && props.record.updatedAt !== props.record.createdAt
    ? `${created} · 更新 ${compactDateTime(props.record.updatedAt)}` : created;
});
const emptyFields = computed(() => props.spec.fields.filter(isEmptyOptional));
const visibleFields = computed(() => props.spec.fields.filter((field) => !isEmptyOptional(field)));
const numericFields = computed(() => visibleFields.value.filter((field) => field.type === 'integer'));
const longFields = computed(() => visibleFields.value.filter((field) => field.control === 'textarea'));
const shortFields = computed(() => visibleFields.value.filter((field) => field.type !== 'integer' && field.control !== 'textarea'));

function isEmptyOptional(field: FormFieldSpecView): boolean {
  const value = props.record.fields?.[field.apiKey];
  return !field.required && (value == null || value === '' || (Array.isArray(value) && value.length === 0));
}
function canExpand(field: FormFieldSpecView): boolean {
  const value = displayValue(field);
  return value.length > 120 || value.split('\n').length > 3;
}
function formatDateTime(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}
function compactDateTime(value: string | null): string {
  if (!value) return '时间未记录';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number): string => String(part).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
function displayValue(field: FormFieldSpecView): string {
  const value = props.record.fields?.[field.apiKey];
  if (field.control === 'person-select') {
    return typeof value === 'string' && value ? props.record.fieldNames?.[field.apiKey] || value : '—';
  }
  return formatFieldValue(props.spec, field, value as never);
}
</script>

<template>
  <article class="form-record-card" :data-record-id="record.id">
    <header class="form-record-header">
      <div class="form-record-identity">
        <span class="form-record-avatar" aria-hidden="true">{{ authorInitial }}</span>
        <div class="form-record-heading">
          <div class="form-record-author-line">
            <span class="form-record-author">{{ author }}</span>
            <span class="form-record-id">记录 {{ record.id }}</span>
          </div>
          <div class="form-record-meta">
            <span class="form-record-time" :title="timeTitle">{{ compactTime }}</span>
            <span class="form-record-version" :title="`模板版本：${record.templateVersion || spec.template_key}`">{{ record.templateVersion || spec.template_key }}</span>
            <el-tag v-if="latest" size="small" type="success" effect="plain">最新</el-tag>
            <el-tag v-if="record.legacyIncomplete" size="small" type="warning" effect="plain">历史不完整</el-tag>
            <el-tag v-if="editing" size="small" type="info" effect="plain">编辑中</el-tag>
          </div>
        </div>
      </div>
      <div class="form-record-actions">
        <el-button v-if="record.canEdit && writable" link type="primary" :disabled="editing" @click="emit('edit')">编辑</el-button>
        <el-button v-if="record.canVoid && writable" link type="danger" @click="emit('void')">作废</el-button>
      </div>
    </header>
    <div class="form-record-fields">
      <div v-if="numericFields.length" class="form-record-numbers">
        <div v-for="field in numericFields" :key="field.apiKey" class="form-record-field form-record-stat" :data-display-field="field.apiKey">
          <span class="form-record-label">{{ field.label || field.name }}</span>
          <span class="form-record-value">{{ displayValue(field) }}</span>
        </div>
      </div>
      <div v-if="shortFields.length" class="form-record-short-fields">
        <div v-for="field in shortFields" :key="field.apiKey" class="form-record-field" :data-display-field="field.apiKey">
          <span class="form-record-label">{{ field.label || field.name }}</span>
          <span class="form-record-value">{{ displayValue(field) }}</span>
        </div>
      </div>
      <div v-for="field in longFields" :key="field.apiKey" class="form-record-field form-record-long" :data-display-field="field.apiKey">
        <span class="form-record-label">{{ field.label || field.name }}</span>
        <span class="form-record-value" :class="{ 'is-collapsed': !expandedFields[field.apiKey] }">{{ displayValue(field) }}</span>
        <button v-if="canExpand(field)" type="button" class="form-record-expand" :aria-expanded="Boolean(expandedFields[field.apiKey])" @click="expandedFields[field.apiKey] = !expandedFields[field.apiKey]">{{ expandedFields[field.apiKey] ? '收起' : '展开' }}</button>
      </div>
    </div>
    <div v-if="emptyFields.length" class="form-record-empty-fields">未填写：{{ emptyFields.map((field) => field.label || field.name).join('、') }}</div>
  </article>
</template>

<style scoped>
.form-record-card { min-width: 0; padding: 16px 24px; border: 1px solid #dfe5ec; border-radius: 12px; background: #fff; }
.form-record-header { align-items: flex-start; gap: 16px; }
.form-record-identity { display: flex; flex: 1; align-items: flex-start; gap: 12px; min-width: 0; }
.form-record-avatar { flex: 0 0 28px; width: 28px; height: 28px; display: grid; place-items: center; border-radius: 50%; background: #eaf3fc; color: #1464a0; font-size: 14px; font-weight: 600; }
.form-record-heading { display: flex; flex: 1; align-items: center; gap: 4px 16px; flex-wrap: wrap; }
.form-record-author-line { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.form-record-author { font-size: 16px; font-weight: 650; line-height: 24px; color: #243347; overflow-wrap: anywhere; }
.form-record-id { font-size: 13px; color: #667487; overflow-wrap: anywhere; }
.form-record-meta { display: flex; gap: 6px 10px; margin: 0 0 0 auto; font-size: 13px; color: #667487; line-height: 20px; }
.form-record-version { font-size: 12px; padding: 0 6px; border-radius: 4px; background: #f1f4f8; color: #59697b; overflow-wrap: anywhere; }
.form-record-actions { flex: 0 0 auto; gap: 12px; padding-top: 2px; }
.form-record-actions :deep(.el-button) { height: 28px; margin: 0; padding: 0; font-size: 13px; border: 0 !important; background: transparent !important; box-shadow: none; }
.form-record-actions :deep(.el-button--primary) { color: #006ebd !important; }
.form-record-actions :deep(.el-button--danger) { color: #bf3541; }
.form-record-actions :deep(.el-button:hover) { text-decoration: underline; }
.form-record-actions :deep(.el-button.is-disabled) { color: #919cab !important; text-decoration: none; }
.form-record-fields { display: grid; gap: 8px; margin-top: 8px; }
.form-record-numbers { display: grid; grid-template-columns: repeat(auto-fit, minmax(72px, 1fr)); gap: 12px; }
.form-record-short-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px 24px; }
.form-record-field { display: flex; flex-direction: column; align-items: flex-start; min-width: 0; gap: 4px; padding: 0; border: 0; }
.form-record-label { font-size: 13px; line-height: 18px; color: #667487; }
.form-record-value { font-size: 15px; line-height: 20px; font-weight: 500; color: #243347; white-space: pre-wrap; overflow-wrap: anywhere; }
.form-record-stat .form-record-value { order: -1; font-size: 24px; font-weight: 650; line-height: 26px; font-variant-numeric: tabular-nums; }
.form-record-long .form-record-value { font-weight: 400; }
.form-record-long .is-collapsed { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
.form-record-expand { padding: 0; border: 0; background: none; color: #006ebd; font: inherit; font-size: 13px; line-height: 24px; cursor: pointer; }
.form-record-expand:focus-visible { outline: 2px solid #0078d4; outline-offset: 2px; }
.form-record-empty-fields { margin-top: 8px; font-size: 13px; line-height: 18px; color: #667487; overflow-wrap: anywhere; }
@media (max-width: 600px) {
  .form-record-card { padding: 16px; }
  .form-record-header { gap: 8px; }
  .form-record-identity { gap: 8px; }
  .form-record-avatar { width: 28px; height: 28px; flex-basis: 28px; font-size: 14px; }
  .form-record-heading { gap: 2px; }
  .form-record-meta { gap: 4px 8px; }
  .form-record-actions { gap: 8px; }
  .form-record-fields { margin-top: 16px; gap: 12px; }
  .form-record-numbers { grid-template-columns: repeat(auto-fit, minmax(44px, 1fr)); gap: 8px; }
  .form-record-stat .form-record-label { font-size: 12px; }
  .form-record-stat .form-record-value { font-size: 22px; }
}
</style>
