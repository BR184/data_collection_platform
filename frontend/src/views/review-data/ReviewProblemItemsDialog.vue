<script setup lang="ts">
import { Close } from '@element-plus/icons-vue';
import ReviewProblemPanel from './ReviewProblemPanel.vue';
import type { ReviewDataProblemItemResponse, ReviewDataRecordRowResponse } from '../../types/api';
import type { RecordTableColumn } from '../../types/record-table';

defineProps<{
  visible: boolean;
  record: ReviewDataRecordRowResponse | null;
  loading: boolean;
  rows: Record<string, unknown>[];
  columns: RecordTableColumn[];
  onCreateProblemItem: (recordId: number) => void | Promise<void>;
  onEditProblemItem: (recordId: number, item: ReviewDataProblemItemResponse) => void | Promise<void>;
  onDeleteProblemItem: (recordId: number, itemId: number) => void | Promise<void>;
  canManage?: boolean;
  canCreate?: boolean;
  canEdit?: boolean;
  canDelete?: boolean | ((row: Record<string, unknown>) => boolean);
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
}>();
</script>

<template>
  <el-dialog
    :model-value="visible"
    class="review-problem-dialog"
    width="calc(100vw - 24px)"
    top="4vh"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="true"
    :close-on-press-escape="true"
    @update:model-value="emit('update:visible', $event)"
  >
    <template #header="{ close, titleId, titleClass }">
      <div class="review-problem-dialog-header">
        <div class="review-problem-dialog-heading">
          <h2 :id="titleId" :class="['review-problem-dialog-title', titleClass]">评审问题清单</h2>
          <span v-if="record" class="review-problem-dialog-context">{{ record.title }}</span>
        </div>
        <el-button
          class="review-problem-dialog-close"
          text
          :icon="Close"
          aria-label="关闭评审问题清单"
          @click="close"
        />
      </div>
    </template>

    <div class="review-problem-dialog-body">
      <ReviewProblemPanel
        v-if="record"
        :record="record"
        :loading="loading"
        :rows="rows"
        :columns="columns"
        :on-create-problem-item="onCreateProblemItem"
        :on-edit-problem-item="onEditProblemItem"
        :on-delete-problem-item="onDeleteProblemItem"
        :can-manage="canManage"
        :can-create="canCreate"
        :can-edit="canEdit"
        :can-delete="canDelete"
      />
    </div>
  </el-dialog>
</template>

<style scoped>
:global(.review-problem-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  width: min(1728px, calc(100vw - 24px));
  max-width: calc(100vw - 24px);
  max-height: calc(100vh - 18px);
  padding: 10px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px !important;
  box-shadow: var(--el-box-shadow-dark) !important;
  box-sizing: border-box;
}

:global(.review-problem-dialog .el-dialog__header) {
  padding: 0 10px 8px !important;
  margin-right: 0;
  border-bottom: 0 !important;
  background: transparent !important;
}

.review-problem-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 0;
}

.review-problem-dialog-heading {
  display: flex;
  align-items: baseline;
  gap: 14px;
  min-width: 0;
}

.review-problem-dialog-title {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 30px;
  font-weight: 800;
  line-height: 40px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.review-problem-dialog-title.el-dialog__title) {
  color: var(--el-text-color-primary) !important;
  font-size: 30px !important;
  font-weight: 800 !important;
  line-height: 40px !important;
}

.review-problem-dialog-context {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-problem-dialog-close {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--el-text-color-secondary);
}

:global(.review-problem-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  padding: 0 !important;
  box-sizing: border-box;
}

.review-problem-dialog-body {
  width: 100%;
  min-height: 0;
  max-height: calc(100vh - 78px);
  overflow: auto;
}

.review-problem-dialog-body :deep(.problem-panel) {
  width: 100%;
  max-width: 100%;
  padding: 8px 0 4px;
  background: transparent;
  animation: none;
}

.review-problem-dialog-body :deep(.problem-subtable-frame) {
  overflow-x: auto;
  overflow-y: visible;
}

@media (max-width: 760px) {
  .review-problem-dialog-heading {
    display: grid;
    gap: 2px;
  }

  .review-problem-dialog-title,
  :global(.review-problem-dialog-title.el-dialog__title) {
    font-size: 22px !important;
    line-height: 30px !important;
  }
}
</style>
