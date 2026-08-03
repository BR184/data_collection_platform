<script setup lang="ts">
import RecordTableFilterFieldRenderer from './RecordTableFilterFieldRenderer.vue';
import type { RecordTableFilterField, RecordTableFilterValue } from '../../types/record-table';
import { filterDimensionKey } from '../filter-priority';

const props = withDefaults(
  defineProps<{
    filters: RecordTableFilterField[];
    filterValues: Record<string, unknown>;
    inputDrafts: Record<string, string>;
    keywordFieldVisible?: boolean;
    defaultInputWidth?: number;
    defaultSelectWidth?: number;
    defaultDateRangeWidth?: number;
    disabledKeys?: string[];
    highlightedKeys?: string[];
  }>(),
  {
    keywordFieldVisible: false,
    defaultInputWidth: 168,
    defaultSelectWidth: 168,
    defaultDateRangeWidth: 280,
    disabledKeys: () => [],
    highlightedKeys: () => [],
  },
);

defineEmits<{
  (event: 'input-update', key: string, value: string): void;
  (event: 'input-change', key: string, value: string): void;
  (event: 'input-search', key: string): void;
  (event: 'input-clear', key: string): void;
  (event: 'filter-change', key: string, value: RecordTableFilterValue): void;
}>();

function filterValue(key: string) {
  return props.filterValues[key];
}

function inputValue(filter: RecordTableFilterField) {
  return props.inputDrafts[filter.key] ?? String(filterValue(filter.key) ?? '');
}

function inputClass(filter: RecordTableFilterField) {
  return props.keywordFieldVisible && (filter.key === 'keyword' || filter.key === 'detailKeyword')
    ? { 'record-filter-main-keyword': true, 'record-filter-main-keyword--highlight': true }
    : '';
}

function inputWidth(filter: RecordTableFilterField) {
  return props.keywordFieldVisible && (filter.key === 'keyword' || filter.key === 'detailKeyword')
    ? 260
    : props.defaultInputWidth;
}

function isDisabled(filter: RecordTableFilterField) {
  const dimension = filterDimensionKey(filter.key);
  return props.disabledKeys.some((key) => filterDimensionKey(key) === dimension);
}

function isHighlighted(filter: RecordTableFilterField) {
  const dimension = filterDimensionKey(filter.key);
  return props.highlightedKeys.some((key) => filterDimensionKey(key) === dimension);
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
    :disabled="isDisabled(filter)"
    :highlighted="isHighlighted(filter)"
    @input-update="(key, value) => $emit('input-update', key, value)"
    @input-change="(key, value) => $emit('input-change', key, value)"
    @input-search="(key) => $emit('input-search', key)"
    @input-clear="(key) => $emit('input-clear', key)"
    @filter-change="(key, value) => $emit('filter-change', key, value)"
  />
</template>
