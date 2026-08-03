<script setup lang="ts">
import BaseSearchInput from './BaseSearchInput.vue';
import SmartSelect from './SmartSelect.vue';
import type {
  RecordTableFilterField,
  RecordTableFilterValue,
  RecordTableNumberRangeValue,
} from '../../types/record-table';

const props = withDefaults(
  defineProps<{
    filter: RecordTableFilterField;
    modelValue?: unknown;
    inputValue?: string;
    inputClass?: string | Record<string, boolean>;
    defaultInputWidth?: number;
    defaultSelectWidth?: number;
    defaultDateRangeWidth?: number;
    disabled?: boolean;
    highlighted?: boolean;
  }>(),
  {
    modelValue: '',
    inputValue: '',
    inputClass: '',
    defaultInputWidth: 168,
    defaultSelectWidth: 168,
    defaultDateRangeWidth: 280,
    disabled: false,
    highlighted: false,
  },
);

const emit = defineEmits<{
  (event: 'input-update', key: string, value: string): void;
  (event: 'input-change', key: string, value: string): void;
  (event: 'input-search', key: string): void;
  (event: 'input-clear', key: string): void;
  (event: 'filter-change', key: string, value: RecordTableFilterValue): void;
}>();

function widthStyle(defaultWidth: number) {
  const width = `${props.filter.width ?? defaultWidth}px`;
  return { width, flex: `0 0 ${width}` };
}

function stringValue(value: unknown) {
  return String(value ?? '');
}

function selectValue(value: unknown) {
  if (props.filter.multiple) {
    return Array.isArray(value) ? value.map((item) => String(item)) : [];
  }
  return stringValue(value);
}

function dateRangeValue(value: unknown) {
  if (!Array.isArray(value)) {
    return [];
  }
  const [start, end] = value.map((item) => String(item ?? '').trim());
  return start && end ? [start, end] : [];
}

function emitDateRangeChange(value: unknown) {
  emit('filter-change', props.filter.key, dateRangeValue(value));
}

function numberRangeValue(value: unknown): RecordTableNumberRangeValue {
  if (!Array.isArray(value)) {
    return [null, null];
  }
  return [finiteNumber(value[0]), finiteNumber(value[1])];
}

function finiteNumber(value: unknown) {
  if (value == null || value === '') {
    return null;
  }
  const numericValue = Number(value);
  return Number.isFinite(numericValue) ? numericValue : null;
}

function emitNumberRangeChange(index: 0 | 1, value: unknown) {
  const nextValue = numberRangeValue(props.modelValue);
  nextValue[index] = finiteNumber(value);
  emit('filter-change', props.filter.key, nextValue);
}

function controlClass(baseClass: string) {
  return [
    'record-filter-control',
    baseClass,
    props.highlighted ? 'record-filter-control--priority-warning' : '',
    props.disabled ? 'record-filter-control--priority-disabled' : '',
  ];
}
</script>

