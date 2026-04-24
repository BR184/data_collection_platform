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
  return { width: `${props.filter.width ?? defaultWidth}px` };
}

function stringValue(value: unknown) {
  return String(value ?? '');
}

function dateRangeValue(value: unknown) {
  return Array.isArray(value) ? value : [];
}
</script>

<template>
  <BaseSearchInput
    v-if="filter.type === 'input'"
    :model-value="inputValue"
    :class="['record-filter-input', inputClass]"
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
    :model-value="stringValue(modelValue)"
    class="record-filter-select"
    :style="widthStyle(defaultSelectWidth)"
    :placeholder="filter.placeholder || filter.label"
    :options="filter.options ?? []"
    :compact="filter.selectMode === 'compact'"
    @change="emit('filter-change', filter.key, stringValue($event))"
  />

  <el-date-picker
    v-else-if="filter.type === 'daterange'"
    :model-value="dateRangeValue(modelValue)"
    class="record-filter-main-date"
    :style="widthStyle(defaultDateRangeWidth)"
    type="daterange"
    range-separator="至"
    :start-placeholder="filter.startPlaceholder || '开始日期'"
    :end-placeholder="filter.endPlaceholder || '结束日期'"
    value-format="YYYY-MM-DD"
    @change="emit('filter-change', filter.key, Array.isArray($event) ? $event : null)"
  />
</template>
