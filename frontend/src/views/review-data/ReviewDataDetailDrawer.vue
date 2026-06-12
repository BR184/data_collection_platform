<script setup lang="ts">
import { Document } from '@element-plus/icons-vue';
// 评审详情抽屉只展示单条记录的完整字段，刷新和编辑动作仍由主页面统一调度。
// 这样详情区可以安全复用，不会意外触发列表查询条件变化。
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

function formatNumber(value?: number | null, digits = 2) {
  return Number.isFinite(value) ? Number(value).toFixed(digits) : '0';
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
          <el-descriptions-item label="不达标说明">{{ displayText(detailData.record.notReachStandardReason) }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>问题概览</span>
        </header>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="问题合计">{{ detailData.record.problemCount }}</el-descriptions-item>
          <el-descriptions-item label="缺陷密度">{{ formatNumber(detailData.record.problemDensity) }}</el-descriptions-item>
          <el-descriptions-item label="评审效率">{{ formatNumber(detailData.record.reviewEfficiency) }}</el-descriptions-item>
          <el-descriptions-item label="评审速率">{{ formatNumber(detailData.record.reviewRate) }}</el-descriptions-item>
          <el-descriptions-item label="独立评审工作量">{{ formatNumber(detailData.record.independentReviewWorkload) }}</el-descriptions-item>
          <el-descriptions-item label="有效独立问题数">{{ detailData.record.independentReviewProblemCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="会议评审工作量">{{ formatNumber(detailData.record.meetingReviewWorkload) }}</el-descriptions-item>
          <el-descriptions-item label="有效会议问题数">{{ detailData.record.meetingReviewProblemCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="是否达标">{{ detailData.record.reachStandard ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">
            {{ displayText(detailData.record.updatedAt?.replace('T', ' ').slice(0, 19)) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">{{ detailData.record.deleted ? '已删除' : '有效' }}</el-descriptions-item>
          <el-descriptions-item label="原文件">{{ displayText(detailData.record.sourceFileName) }}</el-descriptions-item>
          <el-descriptions-item label="加权密度">{{ formatNumber(detailData.record.weightedDefectDensity) }}</el-descriptions-item>
        </el-descriptions>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>工作产品描述</span>
        </header>
        <el-table :data="detailData.descriptions" border size="small" empty-text="暂无工作产品描述">
          <el-table-column prop="reviewProduct" label="评审工作产品" min-width="150" />
          <el-table-column prop="reviewVersion" label="版本" width="100" />
          <el-table-column prop="authorName" label="作者" width="100" />
          <el-table-column label="规模" width="90" align="right">
            <template #default="{ row }">{{ row.reviewScalePages }}{{ row.unit || '页' }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="detail-section">
        <header class="detail-section-head">
          <span>评审分工内容</span>
        </header>
        <el-table :data="detailData.contents" border size="small" empty-text="暂无评审分工内容">
          <el-table-column prop="reviewerName" label="评审人" width="100" />
          <el-table-column prop="assignmentContent" label="内容" min-width="180" />
          <el-table-column prop="independentWorkloadHours" label="独立工作量" width="100" align="right" />
          <el-table-column prop="independentProblemCount" label="独立问题" width="90" align="right" />
          <el-table-column prop="meetingWorkloadHours" label="会议工作量" width="100" align="right" />
          <el-table-column prop="meetingProblemCount" label="会议问题" width="90" align="right" />
        </el-table>
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
