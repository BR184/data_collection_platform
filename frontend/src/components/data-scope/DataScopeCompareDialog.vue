<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import SmartSelect from '../base/SmartSelect.vue';
import type { RecordTableFilterOption } from '../../types/record-table';
import type { DataScopeOption } from '../../types/data-scope';

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title: string;
    options: DataScopeOption[];
    primaryLabel?: string;
    secondaryLabel?: string;
    primaryPlaceholder?: string;
    secondaryPlaceholder?: string;
    confirmText?: string;
    loading?: boolean;
  }>(),
  {
    primaryLabel: '基准范围',
    secondaryLabel: '对比范围',
    primaryPlaceholder: '请选择基准范围',
    secondaryPlaceholder: '请选择对比范围',
    confirmText: '确认',
    loading: false,
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'confirm', payload: { primary: string; secondary: string }): void;
}>();

const primaryValue = ref('');
const secondaryValue = ref('');

const selectOptions = computed<RecordTableFilterOption[]>(() =>
  props.options.map((option) => ({
    label: option.label,
    value: option.value,
  })),
);

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      primaryValue.value = '';
      secondaryValue.value = '';
    }
  },
);

const canConfirm = computed(
  () =>
    !!primaryValue.value &&
    !!secondaryValue.value &&
    primaryValue.value !== secondaryValue.value &&
    !props.loading,
);

function closeDialog() {
  emit('update:modelValue', false);
}

function handleConfirm() {
  if (!canConfirm.value) {
    return;
  }
  emit('confirm', {
    primary: primaryValue.value,
    secondary: secondaryValue.value,
  });
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="520px"
    :close-on-click-modal="!loading"
    @close="closeDialog"
  >
    <div class="data-scope-compare-dialog">
      <div class="data-scope-compare-field">
        <span class="data-scope-compare-field__label">{{ primaryLabel }}</span>
        <SmartSelect
          v-model="primaryValue"
          :options="selectOptions"
          :placeholder="primaryPlaceholder"
          :disabled="loading"
        />
      </div>
      <div class="data-scope-compare-field">
        <span class="data-scope-compare-field__label">{{ secondaryLabel }}</span>
        <SmartSelect
          v-model="secondaryValue"
          :options="selectOptions"
          :placeholder="secondaryPlaceholder"
          :disabled="loading"
        />
      </div>
    </div>
    <template #footer>
      <el-button :disabled="loading" @click="closeDialog">取消</el-button>
      <el-button type="primary" :loading="loading" :disabled="!canConfirm" @click="handleConfirm">
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.data-scope-compare-dialog {
  display: grid;
  gap: 16px;
}

.data-scope-compare-field {
  display: grid;
  gap: 8px;
}

.data-scope-compare-field__label {
  color: rgba(15, 23, 42, 0.72);
  font-size: 13px;
  font-weight: 600;
}
</style>
