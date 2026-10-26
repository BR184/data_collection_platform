<script setup lang="ts">
import { computed } from 'vue';
import type {
  FormFieldSpecView,
  FormReviewerCandidate,
  FormTemplateSpecView,
} from '../../types/api';
import {
  fieldOptions,
  isDateDisabled,
  type FieldOptionView,
  type FieldValue,
} from '../../features/collect-forms/field-logic';

const props = defineProps<{
  spec: FormTemplateSpecView;
  field: FormFieldSpecView;
  modelValue: FieldValue;
  disabled: boolean;
  reviewerCandidates?: FormReviewerCandidate[];
  reviewerLoading?: boolean;
  searchReviewers?: (keyword: string) => void;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: unknown): void;
}>();

const options = computed(() => fieldOptions(props.spec, props.field));
const groupedOptions = computed(() => {
  const groups = new Map<string, FieldOptionView[]>();
  for (const option of options.value) {
    const group = option.group ?? '';
    const bucket = groups.get(group) ?? [];
    bucket.push(option);
    groups.set(group, bucket);
  }
  return [...groups.entries()].map(([name, items]) => ({ name, items }));
});
const hasGroups = computed(() => options.value.some((option) => option.group));
const stringValue = computed(() => (typeof props.modelValue === 'string' ? props.modelValue : ''));
const numberValue = computed(() =>
  typeof props.modelValue === 'number' ? props.modelValue : undefined,
);
const arrayValue = computed<string[]>(() =>
  Array.isArray(props.modelValue) ? [...props.modelValue] : [],
);
const radioValue = computed<string | number | boolean | undefined>(() => {
  const value = props.modelValue;
  return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'
    ? value
    : undefined;
});
const dateDisabled = computed(() => isDateDisabled(props.field));
const personCandidates = computed(() => {
  const candidates = [...(props.reviewerCandidates ?? [])];
  if (typeof props.modelValue === 'string' && props.modelValue && !candidates.some((item) => item.userId === props.modelValue)) {
    candidates.unshift({
      userId: props.modelValue,
      realName: props.modelValue,
      deptName: null,
    });
  }
  return candidates;
});

function update(value: unknown) {
  emit('update:modelValue', value);
}

function updateNumber(value: number | null | undefined) {
  update(value ?? null);
}

function updateDate(value: string | null) {
  update(value || null);
}

function searchPerson(keyword: string) {
  props.searchReviewers?.(keyword);
}
</script>

<template>
  <el-select
    v-if="field.control === 'person-select'"
    :model-value="stringValue || undefined"
    :disabled="disabled"
    :loading="reviewerLoading"
    clearable
    filterable
    remote
    placeholder="搜索姓名或账号"
    style="width: 100%"
    :remote-method="searchPerson"
    @update:model-value="(value) => update(value ?? '')"
  >
    <el-option
      v-for="candidate in personCandidates"
      :key="candidate.userId"
      :label="`${candidate.realName}（${candidate.userId}）`"
      :value="candidate.userId"
    />
  </el-select>

  <el-input
    v-else-if="field.type === 'string' && field.control === 'textarea'"
    :model-value="stringValue"
    :disabled="disabled"
    type="textarea"
    :rows="3"
    :maxlength="field.maxLength ?? undefined"
    :show-word-limit="field.maxLength != null"
    placeholder="请输入"
    @update:model-value="update"
  />

  <el-input
    v-else-if="field.type === 'string'"
    :model-value="stringValue"
    :disabled="disabled"
    :maxlength="field.maxLength ?? undefined"
    :show-word-limit="field.maxLength != null"
    :placeholder="field.control === 'version-members' ? '例如：CC2026R1&CC2026R2' : '请输入'"
    @update:model-value="update"
  />

  <el-input-number
    v-else-if="field.type === 'integer'"
    :model-value="numberValue"
    :disabled="disabled"
    :min="field.min ?? -2147483648"
    :max="field.max ?? 2147483647"
    :step="1"
    :precision="0"
    controls-position="right"
    style="width: 100%"
    @update:model-value="updateNumber"
  />

  <el-date-picker
    v-else-if="field.type === 'date'"
    :model-value="stringValue"
    :disabled="disabled"
    type="date"
    value-format="YYYY-MM-DD"
    placeholder="选择日期"
    :disabled-date="dateDisabled"
    style="width: 100%"
    @update:model-value="updateDate"
  />

  <el-radio-group
    v-else-if="field.type === 'boolean'"
    :model-value="radioValue"
    :disabled="disabled"
    @update:model-value="update"
  >
    <el-radio :value="true">是</el-radio>
    <el-radio :value="false">否</el-radio>
  </el-radio-group>

  <el-radio-group
    v-else-if="field.type === 'enum'"
    :model-value="radioValue"
    :disabled="disabled"
    @update:model-value="update"
  >
    <el-radio v-for="option in options" :key="option.value" :value="option.value">
      {{ option.label }}
    </el-radio>
  </el-radio-group>

  <el-checkbox-group
    v-else-if="field.type === 'enum-array' && field.control === 'checkbox'"
    :model-value="arrayValue"
    :disabled="disabled"
    @update:model-value="update"
  >
    <el-checkbox v-for="option in options" :key="option.value" :value="option.value">
      {{ option.label }}
    </el-checkbox>
  </el-checkbox-group>

  <el-select
    v-else-if="field.type === 'enum-array'"
    :model-value="arrayValue"
    :disabled="disabled"
    multiple
    filterable
    clearable
    collapse-tags
    collapse-tags-tooltip
    placeholder="请选择"
    style="width: 100%"
    @update:model-value="update"
  >
    <template v-if="hasGroups">
      <el-option-group
        v-for="group in groupedOptions"
        :key="group.name || 'ungrouped'"
        :label="group.name || '其他'"
      >
        <el-option
          v-for="option in group.items"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-option-group>
    </template>
    <template v-else>
      <el-option
        v-for="option in options"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </template>
  </el-select>

  <el-input
    v-else
    :model-value="stringValue"
    :disabled="disabled"
    placeholder="请输入"
    @update:model-value="update"
  />
</template>
