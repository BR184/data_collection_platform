<script setup lang="ts">
import { EditPen, Plus } from '@element-plus/icons-vue';
// 问题项面板挂在评审记录行下方，用于展示专家问题列表和行内新增入口。
// 它只接收父级传入的问题数据，实际加载与保存流程交给 review-data composable。
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import type { RecordTableColumn } from '../../types/record-table';

defineProps<{
  record: ReviewDataRecordRowResponse;
  loading: boolean;
  rows: Record<string, unknown>[];
  columns: RecordTableColumn[];
  onCreateProblemItem: (recordId: number) => void | Promise<void>;
  onEditProblemItem: (recordId: number, item: ReviewDataProblemItemResponse) => void | Promise<void>;
  onDeleteProblemItem: (recordId: number, itemId: number) => void | Promise<void>;
}>();
</script>

<template>
  <div class="problem-panel">
    <div class="problem-panel-head">
      <div class="problem-panel-title">
        <span>评审问题清单</span>
        <el-tag size="small" effect="plain">共 {{ record.problemCount }} 条</el-tag>
      </div>
      <el-button type="primary" text :icon="Plus" @click="onCreateProblemItem(record.id)">新增问题</el-button>
    </div>

    <div class="problem-subtable-frame">
      <el-table
      v-loading="loading"
      :data="rows"
      class="problem-subtable"
      border
      stripe
      empty-text="当前评审下还没有录入问题清单。"
    >
      <el-table-column
        v-for="column in columns"
        :key="column.key"
        :prop="column.key"
        :label="column.label"
        :width="column.width"
        :min-width="column.minWidth"
        :align="column.align ?? 'left'"
        :show-overflow-tooltip="column.showOverflowTooltip ?? true"
      >
        <template #default="{ row }">
          <template v-if="column.type === 'tag'">
            <el-tag
              v-for="tag in row[column.key] as Array<{ label: string; type?: 'success' | 'warning' | 'info' | 'danger' | 'primary' }>"
              :key="tag.label"
              size="small"
              :type="tag.type ?? 'info'"
              effect="plain"
            >
              {{ tag.label }}
            </el-tag>
          </template>
          <span v-else>{{ row[column.key] }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="136" fixed="right" align="center">
        <template #default="{ row }">
          <div class="problem-actions">
            <el-button
              class="problem-action-edit"
              type="primary"
              plain
              size="small"
              :icon="EditPen"
              @click="onEditProblemItem(record.id, row.__raw as ReviewDataProblemItemResponse)"
            >
              编辑
            </el-button>
            <el-button
              class="problem-action-delete"
              type="danger"
              text
              size="small"
              @click="onDeleteProblemItem(record.id, (row.__raw as ReviewDataProblemItemResponse).id)"
            >
              删除
            </el-button>
          </div>
        </template>
      </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.problem-panel {
  display: grid;
  gap: 12px;
  width: min(100%, calc(100vw - 304px));
  padding: 12px 16px 14px;
  background: rgba(248, 250, 252, 0.72);
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.04);
  overflow: hidden;
  transform-origin: top center;
  animation: problem-panel-drawer-in 340ms cubic-bezier(0.22, 1, 0.36, 1);
  animation-fill-mode: backwards;
}

.problem-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.problem-panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: rgba(15, 23, 42, 0.82);
}

.problem-subtable-frame {
  width: 100%;
  min-width: 0;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-gutter: stable;
}

.problem-subtable {
  min-width: 1280px;
  border-radius: 12px;
  overflow: hidden;
}

.problem-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
}

.problem-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.problem-action-edit {
  height: 26px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.2);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
}

.problem-action-edit:hover,
.problem-action-edit:focus {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}

.problem-action-delete {
  height: 26px;
  padding: 0 6px;
  color: rgba(220, 38, 38, 0.72);
  font-size: 12px;
  font-weight: 500;
}

.problem-action-delete:hover,
.problem-action-delete:focus {
  background: rgba(220, 38, 38, 0.08);
  color: #dc2626;
}

:deep(.problem-subtable .el-table__header th.el-table__cell) {
  background: #f8fafc;
}

@keyframes problem-panel-drawer-in {
  from {
    max-height: 0;
    opacity: 0;
    transform: translateY(-8px) scaleY(0.98);
    box-shadow: 0 0 0 rgba(15, 23, 42, 0);
  }

  70% {
    max-height: 520px;
    opacity: 1;
  }

  to {
    max-height: 960px;
    opacity: 1;
    transform: translateY(0) scaleY(1);
    box-shadow: 0 10px 24px rgba(15, 23, 42, 0.04);
  }
}

@media (prefers-reduced-motion: reduce) {
  .problem-panel {
    animation: none;
  }
}
</style>
