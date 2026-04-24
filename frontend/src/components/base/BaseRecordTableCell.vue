<script setup lang="ts">
import { computed } from 'vue';
import { resolveRecordTableCellDisplay } from './base-record-table-cell';
import type { RecordTableColumn } from '../../types/record-table';

const props = defineProps<{
  column: RecordTableColumn;
  value: unknown;
}>();

const cell = computed(() => resolveRecordTableCellDisplay(props.value));
</script>

<template>
  <div v-if="column.type === 'tags'" class="record-table-tags">
    <el-tag
      v-for="tag in cell.tags"
      :key="`${column.key}-${tag.label}`"
      size="small"
      :type="tag.type ?? 'info'"
      effect="plain"
    >
      {{ tag.label }}
    </el-tag>
    <span v-if="!cell.tags.length" class="record-table-empty">-</span>
  </div>

  <el-tag
    v-else-if="column.type === 'tag' && cell.primaryTag"
    size="small"
    :type="cell.primaryTag.type ?? 'info'"
    effect="plain"
  >
    {{ cell.primaryTag.label }}
  </el-tag>

  <span v-else-if="column.type === 'tag'" class="record-table-empty">-</span>

  <a
    v-else-if="column.type === 'link' && cell.link"
    class="record-table-link"
    :href="cell.link.href"
    target="_blank"
    rel="noreferrer"
  >
    {{ cell.link.label }}
  </a>

  <span v-else-if="column.type === 'link'" class="record-table-empty">-</span>

  <span v-else class="record-table-text">{{ cell.text }}</span>
</template>

<style scoped>
.record-table-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: center;
}

.record-table-link {
  color: #2563eb;
  text-decoration: none;
}

.record-table-link:hover {
  text-decoration: underline;
}

.record-table-text,
.record-table-empty {
  color: rgba(0, 0, 0, 0.88);
}

.record-table-empty {
  color: rgba(0, 0, 0, 0.45);
}
</style>