<template>
  <BaseSearchInput
    v-if="filter.type === 'input'"
    :model-value="inputValue"
    :class="[controlClass('record-filter-input'), inputClass]"
    :style="widthStyle(defaultInputWidth)"
    :placeholder="filter.placeholder || filter.label"
    :clearable="filter.clearable ?? true"
    :disabled="disabled"
    @update:model-value="emit('input-update', filter.key, stringValue($event))"
    @change="emit('input-change', filter.key, stringValue($event))"
    @search="emit('input-search', filter.key)"
    @clear="emit('input-clear', filter.key)"
  />

  <SmartSelect
    v-else-if="filter.type === 'select'"
    :model-value="selectValue(modelValue)"
    :class="controlClass('record-filter-select')"
    :style="widthStyle(defaultSelectWidth)"
    :placeholder="filter.placeholder || filter.label"
    :options="filter.options ?? []"
    :multiple="filter.multiple"
    :compact="filter.selectMode === 'compact'"
    :disabled="disabled"
    dropdown-mode="adaptive-tags"
    @change="emit('filter-change', filter.key, filter.multiple ? (Array.isArray($event) ? $event : []) : stringValue($event))"
  />

  <el-date-picker
    v-else-if="filter.type === 'daterange'"
    :model-value="dateRangeValue(modelValue)"
    :class="controlClass('record-filter-main-date')"
    :style="widthStyle(defaultDateRangeWidth)"
    type="daterange"
    range-separator="至"
    :start-placeholder="filter.startPlaceholder || '开始日期'"
    :end-placeholder="filter.endPlaceholder || '结束日期'"
    value-format="YYYY-MM-DD"
    :disabled="disabled"
    @update:model-value="emitDateRangeChange"
  />

  <div
    v-else-if="filter.type === 'numberrange'"
    :class="controlClass('record-filter-number-range')"
    :style="widthStyle(defaultDateRangeWidth)"
    role="group"
    :aria-label="filter.label"
  >
    <el-input-number
      :model-value="numberRangeValue(modelValue)[0]"
      :min="filter.min"
      :max="numberRangeValue(modelValue)[1] ?? filter.max"
      :step="filter.step ?? 1"
      :precision="filter.precision"
      :placeholder="filter.startPlaceholder || `最小${filter.label}`"
      :aria-label="filter.startPlaceholder || `最小${filter.label}`"
      :controls="false"
      :disabled="disabled"
      @update:model-value="emitNumberRangeChange(0, $event)"
    />
    <span class="record-filter-number-range__separator" aria-hidden="true">至</span>
    <el-input-number
      :model-value="numberRangeValue(modelValue)[1]"
      :min="numberRangeValue(modelValue)[0] ?? filter.min"
      :max="filter.max"
      :step="filter.step ?? 1"
      :precision="filter.precision"
      :placeholder="filter.endPlaceholder || `最大${filter.label}`"
      :aria-label="filter.endPlaceholder || `最大${filter.label}`"
      :controls="false"
      :disabled="disabled"
      @update:model-value="emitNumberRangeChange(1, $event)"
    />
  </div>
</template>

<style scoped>
.record-filter-control {
  flex: 0 1 auto;
  max-width: min(100%, 360px);
}

.record-filter-input,
.record-filter-select {
  min-width: 144px;
}

.record-filter-main-keyword {
  min-width: 220px;
}

.record-filter-main-keyword--highlight :deep(.el-input__wrapper) {
  border: 0;
  background: #fff;
  box-shadow: 0 0 0 1px var(--el-color-warning-light-5) inset !important;
}

.record-filter-main-keyword--highlight :deep(.el-input__wrapper:hover),
.record-filter-main-keyword--highlight :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--el-color-warning) inset !important;
}

.record-filter-main-keyword--highlight :deep(.el-input__inner) {
  color: var(--el-text-color-regular);
}

.record-filter-main-keyword--highlight :deep(.el-input__prefix),
.record-filter-main-keyword--highlight :deep(.el-input__inner::placeholder) {
  color: var(--el-color-warning-light-5);
}

.record-filter-main-date {
  min-width: 260px;
}

.record-filter-number-range {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  min-width: 280px;
}

.record-filter-number-range :deep(.el-input-number) {
  width: 100%;
}

.record-filter-number-range__separator {
  padding: 0 8px;
  color: var(--el-text-color-secondary);
}

.record-filter-control--priority-warning {
  animation: record-filter-priority-pulse 0.96s ease-in-out infinite;
}

.record-filter-control--priority-warning :deep(.el-input__wrapper),
.record-filter-control--priority-warning :deep(.el-select__wrapper) {
  border-color: transparent !important;
  background: #fff1f2 !important;
  box-shadow:
    0 0 0 2px #f43f5e inset,
    0 0 0 3px rgba(244, 63, 94, 0.16) !important;
}

.record-filter-control--priority-warning :deep(.el-input__inner),
.record-filter-control--priority-warning :deep(.el-select__placeholder),
.record-filter-control--priority-warning :deep(.el-select__selected-item) {
  color: #be123c !important;
}

.record-filter-control--priority-warning :deep(.el-input__suffix),
.record-filter-control--priority-warning :deep(.el-input__prefix),
.record-filter-control--priority-warning :deep(.el-select__caret) {
  color: #e11d48 !important;
}

.record-filter-control--priority-disabled {
  opacity: 1;
}

@keyframes record-filter-priority-pulse {
  0%,
  100% {
    transform: translateY(0);
    filter: none;
  }
  50% {
    transform: translateY(-1px);
    filter: saturate(1.2);
  }
}

@media (max-width: 720px) {
  .record-filter-control {
    width: 100% !important;
    max-width: 100%;
  }

  .record-filter-number-range {
    min-width: 0;
  }
}
</style>
