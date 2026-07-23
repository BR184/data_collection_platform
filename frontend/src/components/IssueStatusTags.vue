<script setup lang="ts">
import { computed } from 'vue';
import { parseIssueStatusMembers } from '../utils/issue-status-members';

const props = withDefaults(
  defineProps<{
    value?: string | null;
    emptyText?: string;
  }>(),
  {
    value: '',
    emptyText: '-',
  },
);

const members = computed(() => parseIssueStatusMembers(props.value));
</script>

<template>
  <span class="issue-status-tags">
    <el-tag
      v-for="member in members"
      :key="member"
      class="issue-status-tag"
      size="small"
      type="primary"
      effect="plain"
      :title="member"
    >
      {{ member }}
    </el-tag>
    <span v-if="!members.length" class="issue-status-empty">{{ emptyText }}</span>
  </span>
</template>

<style scoped>
.issue-status-tags {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  min-width: 0;
}

.issue-status-tag {
  max-width: 100%;
  height: auto;
  min-height: 22px;
  white-space: normal;
}

.issue-status-tag :deep(.el-tag__content) {
  line-height: 16px;
  overflow-wrap: anywhere;
}

.issue-status-empty {
  color: var(--el-text-color-placeholder);
}
</style>
