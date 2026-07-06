<script setup lang="ts">
import { computed } from 'vue';
import { resolveRecordTableCellDisplay } from './base-record-table-cell';
import type { RecordTableColumn } from '../../types/record-table';

const props = defineProps<{
  column: RecordTableColumn;
  value: unknown;
  align?: 'left' | 'center' | 'right';
}>();

const cell = computed(() => resolveRecordTableCellDisplay(props.value));
const cellAlign = computed(() => props.align ?? props.column.align ?? 'center');
</script>

<template>
  <div
    v-if="column.type === 'tags'"
    class="record-table-tags"
    :class="`record-table-tags--${cellAlign}`"
  >
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
    :class="`record-table-link--${cellAlign}`"
    :href="cell.link.href"
    target="_blank"
    rel="noreferrer"
  >
    {{ cell.link.label }}
  </a>

  <span v-else-if="column.type === 'link'" class="record-table-empty">-</span>

  <span v-else class="record-table-text" :class="`record-table-text--${cellAlign}`">{{ cell.text }}</span>
</template>

<style scoped>
.record-table-tags {
  display: flex;
  gap: 4px 6px;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  flex: 1 1 100%;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  overflow: visible;
  line-height: 1.4;
}

.record-table-tags--left {
  justify-content: flex-start;
}

.record-table-tags--right {
  justify-content: flex-end;
}

.record-table-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  max-width: 100%;
  color: #2563eb;
  text-decoration: none;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-table-link--left {
  justify-content: flex-start;
  text-align: left;
}

.record-table-link--right {
  justify-content: flex-end;
  text-align: right;
}

.record-table-link:hover {
  text-decoration: underline;
}

.record-table-text,
.record-table-empty {
  display: block;
  width: 100%;
  max-width: 100%;
  color: rgba(0, 0, 0, 0.88);
  vertical-align: middle;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.record-table-empty {
  color: rgba(0, 0, 0, 0.45);
}

.record-table-text--left {
  text-align: left;
}

.record-table-text--right {
  text-align: right;
}

.record-table-tags :deep(.el-tag) {
  flex: 0 1 auto;
  max-width: 100%;
  margin: 0;
}

.record-table-tags :deep(.el-tag__content) {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
