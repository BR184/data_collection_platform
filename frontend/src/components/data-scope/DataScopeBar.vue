<script setup lang="ts">
import SmartSelect from '../base/SmartSelect.vue';
// 数据范围条承载全局项目、阶段或数据源选择，让看板和记录页共享同一范围语义。
// 组件只负责选择器展示，范围变更后的路由清理和重新加载由 shell 状态处理。
import type { RecordTableFilterOption } from '../../types/record-table';
import type { DataScopeOption, DataScopeProvider } from '../../types/data-scope';

const props = withDefaults(
  defineProps<{
    provider: DataScopeProvider;
    options: DataScopeOption[];
    modelValue: string;
    loading?: boolean;
    disabled?: boolean;
    summary?: string;
  }>(),
  {
    loading: false,
    disabled: false,
    summary: '',
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void;
  (event: 'change', value: string): void;
}>();

function toSelectOptions(options: DataScopeOption[]): RecordTableFilterOption[] {
  return options.map((option) => ({
    label: option.label,
    value: option.value,
  }));
}

function handleSelectChange(value: string | string[]) {
  const nextValue = Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '');
  emit('update:modelValue', nextValue);
  emit('change', nextValue);
}
</script>

<template>
  <div class="data-scope-bar">
    <div class="data-scope-bar__main">
      <span class="data-scope-bar__label">{{ provider.label }}</span>

      <SmartSelect
        v-if="provider.mode === 'single-select'"
        :model-value="modelValue"
        :options="toSelectOptions(options)"
        :placeholder="provider.placeholder || provider.label"
        :clearable="provider.clearable ?? true"
        :compact="provider.compact ?? false"
        :disabled="disabled"
        :loading="loading"
        class="data-scope-bar__select"
        @change="handleSelectChange"
      />

      <el-radio-group
        v-else-if="provider.mode === 'segmented'"
        :model-value="modelValue"
        class="data-scope-bar__segmented"
        size="small"
        @change="handleSelectChange"
      >
        <el-radio-button
          v-for="option in options"
          :key="option.value"
          :label="option.value"
          :disabled="disabled || option.disabled"
        >
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>

      <el-cascader
        v-else
        :model-value="modelValue"
        :options="options"
        :props="{ checkStrictly: true, emitPath: false, value: 'value', label: 'label', children: 'children' }"
        :placeholder="provider.placeholder || provider.label"
        :clearable="provider.clearable ?? true"
        :disabled="disabled"
        class="data-scope-bar__cascader"
        @change="handleSelectChange"
      />
    </div>

    <span v-if="summary" class="data-scope-bar__summary">{{ summary }}</span>
  </div>
</template>

<style scoped>
.data-scope-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
}

.data-scope-bar__main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.data-scope-bar__label {
  flex: 0 0 auto;
  color: rgba(15, 23, 42, 0.62);
  font-size: 12px;
  font-weight: 600;
}

.data-scope-bar__select,
.data-scope-bar__cascader {
  width: 240px;
}

.data-scope-bar__segmented :deep(.el-radio-button__inner) {
  min-width: 72px;
}

.data-scope-bar__summary {
  color: rgba(15, 23, 42, 0.56);
  font-size: 12px;
  line-height: 1.5;
}
</style>
