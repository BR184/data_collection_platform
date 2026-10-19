<script setup lang="ts">
import { computed } from 'vue';
import RecordTableFilterFields from './base/RecordTableFilterFields.vue';
import type { RecordTableFilterField, RecordTableFilterValue } from '../types/record-table';
import type { StatisticBoardControlOptionsStatus } from '../composables/useStatisticBoardControlParams';

/**
 * "常用筛选"行内的成员选择（客户/模块/功能）。
 *
 * <p>成员选择是控制参数而不是业务条件，因此这里只复用共用快速筛选控件的外观与清除行为，
 * 变更事件上抛给页面直接写入控制参数，不经过 `filterGroup`。三种非就绪状态必须分开表达：
 * 加载中、加载失败（可重试）、候选就绪但确实没有成员；候选不可用时只禁用下拉，不清空已接受的选择。
 */
const props = withDefaults(defineProps<{
  filters: RecordTableFilterField[];
  values: Record<string, unknown>;
  /** 候选求解状态；null 表示当前页面没有成员控制项。 */
  status?: StatisticBoardControlOptionsStatus | null;
  /** 整批画面准备失败且没有可继续服务的已接受画面。 */
  failed?: boolean;
  /** 失败原因或范围不可判定的原因。 */
  candidateMessage?: string;
  /** 候选就绪但确实没有成员时的说明文案。 */
  emptyNotice?: string;
  /** 路由中的成员参数非法时的告警文案。 */
  selectionError?: string;
  retrying?: boolean;
}>(), {
  status: null,
  failed: false,
  candidateMessage: '',
  emptyNotice: '',
  selectionError: '',
  retrying: false,
});

const emit = defineEmits<{
  (event: 'filterChange', payload: { key: string; value: RecordTableFilterValue }): void;
  (event: 'retry'): void;
}>();

const disabledKeys = computed(() => (props.failed || props.status !== 'ready'
  ? props.filters.map((filter) => filter.key)
  : []));

const candidateStateText = computed(() => {
  if (props.failed) {
    return props.candidateMessage || '成员候选加载失败，请重试';
  }
  if (props.status === 'unavailable') {
    return props.candidateMessage || '当前范围的成员候选暂不可用';
  }
  if (props.status === 'loading') {
    return '正在加载成员候选…';
  }
  if (props.status === 'ready' && props.emptyNotice) {
    return props.emptyNotice;
  }
  return '';
});

const retryVisible = computed(() => props.failed || props.status === 'unavailable');
</script>

<template>
  <div class="stat-board-member-filters" data-testid="member-filters">
    <RecordTableFilterFields
      :filters="filters"
      :filter-values="values"
      :input-drafts="{}"
      :disabled-keys="disabledKeys"
      @filter-change="(key, value) => emit('filterChange', { key, value })"
    />
    <span
      v-if="candidateStateText"
      class="stat-board-member-filters__state"
      data-testid="member-candidates-state"
    >{{ candidateStateText }}</span>
    <el-button
      v-if="retryVisible"
      size="small"
      class="app-action-button"
      plain
      :loading="retrying"
      data-testid="member-candidates-retry"
      @click="emit('retry')"
    >重试</el-button>
    <span
      v-if="selectionError"
      class="stat-board-member-filters__state"
      data-testid="member-selection-error"
      role="alert"
    >{{ selectionError }}</span>
  </div>
</template>

<style scoped>
/* display: contents 让成员项与字段类快速筛选在同一个 flex 行内平铺，避免多出一层盒子改变间距。 */
.stat-board-member-filters {
  display: contents;
}

.stat-board-member-filters__state {
  color: var(--app-color-warning, #b8860b);
  font-size: 12px;
}
</style>
