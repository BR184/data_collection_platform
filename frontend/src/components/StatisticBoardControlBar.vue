<script setup lang="ts">
import { computed } from 'vue';
import type { StatisticBoardControlSpec } from '../feature-manifest/statistic-board-controls';

// "分析设置"中的行维度控件：只按页面配置渲染，不判断具体看板键。
// 行维度决定聚合结构而不是过滤条件；客户/模块/功能成员选择已并入共用"常用筛选"行
// （见 StatisticBoardMemberFilters），因此这里只承担行维度。
const props = defineProps<{
  spec: StatisticBoardControlSpec;
  activeDimension: string;
  disabled?: boolean;
  onDimensionChange: (value: string) => void;
}>();

const dimensionModel = computed({
  get: () => props.activeDimension,
  set: (value: string) => props.onDimensionChange(value),
});
</script>

<template>
  <div class="stat-board-controls">
    <label class="stat-board-controls__field">
      <span class="stat-board-controls__label">{{ spec.dimensionLabel }}</span>
      <el-select
        v-model="dimensionModel"
        size="small"
        class="stat-board-controls__select"
        :disabled="disabled"
      >
        <el-option
          v-for="option in spec.dimensionOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </label>
  </div>
</template>

<style scoped>
.stat-board-controls {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.stat-board-controls__field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.stat-board-controls__label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.stat-board-controls__select {
  width: 150px;
}
</style>
