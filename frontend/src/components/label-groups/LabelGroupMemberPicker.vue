<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import type { LabelGroupMember, LabelValuePage } from '../../types/api';
import { api } from '../../api';
import { inferValueType } from '../../views/label-groups/label-group-settings';

const props = withDefaults(defineProps<{
  modelValue: LabelGroupMember[];
  dimensionKey?: string;
  valueType?: string | null;
  disabled?: boolean;
  fetchValues?: (dimensionKey: string, keyword: string) => Promise<LabelValuePage>;
}>(), {
  dimensionKey: '',
  valueType: null,
  disabled: false,
  fetchValues: undefined,
});

const emit = defineEmits<{
  (event: 'update:modelValue', value: LabelGroupMember[]): void;
}>();

const loading = ref(false);
const keyword = ref('');
const candidates = ref<LabelGroupMember[]>([]);

const selectedValues = computed({
  get: () => props.modelValue.map((member) => member.value),
  set: (values: string[]) => {
    const nextMembers = values.map((value) => {
      const existing = props.modelValue.find((member) => member.value === value);
      if (existing) {
        return existing;
      }
      const candidate = candidates.value.find((member) => member.value === value);
      return candidate ?? { value, label: value };
    });
    emit('update:modelValue', nextMembers);
  },
});

const candidateOptions = computed(() => {
  const selected = new Set(props.modelValue.map((member) => member.value));
  const merged = new Map<string, LabelGroupMember>();
  for (const item of candidates.value) {
    if (!selected.has(item.value)) {
      merged.set(item.value, item);
    }
  }
  return Array.from(merged.values());
});

const unavailableMembers = computed(() => props.modelValue.filter((member) => member.currentAvailable === false));

watch(
  () => props.dimensionKey,
  async (dimensionKey) => {
    candidates.value = [];
    keyword.value = '';
    if (dimensionKey) {
      await loadCandidates('');
    }
  },
  { immediate: true },
);

async function loadCandidates(nextKeyword: string) {
  keyword.value = nextKeyword;
  if (!props.dimensionKey) {
    candidates.value = [];
    return;
  }
  loading.value = true;
  try {
    const fetcher = props.fetchValues ?? defaultFetchValues;
    const response = await fetcher(props.dimensionKey, nextKeyword);
    candidates.value = response.items.map((item) => ({
      value: item.value,
      label: item.label,
      currentAvailable: true,
    })).filter((item) => !props.valueType || inferValueType(item.value) === props.valueType);
  } finally {
    loading.value = false;
  }
}

function defaultFetchValues(dimensionKey: string, searchKeyword: string) {
  return api.listLabelDimensionValues(dimensionKey, {
    keyword: searchKeyword,
    page: 1,
    size: 50,
  });
}
</script>

<template>
  <div class="label-member-picker">
      <el-select
        v-model="selectedValues"
        class="label-member-picker-select"
        multiple
        filterable
        remote
        allow-create
        default-first-option
        reserve-keyword
        collapse-tags
        collapse-tags-tooltip
        :loading="loading"
        :disabled="disabled"
        :remote-method="loadCandidates"
        placeholder="搜索候选值或直接输入自定义值"
        no-data-text="暂无可选标签值"
        no-match-text="未找到匹配的标签值"
      >
        <el-option
          v-for="item in candidateOptions"
          :key="item.value"
          :label="item.label || item.value"
          :value="item.value"
          :disabled="item.currentAvailable === false"
        >
          <div class="label-member-option">
            <span>{{ item.label || item.value }}</span>
            <el-tag v-if="item.currentAvailable === false" size="small" type="warning">当前数据中暂无命中</el-tag>
          </div>
        </el-option>
      </el-select>
      <div class="label-member-picker-foot">
        <span>已选 {{ modelValue.length }} 个成员</span>
        <span v-if="unavailableMembers.length" class="label-member-warning">
          {{ unavailableMembers.length }} 个成员当前数据中暂无命中
        </span>
      </div>
      <div v-if="modelValue.length" class="label-member-selected-list">
        <el-tag
          v-for="member in modelValue"
          :key="member.value"
          :type="member.currentAvailable === false ? 'warning' : 'primary'"
          closable
          :disable-transitions="true"
          @close="selectedValues = selectedValues.filter((value) => value !== member.value)"
        >
          {{ member.label || member.value }}
        </el-tag>
      </div>
  </div>
</template>

<style scoped>
.label-member-picker {
  display: grid;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.label-member-picker-select {
  width: 100%;
}

.label-member-picker :deep(.el-select) {
  width: 100%;
}

.label-member-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.label-member-picker-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: rgba(0, 0, 0, 0.45);
  font-size: 12px;
}

.label-member-warning {
  color: #ad6800;
}

.label-member-selected-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
