<script setup lang="ts">
import { ElDescriptions, ElDescriptionsItem, ElDrawer, ElIcon } from 'element-plus';
import { Document } from '@element-plus/icons-vue';
import type { ReviewDataRecordDetailResponse } from '../../types/api';

defineProps<{
  visible: boolean;
  detailData: ReviewDataRecordDetailResponse | null;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
}>();

function displayText(value: unknown) {
  const text = String(value ?? '').trim();
  return text || '-';
}

function formatDate(value?: string | null) {
  return value ? value.slice(0, 10) : '-';
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="评审详情"
    size="560px"
    append-to-body
    class="review-data-drawer"
    @update:model-value="emit('update:visible', $event)"
  >
    <template v-if="detailData">
      <section class="detail-section">
        <header class="detail-section-head">
          <el-icon><Document /></el-icon>
          <span>评审基础信息</span>
        </header>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="项目名称">{{ displayText(detailData.record.projectName) }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ displayText(detailData.record.title) }}</el-descriptions-item>
          <el-descriptions-item label="模块">{{ displayText(detailData.record.moduleName) }}</el-descriptions-item>
          <el-descriptions-item label="评审类型">{{ displayText(detailData.record.reviewType) }}</el-descriptions-item>
          <el-descriptions-item label="评审日期">{{ formatDate(detailData.record.reviewDate) }}</el-descriptions-item>
          <el-descriptions-item label="评审负责人">{{ displayText(detailData.record.reviewOwner) }}</el-descriptions-item>
          <el-descriptions-item label="评审专家">{{ detailData.reviewExperts.join('、') || '-' }}</el-descriptions-item>
          <el-descriptions-item label="评审规模">{{ detailData.record.reviewScalePages }} 页</el-descriptions-item>
          <el-descriptions-item label="评审工作产品">{{ displayText(detailData.record.reviewProduct) }}</el-descriptions-item>
          <el-descriptions-item label="作者">{{ displayText(detailData.record.authorName) }}</el-descriptions-item>
          <el-descriptions-item label="评审版本">{{ displayText(detailData.record.reviewVersion) }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>问题概览</span>
        </header>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="问题合计">{{ detailData.record.problemCount }}</el-descriptions-item>
          <el-descriptions-item label="缺陷密度">{{ detailData.record.problemDensity.toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ displayText(detailData.record.updatedAt?.replace('T', ' ').slice(0, 19)) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ detailData.record.deleted ? '已删除' : '有效' }}</el-descriptions-item>
        </el-descriptions>
      </section>
    </template>
  </el-drawer>
</template>

<style scoped>
.detail-section {
  display: grid;
  gap: 10px;
  margin-bottom: 18px;
}

.detail-section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.75);
}

:deep(.review-data-drawer .el-drawer__header) {
  margin-bottom: 10px;
}

:deep(.review-data-drawer .el-descriptions__label) {
  width: 116px;
}
</style>
