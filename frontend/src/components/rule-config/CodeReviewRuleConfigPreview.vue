<script setup lang="ts">
import { computed } from 'vue';
// 规则预览组件把后端返回的命中结果和示例记录转成可读摘要。
// 它不重新计算规则，只展示保存前预览接口给出的结果。
import type { CodeReviewRulePreviewResponse } from '../../types/code-review-rule-config';

const props = defineProps<{
  loading: boolean;
  preview: CodeReviewRulePreviewResponse | null;
}>();

const retainedRateText = computed(() => `${(props.preview?.retainedRate ?? 0).toFixed(1)}%`);
const deltaText = computed(() => {
  const delta = props.preview?.deltaCount ?? 0;
  if (delta > 0) {
    return `比当前规则多判定 ${delta} 条`;
  }
  if (delta < 0) {
    return `比当前规则少判定 ${Math.abs(delta)} 条`;
  }
  return '与当前规则结果一致';
});
</script>

<template>
  <section class="rule-preview-shell" v-loading="loading">
    <header class="rule-preview-head">
      <div class="rule-preview-head-main">
        <h3 class="rule-preview-title">改完之后会怎样</h3>
        <p class="rule-preview-subtitle">预览编辑区规则结果，不改原始数据。</p>
      </div>
    </header>

    <template v-if="preview">
      <section class="rule-preview-summary">
        <article class="rule-preview-card rule-preview-card--primary">
          <span class="rule-preview-label">会判定为非法</span>
          <strong class="rule-preview-value">{{ preview.filteredTotal }}</strong>
        </article>
        <div class="rule-preview-secondary-grid">
          <article class="rule-preview-card rule-preview-card--compact">
            <span class="rule-preview-label">当前范围</span>
            <strong class="rule-preview-value">{{ preview.baseTotal }}</strong>
          </article>
          <article class="rule-preview-card rule-preview-card--compact">
            <span class="rule-preview-label">判定比例</span>
            <strong class="rule-preview-value">{{ retainedRateText }}</strong>
          </article>
        </div>
      </section>

      <section class="rule-preview-outcome">
        <div class="rule-preview-outcome-title">{{ deltaText }}</div>
        <div class="rule-preview-outcome-desc">
          对比当前列表规则，仅预览结果。
        </div>
      </section>

      <section class="rule-preview-samples">
        <div class="rule-preview-section-title">样本与原因</div>
        <el-empty v-if="!preview.samples.length" description="当前规则还没有判定出非法数据，你可以继续调整规则项。" />

        <div v-else class="rule-preview-sample-list">
          <article v-for="sample in preview.samples" :key="`${sample.mergeRequestId}-${sample.mergeRequestIid}`" class="rule-preview-sample-card">
            <header class="rule-preview-sample-head">
              <div class="rule-preview-sample-title">MR #{{ sample.mergeRequestIid || '-' }}</div>
              <div class="rule-preview-sample-meta">
                <span>{{ sample.projectName || '-' }}</span>
                <span class="rule-preview-meta-dot" />
                <span>{{ sample.moduleName || '-' }}</span>
                <span class="rule-preview-meta-dot" />
                <span>{{ sample.targetBranch || '-' }}</span>
              </div>
            </header>

            <div class="rule-preview-sample-content">{{ sample.mergeRequestContent || '-' }}</div>

            <div class="rule-preview-sample-owner">标注责任人：{{ sample.owner || '-' }}</div>

            <div class="rule-preview-sample-reasons">
              <div class="rule-preview-sample-reason-label">判定原因</div>
              <el-tag
                v-for="reason in sample.reasons"
                :key="reason"
                size="small"
                effect="plain"
                class="rule-preview-reason-tag"
              >
                {{ reason }}
              </el-tag>
            </div>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.rule-preview-shell {
  display: grid;
  gap: 8px;
  min-width: 0;
  min-height: 240px;
}

.rule-preview-head-main {
  display: grid;
  gap: 3px;
}

.rule-preview-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #1f2937;
}

.rule-preview-subtitle {
  margin: 0;
  font-size: 13px;
  line-height: 1.35;
  color: rgba(31, 41, 55, 0.64);
}

.rule-preview-summary {
  display: grid;
  gap: 7px;
}

.rule-preview-secondary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
}

