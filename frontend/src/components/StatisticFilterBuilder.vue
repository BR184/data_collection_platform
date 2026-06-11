<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ArrowUp, Delete, MoreFilled } from '@element-plus/icons-vue';
import SmartSelect from './base/SmartSelect.vue';
import { ElMessageBox } from '../element-plus-services';
import { labelGroupsApi } from '../api-client/label-groups-api';
// 高级筛选构建器把字段、操作符和值拆成可组合条件，供记录页和统计板复用。
// 组件只维护前端草稿结构，最终查询表达式由调用方序列化后交给接口。
import type { StatisticFilterField, StatisticFilterOperator } from '../types/api';
import type { LabelGroup } from '../types/api';
import type { RecordTableFilterOption } from '../types/record-table';
import {
  createFilterConditionDraft,
  labelGroupSelectValue,
  operatorLabel,
  parseLabelGroupSelectValue,
  usesSecondaryValue,
  type StatisticFilterConditionDraft,
  type StatisticFilterDraftGroup,
} from './statistic-board-filters';

const visibleConditionLimit = 2;

const props = withDefaults(
  defineProps<{
    modelValue: StatisticFilterDraftGroup;
    fields: StatisticFilterField[];
    addButtonText?: string;
  }>(),
  {
    addButtonText: '添加条件',
  },
);

const conditionsExpanded = ref(false);
const batchDeleteMode = ref(false);
const selectedConditionIds = ref<string[]>([]);
const labelGroupsByValueType = ref<Record<string, LabelGroup[]>>({});

const visibleConditions = computed(() => {
  if (conditionsExpanded.value || props.modelValue.conditions.length <= visibleConditionLimit) {
    return props.modelValue.conditions;
  }
  return props.modelValue.conditions.slice(0, visibleConditionLimit);
});

const hiddenConditionsCount = computed(() => Math.max(0, props.modelValue.conditions.length - visibleConditions.value.length));
const selectedConditionCount = computed(() => selectedConditionIds.value.length);
const allConditionsSelected = computed(
  () => props.modelValue.conditions.length > 0 && selectedConditionIds.value.length === props.modelValue.conditions.length,
);

watch(
  () => props.modelValue.conditions.length,
  (length) => {
    if (length <= visibleConditionLimit) {
      conditionsExpanded.value = false;
    }
    selectedConditionIds.value = selectedConditionIds.value.filter((id) => props.modelValue.conditions.some((condition) => condition.id === id));
    if (!length) {
      batchDeleteMode.value = false;
    }
  },
);

watch(
  () => props.modelValue.conditions.map((condition) => `${condition.fieldKey}:${condition.operator}`).join('|'),
  () => {
    void ensureLabelGroupsForVisibleFields();
  },
  { immediate: true },
);

function addFilterCondition() {
  const field = props.fields[0];
  props.modelValue.conditions.push(createFilterConditionDraft(field));
}

function removeFilterCondition(conditionId: string) {
  const index = props.modelValue.conditions.findIndex((condition) => condition.id === conditionId);
  if (index >= 0) {
    props.modelValue.conditions.splice(index, 1);
  }
}

