<script setup lang="ts">
import { computed } from 'vue';
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
