<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import StatisticBoardView from '../components/StatisticBoardView.vue';

const route = useRoute();

const boardKey = computed(() => {
  if (route.meta.pageKey === 'question-metrics-home') {
    return 'system-test-defect-summary';
  }
  if (route.meta.pageKey === 'customer-issues-home') {
    return 'customer-issue-defect-summary';
  }
  if (route.meta.pageKey === 'question-metrics-delay-analysis') {
    return 'system-test-delay-analysis';
  }
  if (route.meta.pageKey === 'question-metrics-defect-cause') {
    return 'system-test-defect-cause';
  }
  if (route.meta.pageKey === 'customer-issues-defect-cause') {
    return 'customer-issue-defect-cause';
  }
  if (route.meta.pageKey === 'question-metrics-phase-statistics') {
    return 'system-test-phase-statistics';
  }
  return 'mirror-table-overview';
});
</script>

<template>
  <StatisticBoardView :board-key="boardKey" />
</template>
