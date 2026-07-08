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
    dropdownLayout?: 'grid' | 'list';
    dropdownMode?: 'default' | 'adaptive-tags';
    popperClassExtra?: string;
    fitInputWidth?: boolean;
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
    dropdownLayout: 'grid',
    dropdownMode: 'default',
    popperClassExtra: '',
    fitInputWidth: true,
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
  const classNames: string[] = [];
  if (props.dropdownLayout === 'list') {
    // Keep list dropdowns on Element Plus' native option layout. Platform grid styling is opt-in below.
  } else {
    classNames.push('platform-select-dropdown');
    classNames.push('smart-select-dropdown');
    if (props.compact) {
      classNames.push('smart-select-dropdown--compact');
    }
    if (props.multiple) {
      classNames.push('smart-select-dropdown--multiple');
    }
    if (props.compact && props.multiple) {
      classNames.push('smart-select-dropdown--compact-multiple');
    }
    if (props.dropdownMode === 'adaptive-tags') {
      classNames.push('smart-select-dropdown--adaptive-tags');
    }
  }
  if (props.popperClassExtra) {
    classNames.push(props.popperClassExtra);
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

const effectiveFitInputWidth = computed(() => (props.dropdownMode === 'adaptive-tags' ? false : props.fitInputWidth));

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

function isLabelGroupOption(value: string) {
  return value.startsWith('__label_group__:');
}

function optionVariant(option: RecordTableFilterOption) {
  return option.variant ?? (isLabelGroupOption(option.value) ? 'label-group' : 'normal');
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
    :collapse-tags="false"
    :collapse-tags-tooltip="false"
    :disabled="disabled"
    :loading="loading"
    :allow-create="allowCreate"
    :default-first-option="allowCreate"
    :fit-input-width="effectiveFitInputWidth"
    :popper-class="popperClass"
    @change="handleChange"
    @visible-change="handleVisibleChange"
  >
    <template v-if="dropdownLayout === 'list'">
      <el-option
        v-for="option in filteredOptions"
        :key="option.value"
        :label="option.label"
        :value="option.value"
      />
    </template>
    <template v-else>
      <el-option
        v-for="option in filteredOptions"
        :key="option.value"
        :label="option.label"
        :value="option.value"
        :class="{
          'smart-select-option-item--selected': isOptionSelected(option.value),
          'smart-select-option-item--normal': optionVariant(option) === 'normal',
          'smart-select-option-item--label-group': optionVariant(option) === 'label-group',
        }"
      >
        <div class="smart-select-option">
          <span class="smart-select-option-label">{{ option.label }}</span>
        </div>
      </el-option>
    </template>
  </el-select>
</template>

<style>
.smart-select-dropdown .smart-select-option {
  display: flex;
  align-items: center;
  width: auto;
  max-width: 100%;
}

.smart-select-dropdown .el-select-dropdown__list,
.smart-select-dropdown--compact .el-select-dropdown__list {
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

.smart-select-dropdown .el-select-dropdown__item,
.smart-select-dropdown--compact .el-select-dropdown__item {
  flex: 0 1 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  width: fit-content;
  max-width: 100%;
  min-width: 48px;
  min-height: 34px;
  height: auto;
  line-height: 1.35;
  margin: 0;
  padding: 7px 12px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: #fff;
  color: rgba(15, 23, 42, 0.78);
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease,
    color 0.18s ease,
    box-shadow 0.18s ease;
}

.smart-select-dropdown .el-select-dropdown__item.hover,
.smart-select-dropdown .el-select-dropdown__item:hover,
.smart-select-dropdown--compact .el-select-dropdown__item.hover,
.smart-select-dropdown--compact .el-select-dropdown__item:hover {
  border-color: rgba(64, 158, 255, 0.36);
  background: rgba(64, 158, 255, 0.08);
  color: #1d4ed8;
}

.smart-select-dropdown .el-select-dropdown__item.selected,
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

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--normal,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--normal {
  border-color: rgba(37, 99, 235, 0.18);
  background: rgba(239, 246, 255, 0.74);
  color: #1d4ed8;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--normal.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--normal:hover,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--normal.hover,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--normal:hover {
  border-color: rgba(37, 99, 235, 0.36);
  background: rgba(219, 234, 254, 0.86);
  color: #1e40af;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--label-group {
  border-color: rgba(5, 150, 105, 0.28);
  background: rgba(236, 253, 245, 0.96);
  color: #047857;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group:hover,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--label-group.hover,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--label-group:hover {
  border-color: rgba(5, 150, 105, 0.46);
  background: rgba(209, 250, 229, 0.98);
  color: #065f46;
  box-shadow: 0 4px 12px rgba(5, 150, 105, 0.12);
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group .smart-select-option-label,
.smart-select-dropdown--compact .el-select-dropdown__item.smart-select-option-item--label-group .smart-select-option-label {
  color: inherit;
  font-weight: 600;
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.smart-select-option-item--selected,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.selected,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.is-selected,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group[aria-selected='true'] {
  border-color: #059669 !important;
  background: #059669 !important;
  color: #fff !important;
  box-shadow: 0 6px 14px rgba(5, 150, 105, 0.2);
}

.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.smart-select-option-item--selected.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.smart-select-option-item--selected:hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.selected.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.selected:hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.is-selected.hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group.is-selected:hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group[aria-selected='true'].hover,
.smart-select-dropdown .el-select-dropdown__item.smart-select-option-item--label-group[aria-selected='true']:hover {
  border-color: #047857 !important;
  background: #047857 !important;
  color: #fff !important;
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

.smart-select-dropdown .smart-select-option,
.smart-select-dropdown--compact .smart-select-option {
  justify-content: center;
  text-align: center;
}

.smart-select-dropdown .smart-select-option-label,
.smart-select-dropdown--compact .smart-select-option-label {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  line-height: 1.35;
  white-space: nowrap;
}

.smart-select.smart-select--multiple .el-select__wrapper {
  align-items: center;
  min-height: 32px;
  height: auto;
  padding-top: 4px;
  padding-bottom: 4px;
}

.smart-select.smart-select--multiple .el-select__selection {
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.smart-select.smart-select--multiple .el-select__selected-item {
  max-width: 100%;
  margin: 0;
}

.smart-select.smart-select--multiple .el-tag {
  max-width: 100%;
  height: auto;
  min-height: 22px;
}

.smart-select.smart-select--multiple .el-tag__content {
  max-width: 100%;
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  line-height: 1.3;
  word-break: break-word;
}

.smart-select-dropdown .el-select-dropdown__wrap,
.smart-select-dropdown--compact-multiple .el-select-dropdown__wrap {
  overflow-x: hidden;
}

.smart-select-dropdown--compact-multiple .el-select-dropdown__item {
  min-width: 64px;
}

.smart-select-dropdown--compact-multiple .smart-select-option {
  justify-content: center;
  text-align: center;
}

.smart-select-dropdown--compact-multiple .smart-select-option-label {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: center;
}

.smart-select-dropdown--adaptive-tags {
  width: min(360px, calc(100vw - 32px)) !important;
  min-width: min(160px, calc(100vw - 32px));
  max-width: min(360px, calc(100vw - 32px));
  box-sizing: border-box;
}

.smart-select-dropdown--adaptive-tags .el-select-dropdown__list {
  width: 100%;
  min-width: 100%;
  max-width: 100%;
}

.smart-select-dropdown--adaptive-tags .el-select-dropdown__item {
  flex: 0 1 auto;
  width: fit-content;
  max-width: min(240px, calc(100vw - 72px));
  overflow: visible;
}

.smart-select-dropdown--adaptive-tags .smart-select-option {
  max-width: min(216px, calc(100vw - 72px));
}

.smart-select-dropdown--adaptive-tags .smart-select-option-label {
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: normal;
}

</style>