.rule-preview-card {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 10px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.96));
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.04);
}

.rule-preview-card--primary {
  align-content: center;
  min-height: 66px;
  padding: 12px;
  background:
    radial-gradient(circle at 50% 0%, rgba(219, 234, 254, 0.72), transparent 58%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(248, 250, 252, 0.96));
}

.rule-preview-card--compact {
  align-content: center;
  min-height: 58px;
}

.rule-preview-label {
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  color: rgba(31, 41, 55, 0.54);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-preview-value {
  justify-self: end;
  font-size: 22px;
  line-height: 0.95;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  color: #0f172a;
}

.rule-preview-card--primary .rule-preview-value {
  justify-self: start;
  font-size: 30px;
}

.rule-preview-card--compact .rule-preview-value {
  justify-self: start;
}

.rule-preview-outcome {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(239, 246, 255, 0.92), rgba(255, 255, 255, 0.96));
  border: 1px solid rgba(59, 130, 246, 0.14);
}

.rule-preview-outcome-title {
  font-size: 14px;
  font-weight: 700;
  color: #1d4ed8;
}

.rule-preview-outcome-desc {
  font-size: 12px;
  line-height: 1.5;
  color: rgba(30, 64, 175, 0.76);
}

.rule-preview-section-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.rule-preview-sample-list {
  display: grid;
  gap: 7px;
  max-height: clamp(190px, calc(100vh - 520px), 380px);
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: rgba(148, 163, 184, 0.22) transparent;
  transition: scrollbar-color 0.18s ease;
}

.rule-preview-sample-list:hover {
  scrollbar-color: rgba(100, 116, 139, 0.42) transparent;
}

.rule-preview-sample-list::-webkit-scrollbar {
  width: 6px;
}

.rule-preview-sample-list::-webkit-scrollbar-track {
  background: transparent;
}

.rule-preview-sample-list::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.2);
}

.rule-preview-sample-list:hover::-webkit-scrollbar-thumb {
  background: rgba(100, 116, 139, 0.38);
}

.rule-preview-sample-card {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 10px;
  border-radius: 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.96);
}

.rule-preview-sample-head {
  display: grid;
  gap: 4px;
}

.rule-preview-sample-title {
  font-size: 13px;
  font-weight: 700;
  color: #111827;
}

.rule-preview-sample-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
  min-width: 0;
  font-size: 12px;
  color: rgba(31, 41, 55, 0.56);
  overflow: hidden;
}

.rule-preview-sample-meta span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-preview-meta-dot {
  flex: 0 0 4px;
  width: 4px;
  height: 4px;
  border-radius: 999px;
  background: rgba(31, 41, 55, 0.22);
}

.rule-preview-sample-content {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  font-size: 13px;
  line-height: 1.45;
  color: rgba(15, 23, 42, 0.84);
}

.rule-preview-sample-owner {
  font-size: 12px;
  color: rgba(31, 41, 55, 0.62);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-preview-sample-reasons {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 6px 8px;
  min-width: 0;
  max-width: 100%;
  flex-wrap: wrap;
  padding: 8px 9px 8px 12px;
  border-radius: 12px;
  background:
    linear-gradient(90deg, rgba(22, 119, 255, 0.08), transparent 46%),
    rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(22, 119, 255, 0.12);
  overflow: visible;
}

.rule-preview-sample-reasons::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 999px;
  background: #1677ff;
  opacity: 0.7;
}

.rule-preview-sample-reason-label {
  flex: 0 0 100%;
  font-size: 12px;
  font-weight: 700;
  color: rgba(22, 119, 255, 0.9);
}

.rule-preview-reason-tag {
  height: auto;
  min-height: 22px;
  padding: 2px 8px;
  border-radius: 7px;
  border-color: rgba(22, 119, 255, 0.18);
  background: rgba(24, 144, 255, 0.06);
  color: #0958d9;
  max-width: 100%;
  min-width: 0;
  white-space: normal;
}

.rule-preview-reason-tag :deep(.el-tag__content) {
  display: inline;
  overflow: visible;
  text-overflow: clip;
  white-space: normal;
  word-break: break-word;
  line-height: 1.5;
}

@media (max-width: 1200px) {
  .rule-preview-secondary-grid {
    grid-template-columns: 1fr;
  }
}
</style>
