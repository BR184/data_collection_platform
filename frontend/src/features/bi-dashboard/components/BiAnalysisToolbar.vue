<script setup lang="ts">
import type { PropType } from 'vue';

export interface BiToolbarOption {
  label: string;
  value: string;
}

defineProps({
  limit: { type: [String, Number] as PropType<string | number>, default: 'all' },
  sort: { type: String, default: '' },
  search: { type: String, default: '' },
  status: { type: String, default: '' },
  limitOptions: { type: Array as PropType<BiToolbarOption[]>, default: () => [] },
  sortOptions: { type: Array as PropType<BiToolbarOption[]>, default: () => [] },
  statusOptions: { type: Array as PropType<BiToolbarOption[]>, default: () => [] },
  searchPlaceholder: { type: String, default: '搜索' },
});

const emit = defineEmits<{
  (event: 'update:limit', value: string | number): void;
  (event: 'update:sort', value: string): void;
  (event: 'update:search', value: string): void;
  (event: 'update:status', value: string): void;
}>();
</script>

<template>
  <div class="bi-analysis-toolbar" aria-label="图表分析控制">
    <el-select
      v-if="statusOptions.length"
      class="bi-analysis-control"
      :model-value="status"
      size="small"
      aria-label="状态范围"
      @update:model-value="emit('update:status', $event)"
    >
      <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-select
      v-if="limitOptions.length"
      class="bi-analysis-control"
      :model-value="String(limit)"
      size="small"
      aria-label="显示数量"
      @update:model-value="emit('update:limit', $event)"
    >
      <el-option v-for="item in limitOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-select
      v-if="sortOptions.length"
      class="bi-analysis-control"
      :model-value="sort"
      size="small"
      aria-label="排序方式"
      @update:model-value="emit('update:sort', $event)"
    >
      <el-option v-for="item in sortOptions" :key="item.value" :label="item.label" :value="item.value" />
    </el-select>
    <el-input
      v-if="search !== undefined"
      class="bi-analysis-search"
      :model-value="search"
      size="small"
      clearable
      :placeholder="searchPlaceholder"
      :aria-label="searchPlaceholder"
      @update:model-value="emit('update:search', $event)"
    />
  </div>
</template>

<style scoped>
.bi-analysis-toolbar {
  display: flex;
  flex: 1 1 560px;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.bi-analysis-control { width: 132px; }
.bi-analysis-search { width: 154px; }

@media (max-width: 1179px) {
  .bi-analysis-toolbar { justify-content: flex-start; flex-basis: 100%; }
}

@media (max-width: 760px) {
  .bi-analysis-toolbar { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); width: 100%; }
  .bi-analysis-control,
  .bi-analysis-search { width: 100%; }
  .bi-analysis-search { grid-column: span 2; }
}
</style>
