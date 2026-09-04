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
  fetchValues?: (dimensionKey: string, keyword: string, page: number, size: number) => Promise<LabelValuePage>;
}>(), {
  dimensionKey: '',
  valueType: null,
  disabled: false,
  fetchValues: undefined,
});

const emit = defineEmits<{
  (event: 'update:modelValue', value: LabelGroupMember[]): void;
}>();

// 200 是后端 values 接口的单页上限，超出会被静默收敛到 200
const CANDIDATE_PAGE_SIZE = 200;

const loading = ref(false);
const keyword = ref('');
const candidates = ref<LabelGroupMember[]>([]);
// remote-method 高频触发，令牌递增用于丢弃旧一轮分页循环的在途结果
let loadToken = 0;

const selectedValues = computed({
  get: () => props.modelValue.map((member) => member.value),
  set: (values: string[]) => {
    const nextMembers = values
      .map((value) => {
        const existing = props.modelValue.find((member) => member.value === value);
        if (existing) {
          return existing;
        }
        const candidate = candidates.value.find((member) => member.value === value);
        if (candidate) {
          return candidate;
        }
        const normalized = value.trim();
        if (!normalized || (props.valueType && inferValueType(normalized) !== props.valueType)) {
          return null;
        }
        return {
          value: normalized,
          label: normalized,
          currentAvailable: false,
        };
      })
      .filter((member): member is LabelGroupMember => Boolean(member));
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
  async () => {
    candidates.value = [];
    await loadCandidates('');
  },
  { immediate: true },
);

async function loadCandidates(nextKeyword: string): Promise<void> {
  const token = ++loadToken;
  keyword.value = nextKeyword;
  loading.value = true;
  try {
    if (!props.dimensionKey) {
      candidates.value = [];
      return;
    }
    const fetcher = props.fetchValues ?? defaultFetchValues;
    const collected = new Map<string, LabelGroupMember>();
    for (let page = 1; ; page += 1) {
      const response = await fetcher(props.dimensionKey, nextKeyword, page, CANDIDATE_PAGE_SIZE);
      if (token !== loadToken) {
        return;
      }
      for (const item of response.items) {
        if (!collected.has(item.value)) {
          collected.set(item.value, { value: item.value, label: item.label, currentAvailable: true });
        }
      }
      if (response.items.length === 0 || collected.size >= response.total) {
        break;
      }
    }
    candidates.value = Array.from(collected.values())
      .filter((item) => !props.valueType || inferValueType(item.value) === props.valueType);
  } finally {
    if (token === loadToken) {
      loading.value = false;
    }
  }
}

function defaultFetchValues(dimensionKey: string, searchKeyword: string, page: number, size: number): Promise<LabelValuePage> {
  return api.listLabelDimensionValues(dimensionKey, {
    keyword: searchKeyword,
    page,
    size,
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
        reserve-keyword
        :collapse-tags="false"
        :collapse-tags-tooltip="false"
        :loading="loading"
        :disabled="disabled"
        allow-create
        default-first-option
        fit-input-width
        :remote-method="loadCandidates"
        popper-class="label-member-picker-dropdown smart-select-dropdown smart-select-dropdown--compact smart-select-dropdown--compact-multiple"
        :placeholder="dimensionKey ? '搜索并选择候选成员，也可直接输入' : '直接输入成员，或先选择候选来源再搜索'"
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

.label-member-picker :deep(.el-select__wrapper) {
  align-items: center;
  min-height: 32px;
  height: auto;
  padding-top: 4px;
  padding-bottom: 4px;
}

.label-member-picker :deep(.el-select__selection) {
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.label-member-picker :deep(.el-select__selected-item) {
  max-width: 100%;
  margin: 0;
}

.label-member-picker :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: 22px;
}

.label-member-picker :deep(.el-tag__content) {
  max-width: 100%;
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  line-height: 1.3;
  word-break: break-word;
}

.label-member-picker :deep(.el-select__placeholder) {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  line-height: 22px;
}

.label-member-option {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: auto;
  max-width: 100%;
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

</style>
