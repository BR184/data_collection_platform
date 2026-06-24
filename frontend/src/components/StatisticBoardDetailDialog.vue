<script setup lang="ts">
import { computed } from 'vue';
import type {
  StatisticDetailCellValue,
  StatisticDetailColumn,
  StatisticDetailLinkValue,
  StatisticDetailResponse,
} from '../types/api';
// 统计板明细弹窗承接图表点击后的记录列表，保持和主看板一致的排序与分页语义。
// 弹窗只负责展示和导出，明细数据的筛选口径由父级传入的查询上下文决定。

const props = defineProps<{
  modelValue: boolean;
  loading: boolean;
  detail: StatisticDetailResponse | null;
  pagination: {
    page: number;
    size: number;
  };
  detailTableClass?: string;
  detailCellValue: (record: Record<string, unknown>, column: StatisticDetailColumn) => StatisticDetailCellValue;
  onSortChange: (event: { column: unknown; prop: string; order: 'ascending' | 'descending' | null }) => void;
  onCurrentChange: (page: number) => void;
  onSizeChange: (size: number) => void;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

interface DetailDisplayCell {
  label: string;
  href: string | null;
  tags: string[];
}

interface DetailDisplayRow {
  record: Record<string, unknown>;
  cells: Record<string, DetailDisplayCell>;
}

const detailRows = computed<DetailDisplayRow[]>(() =>
  (props.detail?.records ?? []).map((record) => ({
    record,
    cells: Object.fromEntries(
      (props.detail?.columns ?? []).map((column) => [column.key, createDetailCell(record, column)]),
    ),
  })),
);

function isStructuredCellValue(value: StatisticDetailCellValue): value is StatisticDetailLinkValue {
  return value != null && typeof value === 'object' && 'label' in value;
}

function createDetailCell(record: Record<string, unknown>, column: StatisticDetailColumn): DetailDisplayCell {
  const value = props.detailCellValue(record, column);
  const label = String(isStructuredCellValue(value) ? value.label || '-' : value ?? '-');
  const href = isStructuredCellValue(value) && value.href ? value.href : null;
  return {
    label,
    href,
    tags: splitTags(label),
  };
}

function splitTags(value: unknown) {
  const rawValue = String(value ?? '').trim();
  if (!rawValue || rawValue === '-') {
    return [];
  }
  return rawValue
    .split(/[、,，]/)
    .map((value) => value.trim())
    .filter(Boolean);
}

function tagType(label: string) {
  if (label.includes('一级') || label === '已关闭') {
    return 'danger';
  }
  if (label.includes('二级') || label.includes('申请延期') || label.includes('未修复')) {
    return 'warning';
  }
  if (label.includes('已修复') || label.includes('完成') || label === '未关闭') {
    return 'success';
  }
  return 'info';
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="detail?.title || '明细数据'"
    class="stat-detail-dialog"
    width="72%"
    top="8vh"
    align-center
    destroy-on-close
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="stat-detail-shell" v-loading="loading">
      <el-table
        v-if="detail"
        :data="detailRows"
        border
        stripe
        class="stat-detail-table"
        :class="detailTableClass"
        @sort-change="onSortChange"
      >
        <el-table-column
          v-for="column in detail.columns"
          :key="column.key"
          :prop="column.key"
          :label="column.label"
          :width="column.width || undefined"
          :min-width="column.minWidth || 140"
          :sortable="column.sortable ? 'custom' : false"
          show-overflow-tooltip
        >
          <template #default="{ row }: { row: DetailDisplayRow }">
            <div v-if="column.type === 'tags'" class="detail-cell-tags">
              <el-tag
                v-for="tag in row.cells[column.key]?.tags ?? []"
                :key="`${column.key}-${tag}`"
                size="small"
                :type="tagType(tag)"
                effect="plain"
              >
                {{ tag }}
              </el-tag>
              <span v-if="!(row.cells[column.key]?.tags ?? []).length" class="detail-cell-empty">-</span>
            </div>
            <el-tag
              v-else-if="column.type === 'tag' && row.cells[column.key]?.label"
              size="small"
              :type="tagType(row.cells[column.key]?.label ?? '')"
              effect="plain"
            >
              {{ row.cells[column.key]?.label }}
            </el-tag>
            <span v-else-if="column.type === 'tag'" class="detail-cell-empty">-</span>
            <a
              v-else-if="row.cells[column.key]?.href"
              class="detail-cell-link"
              :href="row.cells[column.key]?.href || undefined"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ row.cells[column.key]?.label }}
            </a>
            <span v-else class="detail-cell-text">{{ row.cells[column.key]?.label }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="detail-pagination">
        <el-pagination
          v-if="detail"
          :current-page="pagination.page"
          :page-size="pagination.size"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="detail.total"
          @current-change="onCurrentChange"
          @size-change="onSizeChange"
        />
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.detail-cell-link {
  color: #1677ff;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.detail-cell-link:hover {
  color: #4096ff;
}

.detail-cell-text {
  color: #1f2329;
}

.detail-cell-empty {
  color: rgba(31, 35, 41, 0.42);
}

.detail-cell-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-height: 24px;
}

.stat-detail-table :deep(.el-table__header th) {
  vertical-align: middle;
  padding: 8px 0;
}

.stat-detail-table :deep(.cell) {
  display: flex;
  align-items: center;
  min-height: 28px;
}

.stat-detail-table :deep(.el-table__column-filter-trigger),
.stat-detail-table :deep(.caret-wrapper) {
  width: 28px;
}

.stat-detail-table :deep(.caret-wrapper) {
  height: 28px;
  justify-content: center;
}
</style>
