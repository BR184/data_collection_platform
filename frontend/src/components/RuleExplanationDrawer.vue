<script setup lang="ts">
import type { StatisticRuleFlowStep, StatisticRuleMetricDefinition } from '../types/api';
import {
  metricFormulaSummary,
  ruleStepRemovedCount,
  ruleStepRetainedRate,
  ruleStepSummary,
} from './statistic-board-rule-explanation';

interface OverviewCard {
  label: string;
  value: string | number;
}

interface InfoItem {
  label: string;
  value?: string | null;
}

interface GuidanceCard {
  key: string;
  title: string;
  description?: string | null;
  guidance?: string | null;
  note?: string | null;
  badge?: string | null;
}

interface QuestionCard {
  key: string;
  title: string;
  description: string;
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    loading?: boolean;
    title?: string | null;
    supported?: boolean;
    unsupportedReason?: string | null;
    summaryMain?: string | null;
    summary?: string | null;
    overviewCards?: OverviewCard[];
    infoItems?: InfoItem[];
    exclusionSteps?: StatisticRuleFlowStep[];
    processSteps?: StatisticRuleFlowStep[];
    metrics?: StatisticRuleMetricDefinition[];
    guidanceCards?: GuidanceCard[];
    questions?: QuestionCard[];
    exclusionTitle?: string;
    processTitle?: string;
    metricsTitle?: string;
    guidanceTitle?: string;
    questionsTitle?: string;
  }>(),
  {
    loading: false,
    title: '规则说明',
    supported: true,
    unsupportedReason: '当前暂不支持规则说明。',
    summaryMain: '',
    summary: '',
    overviewCards: () => [],
    infoItems: () => [],
    exclusionSteps: () => [],
    processSteps: () => [],
    metrics: () => [],
    guidanceCards: () => [],
    questions: () => [],
    exclusionTitle: '判定规则',
    processTitle: '处理流程',
    metricsTitle: '指标定义',
    guidanceTitle: '填写指南',
    questionsTitle: '常见问题',
  },
);

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="title || '规则说明'"
    size="44%"
    append-to-body
    destroy-on-close
    class="rule-explanation-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="rule-explanation-panel">
      <el-empty
        v-if="!supported"
        :description="unsupportedReason || '当前暂不支持规则说明。'"
      />

      <template v-else>
        <section v-if="summaryMain || summary || overviewCards.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">先看结论</div>
          <div v-if="summaryMain || summary" class="rule-explanation-summary-card">
            <div v-if="summaryMain" class="rule-explanation-summary-main">{{ summaryMain }}</div>
            <div v-if="summary" class="rule-explanation-summary-sub">{{ summary }}</div>
          </div>
          <div v-if="overviewCards.length" class="rule-explanation-overview-grid">
            <article v-for="card in overviewCards" :key="card.label" class="rule-overview-card">
              <span class="rule-overview-label">{{ card.label }}</span>
              <strong class="rule-overview-value">{{ card.value }}</strong>
            </article>
          </div>
        </section>

        <el-descriptions v-if="infoItems.length" border :column="1" class="rule-explanation-meta">
          <el-descriptions-item v-for="item in infoItems" :key="item.label" :label="item.label">
            {{ item.value || '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <section v-if="guidanceCards.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">{{ guidanceTitle }}</div>
          <div class="rule-card-grid">
            <article v-for="card in guidanceCards" :key="card.key" class="rule-card">
              <div class="rule-card-head">
                <strong class="rule-card-title">{{ card.title }}</strong>
                <el-tag v-if="card.badge" effect="plain" type="info">{{ card.badge }}</el-tag>
              </div>
              <div v-if="card.description" class="rule-card-description">{{ card.description }}</div>
              <div v-if="card.guidance" class="rule-card-guidance">
                <span class="rule-card-guidance-label">建议</span>
                <span>{{ card.guidance }}</span>
              </div>
              <div v-if="card.note" class="rule-card-note">{{ card.note }}</div>
            </article>
          </div>
        </section>

        <section v-if="exclusionSteps.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">{{ exclusionTitle }}</div>
          <div class="rule-card-grid">
            <article v-for="(step, index) in exclusionSteps" :key="step.key" class="rule-card">
              <div class="rule-card-title">规则 {{ index + 1 }}：{{ step.title }}</div>
              <div class="rule-card-description">{{ step.description }}</div>
              <div class="rule-card-summary">{{ ruleStepSummary(step, index + 1) }}</div>
              <div class="rule-card-stats">
                <span class="rule-card-stat">输入 {{ step.inputCount }} 条</span>
                <span class="rule-card-stat">输出 {{ step.outputCount }} 条</span>
                <span class="rule-card-stat">排除 {{ ruleStepRemovedCount(step) }} 条</span>
                <span class="rule-card-stat">保留 {{ ruleStepRetainedRate(step) }}</span>
              </div>
            </article>
          </div>
        </section>

        <section v-if="processSteps.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">{{ processTitle }}</div>
          <div class="rule-process-chain">
            <article v-for="(step, index) in processSteps" :key="`${step.key}-process`" class="rule-process-card">
              <div class="rule-process-step">第 {{ index + 1 }} 步</div>
              <div class="rule-process-title">{{ step.title }}</div>
              <div class="rule-process-value">{{ step.outputCount }} 条</div>
              <div class="rule-process-note">
                输入 {{ step.inputCount }} 条，输出 {{ step.outputCount }} 条
              </div>
            </article>
          </div>
        </section>

        <section v-if="metrics.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">{{ metricsTitle }}</div>
          <div class="rule-card-grid">
            <article v-for="metric in metrics" :key="metric.key" class="rule-card">
              <div class="rule-card-title">{{ metric.label }}</div>
              <div class="rule-card-description">{{ metricFormulaSummary(metric) }}</div>
              <div class="rule-card-formula">{{ metric.formula }}</div>
              <div v-if="metric.note" class="rule-card-note">{{ metric.note }}</div>
            </article>
          </div>
        </section>

        <section v-if="questions.length" class="rule-explanation-section">
          <div class="rule-explanation-section-title">{{ questionsTitle }}</div>
          <div class="rule-card-grid">
            <article v-for="question in questions" :key="question.key" class="rule-card">
              <div class="rule-card-title">{{ question.title }}</div>
              <div class="rule-card-description">{{ question.description }}</div>
            </article>
          </div>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.rule-explanation-panel {
  display: grid;
  gap: 16px;
}

.rule-explanation-section {
  display: grid;
  gap: 12px;
}

.rule-explanation-section-title {
  font-size: 13px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.76);
}

.rule-explanation-summary-card {
  display: grid;
  gap: 8px;
  padding: 16px 18px;
  border: 1px solid rgba(59, 130, 246, 0.14);
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.95), rgba(248, 250, 252, 0.98));
}

