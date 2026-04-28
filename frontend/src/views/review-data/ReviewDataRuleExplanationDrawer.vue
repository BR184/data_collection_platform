<script setup lang="ts">
import { ElDrawer, ElIcon, ElTag } from 'element-plus';
import { InfoFilled } from '@element-plus/icons-vue';
import type { ReviewDataRuleExplanationContent } from './review-data-rule-explanation';

defineProps<{
  visible: boolean;
  content: ReviewDataRuleExplanationContent;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
}>();
</script>

<template>
  <el-drawer
    :model-value="visible"
    :title="content.title"
    size="620px"
    append-to-body
    class="review-data-drawer review-rule-drawer"
    @update:model-value="emit('update:visible', $event)"
  >
    <section class="detail-section">
      <header class="detail-section-head">
        <el-icon><InfoFilled /></el-icon>
        <span>先看说明</span>
      </header>
      <div class="rule-summary-card">
        <div class="rule-summary-main">{{ content.summary }}</div>
        <div class="rule-summary-sub">{{ content.scopeDescription }}</div>
        <div class="rule-summary-meta">
          <el-tag size="small" effect="plain" type="success">版本 {{ content.version }}</el-tag>
          <el-tag size="small" effect="plain" type="info">面向录入与查看</el-tag>
        </div>
      </div>
    </section>

    <section class="detail-section">
      <header class="detail-section-head">
        <span>填写指南</span>
      </header>
      <div class="rule-card-grid">
        <article v-for="field in content.fieldDefinitions" :key="field.key" class="rule-card">
          <div class="rule-card-head">
            <strong class="rule-card-title">{{ field.label }}</strong>
            <el-tag size="small" effect="plain" type="info">关键字段</el-tag>
          </div>
          <div class="rule-card-definition">{{ field.description }}</div>
          <div class="rule-card-line">
            <span class="rule-card-label">建议</span>
            <span>{{ field.guidance }}</span>
          </div>
          <div v-if="field.note" class="rule-card-note">{{ field.note }}</div>
        </article>
      </div>
    </section>

    <section class="detail-section">
      <header class="detail-section-head">
        <span>数据是怎么计算的</span>
      </header>
      <div class="rule-card-grid">
        <article v-for="metric in content.metricDefinitions" :key="metric.key" class="rule-card">
          <div class="rule-card-head">
            <strong class="rule-card-title">{{ metric.label }}</strong>
          </div>
          <div class="rule-card-definition">{{ metric.definition }}</div>
          <div class="rule-card-formula">{{ metric.formula }}</div>
          <div v-if="metric.note" class="rule-card-note">{{ metric.note }}</div>
        </article>
      </div>
    </section>

    <section class="detail-section">
      <header class="detail-section-head">
        <span>常见问题</span>
      </header>
      <div class="rule-question-list">
        <article v-for="question in content.commonQuestions" :key="question.key" class="rule-question-card">
          <div class="rule-question-title">{{ question.title }}</div>
          <div class="rule-question-description">{{ question.description }}</div>
        </article>
      </div>
    </section>
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

.rule-summary-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border: 1px solid rgba(59, 130, 246, 0.14);
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.96), rgba(248, 250, 252, 0.98));
}

.rule-summary-main {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.88);
}

.rule-summary-sub {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.rule-summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-card-grid,
.rule-question-list {
  display: grid;
  gap: 12px;
}

.rule-card,
.rule-question-card {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 14px;
  background: #fff;
}

.rule-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rule-card-title,
.rule-question-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.5;
  color: rgba(15, 23, 42, 0.88);
}

.rule-card-definition,
.rule-question-description {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.72);
}

.rule-card-line {
  display: grid;
  gap: 4px;
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.72);
}

.rule-card-label {
  font-size: 12px;
  font-weight: 700;
  color: #2563eb;
}

.rule-card-formula {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.04);
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.82);
}

.rule-card-note {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(245, 158, 11, 0.1);
  font-size: 12px;
  line-height: 1.7;
  color: rgba(146, 64, 14, 0.92);
}

:deep(.review-data-drawer .el-drawer__header) {
  margin-bottom: 10px;
}
</style>
