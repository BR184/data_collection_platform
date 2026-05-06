<script setup lang="ts">
import { computed } from 'vue';
// 规则编辑器只维护条件组和动作配置的结构化编辑，具体字段来自代码走查规则 schema。
// 组件不保存草稿，外层页面负责本地缓存、预览和最终提交。
import { Delete, Plus } from '@element-plus/icons-vue';
import SmartSelect from '../base/SmartSelect.vue';
import type {
  CodeReviewRuleCondition,
  CodeReviewRuleConfig,
  CodeReviewRuleFieldDefinition,
} from '../../types/code-review-rule-config';
import {
  createCodeReviewRuleCondition,
  describeCodeReviewRuleCondition,
  listActiveCodeReviewRuleConditions,
  listInactiveCodeReviewRuleFields,
  setCodeReviewRuleConditions,
  usesConditionValue,
} from '../../views/code-review-rule-config-utils';

const props = defineProps<{
  modelValue: CodeReviewRuleConfig;
  fields: CodeReviewRuleFieldDefinition[];
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: CodeReviewRuleConfig): void;
}>();

const activeRules = computed(() => listActiveCodeReviewRuleConditions(props.modelValue));
const inactiveFields = computed(() => listInactiveCodeReviewRuleFields(props.modelValue, props.fields));
const activeCountText = computed(() => `${activeRules.value.length} 条生效`);

function findField(fieldKey: string) {
  return props.fields.find((item) => item.key === fieldKey) ?? null;
}

function selectOptions(fieldKey: string) {
  return findField(fieldKey)?.options ?? [];
}

function updateConditions(conditions: CodeReviewRuleCondition[]) {
  emit('update:modelValue', setCodeReviewRuleConditions(props.modelValue, conditions));
}

function addRule(field: CodeReviewRuleFieldDefinition) {
  updateConditions([createCodeReviewRuleCondition(field), ...activeRules.value]);
}

function removeRule(conditionId: string) {
  updateConditions(activeRules.value.filter((condition) => condition.id !== conditionId));
}

function updateConditionValue(conditionId: string, value: string | string[]) {
  const nextValue = Array.isArray(value) ? value.join(',') : value;
  updateConditions(
    activeRules.value.map((condition) =>
      condition.id === conditionId
        ? {
            ...condition,
            value: nextValue,
          }
        : condition,
    ),
  );
}

function valuePlaceholder(condition: CodeReviewRuleCondition) {
  if (condition.operator === 'contains') {
    return '请输入风险词，例如：临时、绕过、测试';
  }
  if (condition.operator === 'lt') {
    return '请输入最低要求，例如：0.2';
  }
  if (condition.operator === 'gt') {
    return '请输入上限，例如：500';
  }
  return '请输入规则值';
}

function conditionValueModel(condition: CodeReviewRuleCondition) {
  const field = findField(condition.fieldKey);
  if (field?.type !== 'multi-select') {
    return condition.value;
  }
  return condition.value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function ruleSentence(condition: CodeReviewRuleCondition) {
  const field = findField(condition.fieldKey);
  if (!field) {
    return '这条规则暂时不可用，可以删除后重新添加。';
  }
  switch (condition.operator) {
    case 'isEmpty':
      return `${field.label}时，判定为非法数据`;
    case 'isMissingReview':
      return '没有形成有效代码走查记录时，判定为非法数据';
    case 'isNotScanned':
      return '明确标记为未完成代码扫描时，判定为非法数据';
    case 'hasOpenScanIssue':
      return '静态扫描问题数大于 0 时，判定为非法数据';
    case 'notIn':
      return '目标分支不在允许范围内时，判定为非法数据';
    case 'contains':
      return '合并内容包含风险词时，判定为非法数据';
    case 'lt':
      return `${field.label}时，低于填写值就判定为非法数据`;
    case 'gt':
      return `${field.label}时，高于填写值就判定为非法数据`;
    default:
      return '满足这条规则时，判定为非法数据';
  }
}
</script>

<template>
  <section class="rule-editor-shell">
    <header class="rule-editor-head">
      <div class="rule-editor-head-main">
        <h3 class="rule-editor-title">生效规则</h3>
        <p class="rule-editor-subtitle">
          下方规则决定非法数据范围，右侧实时预览。
        </p>
      </div>
      <div class="rule-editor-head-actions">
        <el-tag effect="plain" type="success">{{ activeCountText }}</el-tag>
        <el-popover placement="bottom-end" width="360" trigger="click">
          <template #reference>
            <el-button type="primary" plain :icon="Plus">添加规则</el-button>
          </template>
          <div class="rule-add-panel">
            <div class="rule-add-title">可添加的规则</div>
            <div v-if="inactiveFields.length" class="rule-add-list">
              <button
                v-for="field in inactiveFields"
                :key="field.key"
                type="button"
                class="rule-add-item"
                @click="addRule(field)"
              >
                <span>{{ field.label }}</span>
                <small>加入生效规则</small>
              </button>
            </div>
            <el-empty v-else description="所有可配置规则都已经在生效列表中。" :image-size="80" />
          </div>
        </el-popover>
      </div>
    </header>

    <div v-if="activeRules.length" class="rule-card-list">
      <article
        v-for="condition in activeRules"
        :key="condition.id"
        class="rule-card"
        :class="{ 'rule-card--fixed': !usesConditionValue(condition.operator) }"
      >
        <div class="rule-card-main">
          <div class="rule-card-kicker">生效中</div>
          <div class="rule-card-title">{{ findField(condition.fieldKey)?.label || '未知规则' }}</div>
          <div class="rule-card-sentence">{{ ruleSentence(condition) }}</div>
          <div class="rule-card-summary">{{ describeCodeReviewRuleCondition(condition, fields) }}</div>
        </div>

        <div class="rule-card-control">
          <SmartSelect
            v-if="findField(condition.fieldKey)?.type === 'multi-select' && usesConditionValue(condition.operator)"
            :model-value="conditionValueModel(condition)"
            :options="selectOptions(condition.fieldKey)"
            compact
            multiple
            collapse-tags
            placeholder="选择允许范围"
            @change="updateConditionValue(condition.id, $event as string | string[])"
          />

          <el-input
            v-else-if="usesConditionValue(condition.operator)"
            :model-value="condition.value"
            :placeholder="valuePlaceholder(condition)"
            @input="updateConditionValue(condition.id, String($event ?? ''))"
          />

          <div v-else class="rule-card-fixed">无需配置</div>
        </div>

        <el-button text :icon="Delete" class="rule-card-remove" @click="removeRule(condition.id)">
          移除
        </el-button>
      </article>
    </div>

    <el-empty
      v-else
      description="当前没有启用任何个人判定规则。你可以点击“添加规则”选择要生效的规则。"
    />
  </section>
</template>

<style scoped>
.rule-editor-shell {
  display: grid;
  gap: 8px;
}

.rule-editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.rule-editor-head-main {
  display: grid;
  gap: 3px;
}

.rule-editor-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.rule-editor-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #1f2937;
}

