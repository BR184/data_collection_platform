<script setup lang="ts">
import { computed } from 'vue';
import type { StatisticBoardControlOptionsStatus } from '../composables/useStatisticBoardControlParams';
import type {
  StatisticBoardControlSpec,
  StatisticBoardMemberControl,
} from '../feature-manifest/statistic-board-controls';

// 行维度与成员选择控件：只按页面配置渲染，不判断具体看板键。
// 成员选项一律来自后端 control-options（同一窄事实在完整基础范围上求解），因此选择某个成员后，
// 其余成员不会被挤出候选；候选不可用时显示原因，不渲染成"该范围没有成员"。
const props = defineProps<{
  spec: StatisticBoardControlSpec;
  activeDimension: string;
  memberControls: StatisticBoardMemberControl[];
  /** 候选求解状态；null 表示当前页面没有成员控制项。 */
  optionsStatus?: StatisticBoardControlOptionsStatus | null;
  optionsMessage?: string;
  memberSelectionError?: string;
  disabled?: boolean;
  onDimensionChange: (value: string) => void;
  onMemberChange: (key: StatisticBoardMemberControl['key'], value: string) => void;
}>();

const optionsUnavailable = computed(
  () => props.optionsStatus === 'unavailable' && Boolean(props.optionsMessage),
);
const memberSelectorsDisabled = computed(() => props.disabled || props.optionsStatus !== 'ready');

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
    <label
      v-for="member in memberControls"
      :key="member.key"
      class="stat-board-controls__field"
    >
      <span class="stat-board-controls__label">{{ member.label }}</span>
      <el-select
        :model-value="member.value"
        size="small"
        clearable
        class="stat-board-controls__select"
        :placeholder="`全部${member.label}`"
        :disabled="memberSelectorsDisabled"
        @update:model-value="(value: string) => onMemberChange(member.key, String(value ?? ''))"
      >
        <el-option
          v-for="option in member.options"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </label>
    <span
      v-if="optionsUnavailable"
      class="stat-board-controls__state"
      data-testid="control-options-state"
    >{{ props.optionsMessage }}</span>
    <span
      v-if="memberSelectionError"
      class="stat-board-controls__state"
      data-testid="member-selection-error"
      role="alert"
    >{{ memberSelectionError }}</span>
  </div>
</template>

<style scoped>
.stat-board-controls {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.stat-board-controls__state {
  color: var(--app-color-warning, #b8860b);
  font-size: 12px;
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