.rule-explanation-summary-main {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.9);
}

.rule-explanation-summary-sub,
.rule-card-description,
.rule-card-guidance,
.rule-card-note,
.rule-card-summary {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.rule-explanation-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.rule-overview-card,
.rule-card,
.rule-process-card {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
}

.rule-overview-label,
.rule-process-step,
.rule-card-guidance-label {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.52);
}

.rule-overview-value {
  font-size: 22px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.92);
}

.rule-explanation-meta {
  background: rgba(255, 255, 255, 0.82);
  border-radius: 8px;
}

.rule-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rule-card-title,
.rule-process-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.rule-card-guidance {
  display: grid;
  gap: 4px;
}

.rule-card-guidance-label {
  font-weight: 700;
  color: #2563eb;
}

.rule-card-summary,
.rule-card-formula {
  padding: 10px 12px;
  border: 1px solid rgba(15, 23, 42, 0.06);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.96);
}

.rule-card-formula {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.82);
}

.rule-card-note {
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.1);
  color: rgba(146, 64, 14, 0.92);
}

.rule-card-grid {
  display: grid;
  gap: 12px;
}

.rule-card-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-card-stat {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(241, 245, 249, 0.95);
  font-size: 12px;
  color: rgba(15, 23, 42, 0.72);
}

.rule-process-chain {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.rule-process-value {
  font-size: 26px;
  line-height: 1;
  color: rgba(15, 23, 42, 0.94);
}

.rule-process-note {
  font-size: 12px;
  line-height: 1.6;
  color: rgba(15, 23, 42, 0.62);
}

:deep(.rule-explanation-drawer .el-drawer__header) {
  margin-bottom: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.06);
}

:deep(.rule-explanation-drawer .el-drawer__body) {
  background: #fafafa;
}

@media (max-width: 960px) {
  .rule-explanation-overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