.rule-editor-subtitle {
  margin: 0;
  font-size: 13px;
  line-height: 1.35;
  color: rgba(31, 41, 55, 0.64);
}

.rule-card-list {
  display: grid;
  gap: 9px;
  max-height: clamp(320px, calc(100vh - 390px), 560px);
  overflow-x: hidden;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: rgba(148, 163, 184, 0.22) transparent;
  transition: scrollbar-color 0.18s ease;
}

.rule-card-list:hover {
  scrollbar-color: rgba(100, 116, 139, 0.42) transparent;
}

.rule-card-list::-webkit-scrollbar {
  width: 6px;
}

.rule-card-list::-webkit-scrollbar-track {
  background: transparent;
}

.rule-card-list::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.2);
}

.rule-card-list:hover::-webkit-scrollbar-thumb {
  background: rgba(100, 116, 139, 0.38);
}

.rule-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(160px, 235px) auto;
  gap: 12px;
  align-items: center;
  min-height: 80px;
  padding: 12px 14px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 16px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(249, 250, 251, 0.96)),
    radial-gradient(circle at top left, rgba(219, 234, 254, 0.5), transparent 36%);
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 10px 22px rgba(15, 23, 42, 0.045);
}

.rule-card--fixed {
  grid-template-columns: minmax(0, 1fr) auto auto;
}

.rule-card-main {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 4px 10px;
  align-content: center;
  align-items: start;
  min-width: 0;
}

.rule-card-kicker {
  grid-row: 1 / span 3;
  width: fit-content;
  margin-top: 1px;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(22, 163, 74, 0.1);
  color: #15803d;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.rule-card-title {
  min-width: 0;
  font-size: 15px;
  line-height: 1.3;
  font-weight: 700;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-card-sentence {
  min-width: 0;
  font-size: 13px;
  line-height: 1.42;
  color: rgba(31, 41, 55, 0.8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-card-summary {
  grid-column: 2;
  min-width: 0;
  max-width: 100%;
  font-size: 12px;
  line-height: 1.45;
  color: rgba(31, 41, 55, 0.56);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-card-control {
  min-width: 0;
}

.rule-card-control :deep(.el-input__wrapper) {
  min-height: 30px;
}

.rule-card-fixed {
  display: flex;
  align-items: center;
  min-height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(248, 250, 252, 0.95);
  font-size: 12px;
  color: rgba(71, 85, 105, 0.8);
  white-space: nowrap;
}

.rule-card-remove {
  justify-self: end;
  padding: 4px 8px;
}

.rule-add-panel {
  display: grid;
  gap: 12px;
}

.rule-add-title {
  font-size: 14px;
  font-weight: 700;
  color: #111827;
}

.rule-add-list {
  display: grid;
  gap: 8px;
  max-height: 360px;
  overflow: auto;
  scrollbar-gutter: stable;
}

.rule-add-item {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 12px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease,
    transform 0.18s ease;
}

.rule-add-item:hover {
  border-color: rgba(37, 99, 235, 0.3);
  background: rgba(239, 246, 255, 0.72);
  transform: translateY(-1px);
}

.rule-add-item span {
  font-size: 13px;
  font-weight: 700;
  color: #111827;
}

.rule-add-item small {
  font-size: 12px;
  color: rgba(31, 41, 55, 0.56);
}

@media (max-width: 1200px) {
  .rule-card {
    grid-template-columns: 1fr;
  }

  .rule-card-remove {
    justify-self: start;
  }
}
</style>
