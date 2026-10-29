<script setup lang="ts">
import { computed } from 'vue';
import type {
  FormFieldSpecView,
  FormRecordView,
  FormTemplateSpecView,
} from '../../types/api';
import { formatFieldValue } from '../../features/collect-forms/field-logic';

const props = defineProps<{
  spec: FormTemplateSpecView | null;
  record: FormRecordView;
  templateTitle: string;
  latest: boolean;
  writable: boolean;
  editing: boolean;
}>();

const emit = defineEmits<{
  (event: 'edit'): void;
  (event: 'void'): void;
}>();

const createdAt = computed(() => formatDateTime(props.record.createdAt));
const updatedAt = computed(() => formatDateTime(props.record.updatedAt));

function formatDateTime(value: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

function displayValue(field: FormFieldSpecView): string {
  const value = props.record.fields?.[field.apiKey];
  if (field.control === 'person-select') {
    if (typeof value === 'string' && value) {
      return props.record.fieldNames?.[field.apiKey] || value;
    }
    return '—';
  }
  if (!props.spec) {
    return '—';
  }
  return formatFieldValue(props.spec, field, value as never);
}
</script>

<template>
  <article class="form-record-card" :data-record-id="record.id">
    <header class="form-record-header">
      <div class="form-record-heading">
        <span class="form-record-template">{{ templateTitle }}</span>
        <span class="form-record-id">记录 {{ record.id }}</span>
        <el-tag v-if="latest" size="small" type="success" effect="plain">最新</el-tag>
        <el-tag v-if="record.legacyIncomplete" size="small" type="warning" effect="plain">历史不完整</el-tag>
        <el-tag v-if="editing" size="small" type="info" effect="plain">编辑中</el-tag>
      </div>
      <div class="form-record-actions">
        <el-button
          v-if="record.canEdit && writable && spec"
          link
          type="primary"
          :disabled="editing"
          @click="emit('edit')"
        >
          编辑
        </el-button>
        <el-button
          v-if="record.canVoid && writable && spec"
          link
          type="danger"
          @click="emit('void')"
        >
          作废
        </el-button>
      </div>
    </header>

    <div class="form-record-meta">
      <span>填写人：{{ record.createdByName || '未知' }}</span>
      <span>模板：{{ record.templateVersion || spec?.template_key || '—' }}</span>
      <span>创建：{{ createdAt }}</span>
      <span>更新：{{ updatedAt }}</span>
    </div>

    <div v-if="spec" class="form-record-fields">
      <div v-for="field in spec.fields" :key="field.apiKey" class="form-record-field">
        <span class="form-record-label">{{ field.label || field.name }}</span>
        <span class="form-record-value">{{ displayValue(field) }}</span>
      </div>
    </div>
    <el-alert
      v-else
      type="warning"
      :closable="false"
      title="记录版本不可用：无法读取该记录所属结构版本，仅显示基础信息"
    />
  </article>
</template>
