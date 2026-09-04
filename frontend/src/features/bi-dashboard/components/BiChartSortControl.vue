<script setup lang="ts">
import { Bottom, Sort, Top } from '@element-plus/icons-vue';
import type { PropType } from 'vue';

export interface BiSortOption {
  label: string;
  value: string;
}

export type BiSortOrder = 'asc' | 'desc';

const props = defineProps({
  modelValue: { type: String, required: true },
  order: { type: String as PropType<BiSortOrder>, default: 'desc' },
  options: { type: Array as PropType<BiSortOption[]>, default: () => [] },
  placeholder: { type: String, default: '排序指标' },
});

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void;
  (event: 'update:order', value: BiSortOrder): void;
  (event: 'change', payload: { sortBy: string; order: BiSortOrder }): void;
}>();

function handleSelectChange(val: string): void {
  emit('update:modelValue', val);
  emit('change', { sortBy: val, order: props.order });
}

function toggleOrder(): void {
  const nextOrder: BiSortOrder = props.order === 'asc' ? 'desc' : 'asc';
  emit('update:order', nextOrder);
  emit('change', { sortBy: props.modelValue, order: nextOrder });
}
</script>

<template>
  <div v-if="options.length" class="bi-chart-sort-control">
    <el-icon class="bi-chart-sort-control__icon"><Sort /></el-icon>
    <el-select
      class="bi-chart-sort-control__select"
      :model-value="modelValue"
      size="small"
      :placeholder="placeholder"
      aria-label="排序指标"
      @update:model-value="handleSelectChange"
    >
      <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <div class="bi-chart-sort-control__divider" />
    <el-tooltip :content="order === 'asc' ? '当前：升序（点击切换为降序）' : '当前：降序（点击切换为升序）'" placement="top">
      <button
        type="button"
        class="bi-chart-sort-control__btn"
        :class="{ 'is-asc': order === 'asc' }"
        :aria-label="order === 'asc' ? '切换为降序' : '切换为升序'"
        @click="toggleOrder"
      >
        <el-icon :size="13">
          <Top v-if="order === 'asc'" />
          <Bottom v-else />
        </el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<style scoped>
.bi-chart-sort-control {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 3px 0 7px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  color: #475467;
  box-sizing: border-box;
  transition: all 0.2s ease;
}

.bi-chart-sort-control:hover {
  border-color: #cbd5e1;
  background: #f1f5f9;
}

.bi-chart-sort-control__icon {
  font-size: 13px;
  color: #94a3b8;
  margin-right: 2px;
  flex-shrink: 0;
}

.bi-chart-sort-control__select {
  width: 92px;
}

/* 穿透定制极简无框 Select */
:deep(.bi-chart-sort-control__select .el-select__wrapper) {
  padding: 0 4px;
  min-height: 24px;
  height: 24px;
  background-color: transparent !important;
  box-shadow: none !important;
  border: none !important;
}

:deep(.bi-chart-sort-control__select .el-select__selected-item) {
  font-size: 12px;
  color: #334155;
  font-weight: 500;
}

:deep(.bi-chart-sort-control__select .el-select__caret) {
  font-size: 11px;
  color: #94a3b8;
}

.bi-chart-sort-control__divider {
  width: 1px;
  height: 14px;
  background: #e2e8f0;
  margin: 0 3px;
  flex-shrink: 0;
}

.bi-chart-sort-control__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 4px;
  color: #475467;
  cursor: pointer;
  transition: all 0.15s ease;
  flex-shrink: 0;
}

.bi-chart-sort-control__btn:hover {
  background: #ffffff;
  color: #1e40af;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.bi-chart-sort-control__btn.is-asc {
  color: #1e40af;
}
</style>