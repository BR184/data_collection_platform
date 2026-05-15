<script setup lang="ts">
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

function isStructuredCellValue(value: StatisticDetailCellValue): value is StatisticDetailLinkValue {
  return value != null && typeof value === 'object' && 'label' in value;
}

function detailCellLabel(record: Record<string, unknown>, column: StatisticDetailColumn) {
  const value = props.detailCellValue(record, column);
  if (isStructuredCellValue(value)) {
    return value.label || '-';
  }
  return value;
}

function detailCellLink(record: Record<string, unknown>, column: StatisticDetailColumn) {
  const value = props.detailCellValue(record, column);
  if (!isStructuredCellValue(value) || !value.href) {
    return null;
  }
  return value.href;
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
        :data="detail.records"
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
          <template #default="{ row }">
            <a
              v-if="detailCellLink(row, column)"
              class="detail-cell-link"
              :href="detailCellLink(row, column) || undefined"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ detailCellLabel(row, column) }}
            </a>
            <span v-else class="detail-cell-text">{{ detailCellLabel(row, column) }}</span>
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
