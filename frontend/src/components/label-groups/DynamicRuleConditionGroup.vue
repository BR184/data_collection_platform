<script setup lang="ts">
import { computed } from 'vue';
import { Delete, Plus } from '@element-plus/icons-vue';
import SmartSelect from '../base/SmartSelect.vue';
import type { LabelGroupDynamicRuleSource } from '../../types/api';
import type {
  RuleConditionFormState,
  RuleConditionGroupFormState,
  RuleSourceFieldOption,
} from '../../views/label-groups/label-group-settings';
import { createEmptyCondition, createEmptyConditionGroup } from '../../views/label-groups/label-group-settings';
import type { RecordTableFilterOption } from '../../types/record-table';

const props = withDefaults(defineProps<{
  group: RuleConditionGroupFormState;
  sources: LabelGroupDynamicRuleSource[];
  aggregateOptions?: RecordTableFilterOption[];
  aggregateMode?: boolean;
  level?: number;
}>(), {
  aggregateOptions: () => [],
  aggregateMode: false,
  level: 0,
});

const sourceOptions = computed<RecordTableFilterOption[]>(() =>
  props.sources.map((source) => ({ label: source.name, value: source.key })),
);

function sourceFieldOptions(sourceKey: string): RuleSourceFieldOption[] {
  const source = props.sources.find((item) => item.key === sourceKey);
  if (!source) {
    return [];
  }
  return source.fields.map((field) => ({
    sourceKey: source.key,
    sourceName: source.name,
    fieldKey: field.key,
    fieldName: field.name,
    valueType: field.valueType,
    operators: field.operators,
    outputSupported: field.outputSupported,
    filterSupported: field.filterSupported,
    groupSupported: field.groupSupported,
    aggregateSupported: field.aggregateSupported,
  })).filter((field) => field.filterSupported);
}

function fieldOptions(condition: RuleConditionFormState): RecordTableFilterOption[] {
  return sourceFieldOptions(condition.sourceKey).map((field) => ({
    label: `${field.fieldName} / ${valueTypeLabel(field.valueType)}`,
    value: field.fieldKey,
  }));
}

function currentField(condition: RuleConditionFormState) {
  return sourceFieldOptions(condition.sourceKey).find((field) => field.fieldKey === condition.fieldKey) ?? null;
}

function operatorOptions(condition: RuleConditionFormState): RecordTableFilterOption[] {
  if (props.aggregateMode) {
    return numberOperatorOptions();
  }
  const field = currentField(condition);
  return (field?.operators ?? []).map((operator) => ({ label: operatorLabel(operator), value: operator }));
}

function addCondition() {
  props.group.conditions.push(createEmptyCondition(props.sources[0]?.key ?? ''));
}

function addChildGroup() {
  props.group.groups.push(createEmptyConditionGroup());
}

function removeCondition(index: number) {
  props.group.conditions.splice(index, 1);
}

function removeGroup(index: number) {
  props.group.groups.splice(index, 1);
}

function handleSourceChange(condition: RuleConditionFormState, value: string | string[]) {
  condition.sourceKey = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  condition.fieldKey = '';
  condition.operator = '';
  condition.value = '';
  condition.secondValue = '';
  condition.valuesText = '';
}

function handleFieldChange(condition: RuleConditionFormState, value: string | string[]) {
  condition.fieldKey = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  condition.operator = currentField(condition)?.operators[0] ?? 'eq';
  condition.value = '';
  condition.secondValue = '';
  condition.valuesText = '';
}

function handleAggregateChange(condition: RuleConditionFormState, value: string | string[]) {
  condition.aggregateKey = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  condition.operator = condition.operator || 'gt';
  condition.value = '';
  condition.secondValue = '';
}

function handleOperatorChange(condition: RuleConditionFormState, value: string | string[]) {
  condition.operator = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  condition.value = '';
  condition.secondValue = '';
  condition.valuesText = '';
}

function needsValue(condition: RuleConditionFormState) {
  return condition.operator !== 'isEmpty' && condition.operator !== 'isNotEmpty';
}

function usesSecondValue(condition: RuleConditionFormState) {
  return condition.operator === 'between';
}

function usesMultiValue(condition: RuleConditionFormState) {
  return condition.operator === 'in';
}

function usesDatePicker(condition: RuleConditionFormState) {
  return currentField(condition)?.valueType === 'DATE';
}

function usesNumberInput(condition: RuleConditionFormState) {
  return props.aggregateMode || currentField(condition)?.valueType === 'NUMBER';
}

function operatorLabel(operator: string) {
  return ({
    eq: '等于',
    ne: '不等于',
    contains: '包含',
    notContains: '不包含',
    startsWith: '开始于',
    endsWith: '结束于',
    gt: '大于',
    gte: '大于等于',
    lt: '小于',
    lte: '小于等于',
    between: '区间',
    lastDays: '最近天数',
    isEmpty: '为空',
    isNotEmpty: '不为空',
    in: '包含任意',
  } as Record<string, string>)[operator] ?? operator;
}

function valueTypeLabel(valueType: string) {
  return ({
    STRING: '文本',
    NUMBER: '数字',
    DATE: '日期',
    BOOLEAN: '布尔',
  } as Record<string, string>)[valueType] ?? valueType;
}

function numberOperatorOptions() {
  return ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'between'].map((operator) => ({
    label: operatorLabel(operator),
    value: operator,
  }));
}
</script>

