<script setup lang="ts">
import BaseSearchInput from './BaseSearchInput.vue';
import SmartSelect from './SmartSelect.vue';
import type { RecordTableFilterField } from '../../types/record-table';

const props = withDefaults(
  defineProps<{
    filter: RecordTableFilterField;
    modelValue?: unknown;
    inputValue?: string;
    inputClass?: string | Record<string, boolean>;
    defaultInputWidth?: number;
    defaultSelectWidth?: number;
    defaultDateRangeWidth?: number;
  }>(),
  {
    modelValue: '',
    inputValue: '',
    inputClass: '',
    defaultInputWidth: 168,
    defaultSelectWidth: 168,
    defaultDateRangeWidth: 280,
  },
);

const emit = defineEmits<{
  (event: 'input-update', key: string, value: string): void;
  (event: 'input-change', key: string, value: string): void;
  (event: 'input-search', key: string): void;
  (event: 'input-clear', key: string): void;
  (event: 'filter-change', key: string, value: string | string[] | null): void;
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
</script>

<template>
  <BaseSearchInput
    v-if="filter.type === 'input'"
    :model-value="inputValue"
    :class="['record-filter-control', 'record-filter-input', inputClass]"
    :style="widthStyle(defaultInputWidth)"
    :placeholder="filter.placeholder || filter.label"
    :clearable="filter.clearable ?? true"
    @update:model-value="emit('input-update', filter.key, stringValue($event))"
    @change="emit('input-change', filter.key, stringValue($event))"
    @search="emit('input-search', filter.key)"
    @clear="emit('input-clear', filter.key)"
  />

  <SmartSelect
    v-else-if="filter.type === 'select'"
    :model-value="selectValue(modelValue)"
    class="record-filter-control record-filter-select"
    :style="widthStyle(defaultSelectWidth)"
    :placeholder="filter.placeholder || filter.label"
    :options="filter.options ?? []"
    :multiple="filter.multiple"
    :compact="filter.selectMode === 'compact'"
    dropdown-mode="adaptive-tags"
    @change="emit('filter-change', filter.key, filter.multiple ? (Array.isArray($event) ? $event : []) : stringValue($event))"
  />

  <el-date-picker
    v-else-if="filter.type === 'daterange'"
    :model-value="dateRangeValue(modelValue)"
    class="record-filter-control record-filter-main-date"
    :style="widthStyle(defaultDateRangeWidth)"
    type="daterange"
    range-separator="至"
    :start-placeholder="filter.startPlaceholder || '开始日期'"
    :end-placeholder="filter.endPlaceholder || '结束日期'"
    value-format="YYYY-MM-DD"
    @update:model-value="emitDateRangeChange"
  />
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

@media (max-width: 720px) {
  .record-filter-control {
    width: 100% !important;
    max-width: 100%;
  }
}
</style>
