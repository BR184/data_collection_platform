<script setup lang="ts">
import { computed, ref } from 'vue';
import type { RecordTableFilterOption } from '../../types/record-table';
import { matchesSmartSelectOption } from './smart-select-search';

const props = withDefaults(
  defineProps<{
    modelValue: string | string[];
    options: RecordTableFilterOption[];
    placeholder?: string;
    clearable?: boolean;
    multiple?: boolean;
    collapseTags?: boolean;
    compact?: boolean;
    disabled?: boolean;
    loading?: boolean;
    allowCreate?: boolean;
  }>(),
  {
    placeholder: '',
    clearable: true,
    multiple: false,
    collapseTags: false,
    compact: false,
    disabled: false,
    loading: false,
    allowCreate: false,
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: string | string[]): void;
  (event: 'change', value: string | string[]): void;
  (event: 'visible-change', value: boolean): void;
  (event: 'search', value: string): void;
}>();

const query = ref('');

const filteredOptions = computed(() => {
  const normalizedQuery = query.value.trim().toLowerCase();
  if (!normalizedQuery) {
    return props.options;
  }
  return props.options.filter((option) => matchesOption(option, normalizedQuery));
});

const popperClass = computed(() => {
  const classNames = ['smart-select-dropdown'];
  if (props.compact) {
    classNames.push('smart-select-dropdown--compact');
  }
  if (props.multiple) {
    classNames.push('smart-select-dropdown--multiple');
  }
  if (props.compact && props.multiple) {
    classNames.push('smart-select-dropdown--compact-multiple');
  }
  return classNames.join(' ');
});

const selectClass = computed(() => {
  const classNames = ['smart-select'];
  if (props.compact) {
    classNames.push('smart-select--compact');
  }
  if (props.multiple) {
    classNames.push('smart-select--multiple');
  }
  if (props.compact && props.multiple) {
    classNames.push('smart-select--compact-multiple');
  }
  return classNames;
});

function handleFilter(keyword: string) {
  query.value = keyword;
  emit('search', keyword);
}

function handleVisibleChange(visible: boolean) {
  emit('visible-change', visible);
  if (!visible) {
    query.value = '';
  }
}

function handleChange(value: string | string[]) {
  emit('update:modelValue', value);
  emit('change', value);
}

function matchesOption(option: RecordTableFilterOption, normalizedQuery: string) {
  return matchesSmartSelectOption(option, normalizedQuery);
}

function isOptionSelected(value: string) {
  return Array.isArray(props.modelValue) ? props.modelValue.includes(value) : props.modelValue === value;
}
</script>

<template>
  <el-select
    :class="selectClass"
    :model-value="modelValue"
    filterable
    :filter-method="handleFilter"
    :placeholder="placeholder"
    :clearable="clearable"
    :multiple="multiple"
    :collapse-tags="collapseTags"
    collapse-tags-tooltip
    :disabled="disabled"
    :loading="loading"
    :allow-create="allowCreate"
    :default-first-option="allowCreate"
    :fit-input-width="compact && multiple"
    :popper-class="popperClass"
    @change="handleChange"
    @visible-change="handleVisibleChange"
  >
    <el-option
      v-for="option in filteredOptions"
      :key="option.value"
      :label="option.label"
      :value="option.value"
      :class="{ 'smart-select-option-item--selected': isOptionSelected(option.value) }"
    >
      <div class="smart-select-option">
        <span class="smart-select-option-label">{{ option.label }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<style>
.smart-select-dropdown .smart-select-option {
  display: flex;
  align-items: center;
  width: 100%;
}

.smart-select-dropdown--compact .el-select-dropdown__list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 8px;
  padding: 8px;
}

.smart-select-dropdown--compact .el-select-dropdown__item {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  height: auto;
  line-height: 1.4;
  margin: 0;
  padding: 8px 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 10px;
  background: #fff;
  color: rgba(15, 23, 42, 0.78);
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease,
    color 0.18s ease,
    box-shadow 0.18s ease;
}

.smart-select-dropdown--compact .el-select-dropdown__item.hover,
.smart-select-dropdown--compact .el-select-dropdown__item:hover {
  border-color: rgba(64, 158, 255, 0.36);
  background: rgba(64, 158, 255, 0.08);
  color: #1d4ed8;
}

.smart-select-dropdown--compact .el-select-dropdown__item.selected {
  border-color: rgba(64, 158, 255, 0.48);
  background: rgba(64, 158, 255, 0.12);
  color: #1d4ed8;
  font-weight: 600;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--selected,
.smart-select-dropdown .el-select-dropdown__item.selected,
.smart-select-dropdown .el-select-dropdown__item.is-selected,
.smart-select-dropdown .el-select-dropdown__item[aria-selected='true'] {
  border-color: #2563eb !important;
  background: #2563eb !important;
  color: #fff !important;
  font-weight: 400 !important;
  box-shadow: 0 6px 14px rgba(37, 99, 235, 0.18);
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--selected.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--selected:hover,
.smart-select-dropdown .el-select-dropdown__item.selected.hover,
.smart-select-dropdown .el-select-dropdown__item.selected:hover,
.smart-select-dropdown .el-select-dropdown__item.is-selected.hover,
.smart-select-dropdown .el-select-dropdown__item.is-selected:hover,
.smart-select-dropdown .el-select-dropdown__item[aria-selected='true'].hover,
.smart-select-dropdown .el-select-dropdown__item[aria-selected='true']:hover {
  border-color: #1d4ed8 !important;
  background: #1d4ed8 !important;
  color: #fff !important;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--selected .smart-select-option-label,
.smart-select-dropdown .el-select-dropdown__item.selected .smart-select-option-label,
.smart-select-dropdown .el-select-dropdown__item.is-selected .smart-select-option-label,
.smart-select-dropdown .el-select-dropdown__item[aria-selected='true'] .smart-select-option-label {
  color: #fff !important;
  font-weight: 400;
}

.smart-select-dropdown--compact .smart-select-option {
  justify-content: center;
  text-align: center;
}

.smart-select-dropdown--compact .smart-select-option-label {
  font-size: 13px;
  line-height: 1.35;
  white-space: normal;
}

.smart-select-dropdown--compact-multiple .el-select-dropdown__list {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 6px;
  padding: 8px;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  overflow-x: hidden;
}

.smart-select-dropdown--compact-multiple .el-select-dropdown__wrap {
  overflow-x: hidden;
}

.smart-select-dropdown--compact-multiple .el-select-dropdown__item {
  flex: 0 1 auto;
  width: fit-content;
  min-width: 64px;
  max-width: 100%;
  min-height: 34px;
  height: auto;
  line-height: 1.35;
  padding: 7px 12px;
  border-radius: 9px;
  justify-content: center;
}

.smart-select-dropdown--compact-multiple .smart-select-option {
  justify-content: center;
  text-align: center;
}

.smart-select-dropdown--compact-multiple .smart-select-option-label {
  display: block;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
}
</style>
