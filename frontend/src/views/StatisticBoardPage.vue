<script setup lang="ts">
import { computed } from 'vue';
// 统计板路由页把 feature manifest 中的看板键映射到统一看板组件。
// 这样新增看板时优先补配置，而不是再复制一个独立页面。
import { useRoute } from 'vue-router';
import StatisticBoardView from '../components/StatisticBoardView.vue';
import { getStatisticBoardKey, type PageKey } from '../feature-manifest';

const route = useRoute();

const boardKey = computed(
  () => getStatisticBoardKey(route.meta.pageKey as PageKey | undefined) ?? 'mirror-table-overview',
);
</script>

<template>
  <StatisticBoardView :board-key="boardKey" />
</template>
