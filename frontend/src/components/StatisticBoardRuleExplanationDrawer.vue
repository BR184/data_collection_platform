<script setup lang="ts">
import type {
  StatisticBoardRuleExplanationResponse,
  StatisticRuleFlowStep,
  StatisticRuleMetricDefinition,
} from '../types/api';
// 规则说明抽屉集中展示统计口径，避免每个看板页面散落一份解释文案。
// 组件只做结构化展示，不参与任何统计计算或筛选状态维护。
import {
  metricFormulaSummary,
  ruleStepRemovedCount,
  ruleStepRetainedRate,
  ruleStepSummary,
} from './statistic-board-rule-explanation';

defineProps<{
  modelValue: boolean;
  loading: boolean;
  explanation: StatisticBoardRuleExplanationResponse | null;
  steps: StatisticRuleFlowStep[];
  metrics: StatisticRuleMetricDefinition[];
  exclusionSteps: StatisticRuleFlowStep[];
  firstInputCount: number;
  finalOutputCount: number;
  finalRetainedRate: string;
  qaFriendlySummary: string;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :title="explanation?.title || '规则说明'"
    size="44%"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="loading" class="rule-explanation-panel">
      <el-empty
        v-if="!explanation?.supported"
        :description="explanation?.unsupportedReason || '当前统计表暂不支持规则说明。'"
      />

      <template v-else>
        <div class="rule-explanation-section">
          <div class="rule-explanation-section-title">先看结论</div>
          <div class="rule-explanation-summary-card">
            <div class="rule-explanation-summary-main">{{ qaFriendlySummary }}</div>
            <div v-if="explanation?.summary" class="rule-explanation-summary-sub">
              {{ explanation.summary }}
            </div>
          </div>
          <div class="rule-explanation-overview-grid">
            <article class="rule-overview-card">
              <span class="rule-overview-label">原始数据</span>
              <strong class="rule-overview-value">{{ firstInputCount }}</strong>
            </article>
            <article class="rule-overview-card">
              <span class="rule-overview-label">最终保留</span>
              <strong class="rule-overview-value">{{ finalOutputCount }}</strong>
            </article>
            <article class="rule-overview-card">
              <span class="rule-overview-label">最终保留比例</span>
              <strong class="rule-overview-value">{{ finalRetainedRate }}</strong>
            </article>
          </div>
        </div>

        <el-descriptions border :column="1" class="rule-explanation-meta">
          <el-descriptions-item label="当前使用规则版本">{{ explanation?.version || '-' }}</el-descriptions-item>
          <el-descriptions-item label="这次统计包含哪些数据">{{ explanation?.scopeDescription || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="rule-explanation-section">
          <div class="rule-explanation-section-title">哪些会被排除</div>
          <div class="rule-rule-card-grid">
            <article
              v-for="(step, index) in exclusionSteps"
              :key="step.key"
              class="rule-rule-card"
            >
              <div class="rule-rule-card-title">规则 {{ index + 1 }}：{{ step.title }}</div>
              <div class="rule-rule-card-description">{{ step.description }}</div>
              <div class="rule-rule-card-summary">{{ ruleStepSummary(step, index + 1) }}</div>
              <div class="rule-rule-card-stats">
                <span class="rule-rule-card-stat">排除 {{ ruleStepRemovedCount(step) }} 条</span>
                <span class="rule-rule-card-stat">剩余 {{ step.outputCount }} 条</span>
                <span class="rule-rule-card-stat">保留 {{ ruleStepRetainedRate(step) }}</span>
              </div>
            </article>
          </div>
        </div>

        <div class="rule-explanation-section">
          <div class="rule-explanation-section-title">数据是怎么一步步变少的</div>
          <div class="rule-process-chain">
            <article
              v-for="(step, index) in steps"
              :key="`${step.key}-process`"
              class="rule-process-card"
            >
              <div class="rule-process-step">第 {{ index + 1 }} 步</div>
              <div class="rule-process-title">{{ step.title }}</div>
              <div class="rule-process-value">{{ step.outputCount }} 条</div>
              <div class="rule-process-note">
                {{ index === 0 ? '这是最开始纳入统计的原始数据。' : `这一轮处理后还剩 ${step.outputCount} 条。` }}
              </div>
            </article>
          </div>
        </div>

        <div class="rule-explanation-section">
          <div class="rule-explanation-section-title">最后这些数字怎么算</div>
          <div class="rule-formula-card-grid">
            <article
              v-for="metric in metrics"
              :key="metric.key"
              class="rule-formula-card"
            >
              <div class="rule-formula-card-title">{{ metric.label }}</div>
              <div class="rule-formula-card-definition">{{ metricFormulaSummary(metric) }}</div>
              <div class="rule-formula-card-formula">{{ metric.formula }}</div>
              <div v-if="metric.note" class="rule-formula-card-note">{{ metric.note }}</div>
            </article>
          </div>
        </div>
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
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.95) 0%, rgba(248, 250, 252, 0.98) 100%);
  border: 1px solid rgba(59, 130, 246, 0.14);
}

.rule-explanation-summary-main {
  font-size: 15px;
  font-weight: 700;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.9);
}

.rule-explanation-summary-sub {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.66);
}

.rule-explanation-overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.rule-overview-card {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-overview-label {
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
  border-radius: 18px;
}

.rule-rule-card-grid,
.rule-formula-card-grid {
  display: grid;
  gap: 12px;
}

.rule-rule-card,
.rule-formula-card {
  display: grid;
  gap: 10px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-rule-card-title,
.rule-formula-card-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.9);
}

.rule-rule-card-description,
.rule-formula-card-definition,
.rule-formula-card-note {
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.68);
}

.rule-rule-card-summary,
.rule-formula-card-formula {
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(248, 250, 252, 0.96);
  border: 1px solid rgba(15, 23, 42, 0.06);
  font-size: 13px;
  line-height: 1.7;
  color: rgba(15, 23, 42, 0.8);
}

.rule-rule-card-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.rule-rule-card-stat {
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

.rule-process-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(15, 23, 42, 0.06);
}

.rule-process-step {
  font-size: 12px;
  color: rgba(15, 23, 42, 0.46);
}

.rule-process-title {
  font-size: 14px;
  font-weight: 700;
  color: rgba(15, 23, 42, 0.88);
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

@media (max-width: 960px) {
  .rule-explanation-overview-grid {
    grid-template-columns: 1fr;
  }
}
</style>
