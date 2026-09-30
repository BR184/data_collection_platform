<script setup lang="ts">
import { computed } from 'vue';
import { formatLocalDateTime } from '../utils/beijing-time';

const props = withDefaults(
  defineProps<{
    /** 降级产出的数据时刻（ISO 文本）；为空表示本次产出没有新鲜度提示。 */
    dataAsOf?: string | null;
    /** 尚未发布到最新变化版本的稳定根数量；0 表示已是最新。 */
    pendingUpdates?: number;
  }>(),
  {
    dataAsOf: null,
    pendingUpdates: 0,
  },
);

const pendingUpdates = computed(() => Math.max(0, Number(props.pendingUpdates ?? 0)));
const visible = computed(() => pendingUpdates.value > 0);
const dataAsOfText = computed(() => formatLocalDateTime(props.dataAsOf, '未知时刻'));
const bannerTitle = computed(
  () => `数据截至 ${dataAsOfText.value}，仍有 ${pendingUpdates.value} 项待更新`,
);
</script>

<template>
  <div
    v-if="visible"
    class="stat-board-freshness-banner"
    data-testid="stat-board-freshness-banner"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      :title="bannerTitle"
      description="统计结果只覆盖已完成发布的变更；来源完成最新变更发布后会自动更新，也可以稍后手动刷新本页。"
    />
  </div>
</template>
