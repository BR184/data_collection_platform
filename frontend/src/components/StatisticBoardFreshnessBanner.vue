<script setup lang="ts">
import { computed } from 'vue';
import { formatLocalDateTime } from '../utils/beijing-time';
import type { ReadViewMetadata } from '../types/api/read-view';

const props = withDefaults(
  defineProps<{
    /** 降级产出的数据时刻（ISO 文本）；为空表示本次产出没有新鲜度提示。 */
    dataAsOf?: string | null;
    /** 尚未发布到最新变化版本的稳定根数量；0 表示已是最新。 */
    pendingUpdates?: number;
    sourceState?: ReadViewMetadata['sourceState'];
    sourceStateDetail?: string | null;
    escalated?: boolean;
  }>(),
  {
    dataAsOf: null,
    pendingUpdates: 0,
    sourceState: 'CURRENT',
    sourceStateDetail: null,
    escalated: false,
  },
);

const pendingUpdates = computed(() => Math.max(0, Number(props.pendingUpdates ?? 0)));
const visible = computed(() => pendingUpdates.value > 0 || props.sourceState !== 'CURRENT' || props.escalated);
const dataAsOfText = computed(() => formatLocalDateTime(props.dataAsOf, '未知时刻'));
const bannerTitle = computed(
  () => `数据截至 ${dataAsOfText.value}${pendingUpdates.value > 0 ? `，仍有 ${pendingUpdates.value} 项待更新` : ''}`,
);
const description = computed(() => {
  const state = props.sourceState === 'NEWER_AVAILABLE' ? '已有更新数据，可以刷新本页。'
    : props.sourceState === 'ATTENTION' ? '数据更新暂未完成，当前完整数据可继续使用。'
      : '数据正在更新，当前完整数据可继续使用。';
  return props.sourceStateDetail ? `${state} ${props.escalated ? '更新已超过提醒时限。' : ''}${props.sourceStateDetail}` : state;
});
</script>

<template>
  <div
    v-if="visible"
    class="stat-board-freshness-banner"
    data-testid="stat-board-freshness-banner"
  >
    <el-alert
      :type="escalated ? 'warning' : 'info'"
      :closable="false"
      show-icon
      :title="bannerTitle"
      :description="description"
    />
  </div>
</template>