<template>
  <div class="dynamic-condition-group" :class="{ 'is-child': level > 0 }">
    <div class="dynamic-condition-group__head">
      <el-segmented
        :model-value="group.logic"
        :options="[{ label: '满足全部', value: 'AND' }, { label: '满足任意', value: 'OR' }]"
        @update:model-value="group.logic = $event === 'OR' ? 'OR' : 'AND'"
      />
      <el-button :icon="Plus" size="small" plain @click="addCondition">添加条件</el-button>
      <el-button v-if="!aggregateMode" :icon="Plus" size="small" plain @click="addChildGroup">添加条件组</el-button>
    </div>

    <div v-if="group.conditions.length || group.groups.length" class="dynamic-condition-group__body">
      <div v-for="(condition, index) in group.conditions" :key="condition.id" class="dynamic-condition-row">
        <template v-if="aggregateMode">
          <SmartSelect
            :model-value="condition.aggregateKey"
            class="condition-aggregate"
            placeholder="聚合项"
            :options="aggregateOptions"
            @change="handleAggregateChange(condition, $event)"
          />
        </template>
        <template v-else>
          <SmartSelect
            :model-value="condition.sourceKey"
            class="condition-source"
            placeholder="数据源"
            :options="sourceOptions"
            @change="handleSourceChange(condition, $event)"
          />
          <SmartSelect
            :model-value="condition.fieldKey"
            class="condition-field"
            placeholder="字段"
            :options="fieldOptions(condition)"
            @change="handleFieldChange(condition, $event)"
          />
        </template>
        <SmartSelect
          :model-value="condition.operator"
          class="condition-operator"
          placeholder="关系"
          :options="operatorOptions(condition)"
          @change="handleOperatorChange(condition, $event)"
        />
        <template v-if="needsValue(condition)">
          <el-input
            v-if="usesMultiValue(condition)"
            v-model="condition.valuesText"
            class="condition-value"
            placeholder="多个值，用逗号或换行分隔"
          />
          <el-date-picker
            v-else-if="usesDatePicker(condition)"
            v-model="condition.value"
            class="condition-value"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="时间"
          />
          <el-input-number
            v-else-if="usesNumberInput(condition)"
            v-model="condition.value"
            class="condition-value"
            controls-position="right"
            placeholder="值"
          />
          <el-input v-else v-model="condition.value" class="condition-value" clearable placeholder="值" />
        </template>
        <template v-if="usesSecondValue(condition)">
          <el-date-picker
            v-if="usesDatePicker(condition)"
            v-model="condition.secondValue"
            class="condition-value"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ssZ"
            placeholder="结束时间"
          />
          <el-input-number
            v-else-if="usesNumberInput(condition)"
            v-model="condition.secondValue"
            class="condition-value"
            controls-position="right"
            placeholder="结束值"
          />
          <el-input v-else v-model="condition.secondValue" class="condition-value" clearable placeholder="结束值" />
        </template>
        <el-tooltip content="删除条件" placement="top">
          <el-button :icon="Delete" text type="danger" class="condition-remove" @click="removeCondition(index)" />
        </el-tooltip>
      </div>

      <div v-for="(child, index) in group.groups" :key="child.id" class="dynamic-condition-child">
        <DynamicRuleConditionGroup
          :group="child"
          :sources="sources"
          :aggregate-options="aggregateOptions"
          :aggregate-mode="aggregateMode"
          :level="level + 1"
        />
        <el-button text type="danger" size="small" @click="removeGroup(index)">删除条件组</el-button>
      </div>
    </div>
    <div v-else class="dynamic-condition-empty">还没有条件，未添加时表示不过滤。</div>
  </div>
</template>

<style scoped>
.dynamic-condition-group {
  display: grid;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.dynamic-condition-group.is-child {
  padding: 10px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.78);
}

.dynamic-condition-group__head,
.dynamic-condition-group__body {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.dynamic-condition-group__body {
  align-items: stretch;
}

.dynamic-condition-row {
  display: grid;
  grid-template-columns: minmax(120px, 0.8fr) minmax(160px, 1.1fr) minmax(96px, 0.7fr) minmax(180px, 1fr) auto auto;
  gap: 6px;
  align-items: center;
  width: 100%;
  padding: 8px;
  border: 1px solid rgba(29, 78, 216, 0.12);
  border-radius: 8px;
  background: #fff;
}

.condition-aggregate {
  grid-column: span 2;
}

.condition-source,
.condition-field,
.condition-operator,
.condition-value,
.condition-aggregate {
  width: 100%;
  min-width: 0;
}

.condition-remove {
  width: 30px;
  min-width: 30px;
}

.dynamic-condition-child {
  display: grid;
  gap: 4px;
  width: 100%;
}

.dynamic-condition-empty {
  padding: 10px 12px;
  border: 1px dashed rgba(15, 23, 42, 0.16);
  border-radius: 8px;
  color: rgba(15, 23, 42, 0.48);
  font-size: 13px;
}

:deep(.el-input-number),
:deep(.el-date-editor.el-input),
:deep(.el-select),
:deep(.el-input) {
  width: 100%;
}

@media (max-width: 960px) {
  .dynamic-condition-row {
    grid-template-columns: 1fr;
  }

  .condition-aggregate {
    grid-column: auto;
  }
}
</style>
