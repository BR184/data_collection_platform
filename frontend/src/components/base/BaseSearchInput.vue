<script setup lang="ts">
import { ref, watch } from 'vue';
// 基础搜索框统一关键词输入、清空和回车触发方式，避免记录页搜索体验不一致。
// 组件不做防抖和接口请求，节流策略交给使用方按页面场景决定。
import { Search } from '@element-plus/icons-vue';

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    placeholder?: string;
    clearable?: boolean;
    disabled?: boolean;
  }>(),
  {
    modelValue: '',
    placeholder: '',
    clearable: true,
    disabled: false,
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void;
  (event: 'change', value: string): void;
  (event: 'search', value: string): void;
  (event: 'clear'): void;
}>();

const draft = ref(props.modelValue);

watch(
  () => props.modelValue,
  (value) => {
    draft.value = value ?? '';
  },
);

function emitChange(value: string) {
  emit('update:modelValue', value);
  emit('change', value);
}

function handleModelUpdate(value: string) {
  draft.value = value;
  emit('update:modelValue', value);
}

function handleChange(value: string) {
  emitChange(String(value ?? ''));
}

function handleSearch() {
  emit('search', draft.value.trim());
}

function handleClear() {
  draft.value = '';
  emitChange('');
  emit('clear');
}
</script>

<template>
  <el-input
    :model-value="draft"
    :placeholder="placeholder"
    :clearable="clearable"
    :disabled="disabled"
    @update:model-value="handleModelUpdate"
    @change="handleChange"
    @keyup.enter="handleSearch"
    @clear="handleClear"
  >
    <template #prefix>
      <el-icon><Search /></el-icon>
    </template>
  </el-input>
</template>
