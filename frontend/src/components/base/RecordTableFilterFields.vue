<script setup lang="ts">
import RecordTableFilterFieldRenderer from './RecordTableFilterFieldRenderer.vue';
import type { RecordTableFilterField } from '../../types/record-table';

const props = withDefaults(
  defineProps<{
    filters: RecordTableFilterField[];
    filterValues: Record<string, unknown>;
    inputDrafts: Record<string, string>;
    keywordFieldVisible?: boolean;
    defaultInputWidth?: number;
    defaultSelectWidth?: number;
    defaultDateRangeWidth?: number;
  }>(),
  {
    keywordFieldVisible: false,
    defaultInputWidth: 168,
    defaultSelectWidth: 168,
    defaultDateRangeWidth: 280,
  },
);

defineEmits<{
  (event: 'input-update', key: string, value: string): void;
  (event: 'input-change', key: string, value: string): void;
  (event: 'input-search', key: string): void;
  (event: 'input-clear', key: string): void;
  (event: 'filter-change', key: string, value: string | string[] | null): void;
}>();

function filterValue(key: string) {
  return props.filterValues[key];
}

function inputValue(filter: RecordTableFilterField) {
  return props.inputDrafts[filter.key] ?? String(filterValue(filter.key) ?? '');
}

function inputClass(filter: RecordTableFilterField) {
  return props.keywordFieldVisible && filter.key === 'keyword' ? { 'record-filter-main-keyword': true } : '';
}

function inputWidth(filter: RecordTableFilterField) {
  return props.keywordFieldVisible && filter.key === 'keyword' ? 260 : props.defaultInputWidth;
}
</script>

<template>
  <RecordTableFilterFieldRenderer
    v-for="filter in filters"
    :key="filter.key"
    :filter="filter"
    :model-value="filterValue(filter.key)"
    :input-value="inputValue(filter)"
    :input-class="inputClass(filter)"
    :default-input-width="inputWidth(filter)"
    :default-select-width="defaultSelectWidth"
    :default-date-range-width="defaultDateRangeWidth"
    @input-update="(key, value) => $emit('input-update', key, value)"
    @input-change="(key, value) => $emit('input-change', key, value)"
    @input-search="(key) => $emit('input-search', key)"
    @input-clear="(key) => $emit('input-clear', key)"
    @filter-change="(key, value) => $emit('filter-change', key, value)"
  />
</template>