async function confirmRemoveFilterCondition(conditionId: string) {
  try {
    await ElMessageBox.confirm('确认删除这条筛选条件吗？', '删除条件', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    removeFilterCondition(conditionId);
  } catch {
    // 用户取消删除。
  }
}

function openBatchDeleteMode() {
  batchDeleteMode.value = true;
  conditionsExpanded.value = true;
  selectedConditionIds.value = [];
}

function closeBatchDeleteMode() {
  batchDeleteMode.value = false;
  selectedConditionIds.value = [];
}

function toggleSelectAllConditions() {
  if (allConditionsSelected.value) {
    selectedConditionIds.value = [];
    return;
  }
  selectedConditionIds.value = props.modelValue.conditions.map((condition) => condition.id);
}

function removeConditionsByIds(conditionIds: string[]) {
  const selectedIds = new Set(conditionIds);
  props.modelValue.conditions.splice(
    0,
    props.modelValue.conditions.length,
    ...props.modelValue.conditions.filter((condition) => !selectedIds.has(condition.id)),
  );
  selectedConditionIds.value = [];
}

async function confirmRemoveSelectedConditions() {
  if (!selectedConditionCount.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedConditionCount.value} 条筛选条件吗？`, '批量删除条件', {
      type: 'warning',
      confirmButtonText: '删除选中',
      cancelButtonText: '取消',
    });
    removeConditionsByIds(selectedConditionIds.value);
    closeBatchDeleteMode();
  } catch {
    // 用户取消删除。
  }
}

async function confirmClearFilterConditions() {
  if (!props.modelValue.conditions.length) {
    return;
  }
  try {
    await ElMessageBox.confirm(`确认清空全部 ${props.modelValue.conditions.length} 条筛选条件吗？`, '清空条件', {
      type: 'warning',
      confirmButtonText: '清空全部',
      cancelButtonText: '取消',
    });
    props.modelValue.conditions.splice(0, props.modelValue.conditions.length);
    closeBatchDeleteMode();
  } catch {
    // 用户取消清空。
  }
}

function fieldForCondition(fieldKey: string) {
  return props.fields.find((field) => field.key === fieldKey) ?? null;
}

function handleConditionFieldChange(condition: StatisticFilterConditionDraft) {
  const field = fieldForCondition(condition.fieldKey);
  condition.operator = (field?.operators?.[0] ?? '') as StatisticFilterOperator | '';
  condition.value = '';
  condition.secondaryValue = '';
  clearLabelGroupValue(condition);
  void ensureLabelGroupsForField(field);
}

function operatorOptionsForCondition(condition: StatisticFilterConditionDraft) {
  return fieldForCondition(condition.fieldKey)?.operators ?? [];
}

function usesDatePicker(condition: StatisticFilterConditionDraft) {
  return fieldForCondition(condition.fieldKey)?.type === 'datetime';
}

function datePickerType(condition: StatisticFilterConditionDraft) {
  return (
    {
      year: 'year',
      month: 'month',
      day: 'date',
      at: 'datetime',
      before: 'datetime',
      after: 'datetime',
      between: 'datetime',
    } as Record<string, string>
  )[condition.operator] ?? 'datetime';
}

function dateValueFormat(condition: StatisticFilterConditionDraft) {
  return (
    {
      year: 'YYYY',
      month: 'YYYY-MM',
      day: 'YYYY-MM-DD',
      at: 'YYYY-MM-DD HH:mm:ss',
      before: 'YYYY-MM-DD HH:mm:ss',
      after: 'YYYY-MM-DD HH:mm:ss',
      between: 'YYYY-MM-DD HH:mm:ss',
    } as Record<string, string>
  )[condition.operator] ?? 'YYYY-MM-DD HH:mm:ss';
}

function isNumericField(condition: StatisticFilterConditionDraft) {
  return fieldForCondition(condition.fieldKey)?.type === 'number';
}

function isSelectField(condition: StatisticFilterConditionDraft) {
  return fieldForCondition(condition.fieldKey)?.type === 'select';
}

function fieldOptions(condition: StatisticFilterConditionDraft) {
  return fieldForCondition(condition.fieldKey)?.options ?? [];
}

function needsValue(condition: StatisticFilterConditionDraft) {
  return condition.operator !== 'isEmpty' && condition.operator !== 'isNotEmpty';
}

function fieldSelectOptions(): RecordTableFilterOption[] {
  return props.fields.map((field) => ({ label: field.label, value: field.key }));
}

function operatorSelectOptions(condition: StatisticFilterConditionDraft): RecordTableFilterOption[] {
  return operatorOptionsForCondition(condition).map((operator) => ({ label: operatorLabel(operator), value: operator }));
}

function handleFieldSelectChange(condition: StatisticFilterConditionDraft, value: string | string[]) {
  condition.fieldKey = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  handleConditionFieldChange(condition);
}

function handleOperatorSelectChange(condition: StatisticFilterConditionDraft, value: string | string[]) {
  condition.operator = String(Array.isArray(value) ? value[0] ?? '' : value ?? '') as StatisticFilterOperator | '';
  condition.value = '';
  condition.secondaryValue = '';
  clearLabelGroupValue(condition);
  void ensureLabelGroupsForField(fieldForCondition(condition.fieldKey));
}

function handleValueSelectChange(condition: StatisticFilterConditionDraft, value: string | string[]) {
  const nextValue = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  const groupId = parseLabelGroupSelectValue(nextValue);
  if (groupId) {
    const group = labelGroupsForCondition(condition).find((item) => item.id === groupId);
    condition.value = labelGroupSelectValue(groupId);
    condition.valueType = 'LABEL_GROUP';
    condition.labelGroupId = groupId;
    condition.labelGroupName = group?.name ?? '';
    return;
  }
  condition.value = nextValue;
  clearLabelGroupValue(condition);
}

function supportsLabelGroupValue(condition: StatisticFilterConditionDraft) {
  const field = fieldForCondition(condition.fieldKey);
  return Boolean(field?.labelGroupEnabled) && (condition.operator === 'eq' || condition.operator === 'ne');
}

function labelGroupValueType(field: StatisticFilterField | null) {
  return field?.labelGroupValueType || 'STRING';
}

function labelGroupsForCondition(condition: StatisticFilterConditionDraft) {
  return labelGroupsByValueType.value[labelGroupValueType(fieldForCondition(condition.fieldKey))] ?? [];
}

function valueOptionsForCondition(condition: StatisticFilterConditionDraft): RecordTableFilterOption[] {
  const literalOptions = fieldOptions(condition);
  if (!supportsLabelGroupValue(condition)) {
    return literalOptions;
  }
  const groupOptions = labelGroupsForCondition(condition).map((group) => ({
    label: `标签组 / ${group.name}`,
    value: labelGroupSelectValue(group.id),
  }));
  return [...literalOptions, ...groupOptions];
}

async function ensureLabelGroupsForVisibleFields() {
  await Promise.all(props.modelValue.conditions.map((condition) => ensureLabelGroupsForField(fieldForCondition(condition.fieldKey))));
}

async function ensureLabelGroupsForField(field: StatisticFilterField | null) {
  if (!field?.labelGroupEnabled) {
    return;
  }
  const valueType = labelGroupValueType(field);
  if (labelGroupsByValueType.value[valueType]) {
    return;
  }
  const groups = await labelGroupsApi.listLabelGroups({ valueType, enabled: true });
  labelGroupsByValueType.value = {
    ...labelGroupsByValueType.value,
    [valueType]: groups,
  };
}

function clearLabelGroupValue(condition: StatisticFilterConditionDraft) {
  condition.valueType = 'LITERAL';
  condition.labelGroupId = null;
  condition.labelGroupName = null;
}
</script>

<template>
  <div class="stat-filter-builder" :class="{ 'is-expanded': conditionsExpanded }">
    <el-segmented
      :model-value="modelValue.logic"
      :options="[{ label: '满足全部', value: 'AND' }, { label: '满足任意', value: 'OR' }]"
      class="stat-filter-logic"
      @update:model-value="modelValue.logic = $event === 'OR' ? 'OR' : 'AND'"
    />
    <el-button plain class="stat-filter-add" @click="addFilterCondition">{{ addButtonText }}</el-button>
    <div v-if="modelValue.conditions.length" class="stat-filter-list">
      <div
        v-for="condition in visibleConditions"
        :key="condition.id"
        class="stat-filter-row"
        :class="{ 'is-selecting': batchDeleteMode }"
      >
        <el-checkbox
          v-if="batchDeleteMode"
          v-model="selectedConditionIds"
          :value="condition.id"
          class="stat-filter-check"
          aria-label="选择条件"
        />
        <SmartSelect
          :model-value="condition.fieldKey"
          class="stat-filter-field"
          placeholder="字段"
          :options="fieldSelectOptions()"
          @change="handleFieldSelectChange(condition, $event)"
        />
        <SmartSelect
          :model-value="condition.operator"
          class="stat-filter-operator"
          placeholder="关系"
          :options="operatorSelectOptions(condition)"
          @change="handleOperatorSelectChange(condition, $event)"
        />
        <template v-if="needsValue(condition)">
          <SmartSelect
            v-if="isSelectField(condition)"
            :model-value="String(condition.value ?? '')"
            class="stat-filter-value"
            placeholder="值"
            :options="valueOptionsForCondition(condition)"
            @change="handleValueSelectChange(condition, $event)"
          />
          <el-input-number
            v-else-if="isNumericField(condition)"
            v-model="condition.value"
            class="stat-filter-value"
            controls-position="right"
            placeholder="值"
          />
          <el-date-picker
            v-else-if="usesDatePicker(condition)"
            v-model="condition.value"
            class="stat-filter-value"
            :type="datePickerType(condition)"
            :value-format="dateValueFormat(condition)"
            placeholder="时间"
          />
          <el-input v-else v-model="condition.value" class="stat-filter-value" placeholder="值" clearable />
        </template>
        <el-input-number
          v-if="usesSecondaryValue(condition.operator) && isNumericField(condition)"
          v-model="condition.secondaryValue"
          class="stat-filter-value secondary"
          controls-position="right"
          placeholder="结束值"
        />
        <el-date-picker
          v-else-if="usesSecondaryValue(condition.operator) && usesDatePicker(condition)"
          v-model="condition.secondaryValue"
          class="stat-filter-value secondary"
          :type="datePickerType(condition)"
          :value-format="dateValueFormat(condition)"
          placeholder="结束时间"
        />
        <el-input
          v-else-if="usesSecondaryValue(condition.operator)"
          v-model="condition.secondaryValue"
          class="stat-filter-value secondary"
          placeholder="结束值"
          clearable
        />
        <el-tooltip content="删除条件" placement="top">
          <el-button
            :icon="Delete"
            text
            type="danger"
            class="stat-filter-remove"
            aria-label="删除条件"
            @click="confirmRemoveFilterCondition(condition.id)"
          />
        </el-tooltip>
      </div>
      <el-tooltip v-if="hiddenConditionsCount > 0" :content="`展开 ${hiddenConditionsCount} 条隐藏条件`" placement="top">
        <el-button
          :icon="MoreFilled"
          text
          class="stat-filter-more"
          aria-label="展开隐藏条件"
          @click="conditionsExpanded = true"
        >
          {{ hiddenConditionsCount }}
        </el-button>
      </el-tooltip>
      <el-tooltip v-else-if="conditionsExpanded && modelValue.conditions.length > visibleConditionLimit" content="收起条件" placement="top">
        <el-button
          :icon="ArrowUp"
          text
          class="stat-filter-more"
          aria-label="收起条件"
          @click="conditionsExpanded = false"
        />
      </el-tooltip>
    </div>
    <div v-if="modelValue.conditions.length" class="stat-filter-actions">
      <template v-if="batchDeleteMode">
        <span class="stat-filter-selected">已选 {{ selectedConditionCount }}</span>
        <el-button text @click="toggleSelectAllConditions">{{ allConditionsSelected ? '取消全选' : '全选' }}</el-button>
        <el-button text type="danger" :disabled="!selectedConditionCount" @click="confirmRemoveSelectedConditions">删除选中</el-button>
        <el-button text @click="closeBatchDeleteMode">取消</el-button>
      </template>
      <template v-else>
        <el-button v-if="modelValue.conditions.length > 1" text @click="openBatchDeleteMode">批量删除</el-button>
        <el-button text type="danger" @click="confirmClearFilterConditions">清空全部</el-button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.stat-filter-builder {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.stat-filter-logic {
  flex: 0 0 auto;
  width: max-content;
  max-width: 100%;
}

.stat-filter-add {
  flex: 0 0 auto;
  width: max-content;
}

.stat-filter-list {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex: 0 1 auto;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}

.stat-filter-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: 168px;
  min-height: 32px;
}

.stat-filter-selected {
  padding: 0 4px;
  color: rgba(15, 23, 42, 0.56);
  font-size: 13px;
}

.stat-filter-builder.is-expanded .stat-filter-list {
  max-height: 118px;
  overflow-y: auto;
  padding-right: 4px;
}

.stat-filter-row {
  display: grid;
  grid-template-columns: 112px 82px 124px 28px;
  align-items: center;
  gap: 4px;
  width: max-content;
  max-width: 100%;
  min-width: 0;
  min-height: 32px;
  padding: 2px 4px 2px 8px;
  border: 1px solid rgba(29, 78, 216, 0.12);
  border-radius: 6px;
  background: rgba(248, 250, 252, 0.94);
}

.stat-filter-row.is-selecting {
  grid-template-columns: 24px 112px 82px 124px 28px;
}

.stat-filter-check {
  justify-self: center;
}

.stat-filter-field,
.stat-filter-operator,
.stat-filter-value {
  width: 100%;
  min-width: 0;
}

.stat-filter-value.secondary {
  grid-column: 3;
}

.stat-filter-row.is-selecting .stat-filter-value.secondary {
  grid-column: 4;
}

.stat-filter-remove {
  width: 28px;
  height: 28px;
  min-width: 28px;
  justify-self: end;
}

.stat-filter-more {
  height: 32px;
  min-width: 36px;
  padding: 0 8px;
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 6px;
  background: #fff;
  color: rgba(15, 23, 42, 0.62);
}

:deep(.el-input-number),
:deep(.el-date-editor.el-input),
:deep(.el-select),
:deep(.el-input) {
  width: 100%;
}

:deep(.el-input__wrapper),
:deep(.el-select__wrapper) {
  min-height: 28px;
  box-shadow: none;
  background: #fff;
}

:deep(.el-input__inner) {
  height: 28px;
  font-size: 13px;
}

@media (max-width: 1180px) {
  .stat-filter-actions {
    order: 4;
    margin-left: 0;
  }

  .stat-filter-row {
    grid-template-columns: minmax(116px, 1fr) minmax(84px, 0.7fr) minmax(128px, 1fr) 28px;
    width: min(100%, 394px);
  }

  .stat-filter-row.is-selecting {
    grid-template-columns: 24px minmax(116px, 1fr) minmax(84px, 0.7fr) minmax(128px, 1fr) 28px;
    width: min(100%, 422px);
  }

  .stat-filter-value.secondary {
    grid-column: 3;
  }

  .stat-filter-row.is-selecting .stat-filter-value.secondary {
    grid-column: 4;
  }
}

@media (max-width: 760px) {
  .stat-filter-list,
  .stat-filter-actions {
    flex-basis: 100%;
  }

  .stat-filter-row {
    grid-template-columns: 1fr;
  }

  .stat-filter-value.secondary,
  .stat-filter-remove {
    grid-column: auto;
  }

  .stat-filter-remove {
    justify-self: start;
  }
}
</style>
