<script setup lang="ts">
defineProps<{
  row: Record<string, unknown>;
  expanded: boolean;
  onToggleProblemPanel: (row: Record<string, unknown>) => void | Promise<void>;
  onOpenDetail: (row: Record<string, unknown>) => void | Promise<void>;
  onEditRecord: (row: Record<string, unknown>) => void | Promise<void>;
  onCreateProblemItem: (row: Record<string, unknown>) => void | Promise<void>;
  onDeleteRecord: (row: Record<string, unknown>) => void | Promise<void>;
}>();
</script>

<template>
  <div class="record-actions">
    <el-button
      class="record-actions-chip"
      :class="{ active: expanded }"
      type="primary"
      plain
      size="small"
      @click="onToggleProblemPanel(row)"
    >
      {{ expanded ? '收起' : '清单' }}
    </el-button>
    <el-button class="record-actions-link" type="primary" plain size="small" @click="onOpenDetail(row)">查看</el-button>
    <el-dropdown>
      <span class="record-actions-more">
        更多
        <el-icon><ArrowDown /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item @click="onEditRecord(row)">编辑评审</el-dropdown-item>
          <el-dropdown-item @click="onCreateProblemItem(row)">新增问题</el-dropdown-item>
          <el-dropdown-item divided @click="onDeleteRecord(row)">删除评审</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style scoped>
.record-actions {
  display: grid;
  grid-template-columns: 52px 52px 48px;
  align-items: center;
  justify-content: center;
  column-gap: 7px;
}

.record-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.record-actions-chip {
  height: 26px;
  width: 52px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.2);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  justify-content: center;
  line-height: 26px;
}

.record-actions-chip:hover,
.record-actions-chip.active {
  border-color: #2563eb;
  background: #2563eb;
  color: #fff;
}

.record-actions-link {
  height: 26px;
  width: 52px;
  padding: 0 10px;
  border-color: rgba(37, 99, 235, 0.14);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.04);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  justify-content: center;
  line-height: 26px;
}

.record-actions-link:hover {
  border-color: rgba(37, 99, 235, 0.28);
  background: rgba(37, 99, 235, 0.1);
  color: #1d4ed8;
}

.record-actions-more {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  height: 26px;
  width: 48px;
  padding: 0;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12px;
  color: rgba(37, 99, 235, 0.72);
  line-height: 26px;
}

.record-actions-more:hover {
  background: rgba(37, 99, 235, 0.08);
  color: #1d4ed8;
}
</style>
