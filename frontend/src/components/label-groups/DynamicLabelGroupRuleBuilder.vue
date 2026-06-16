<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Delete, Plus } from '@element-plus/icons-vue';
import DynamicRuleConditionGroup from './DynamicRuleConditionGroup.vue';
import type {
  LabelGroupDynamicRuleRelation,
  LabelGroupDynamicRuleSource,
} from '../../types/api';
import type {
  DynamicRuleFormState,
  RuleRelationFormState,
  RuleSourceFieldOption,
} from '../../views/label-groups/label-group-settings';
import {
  createEmptyCondition,
  createEmptyConditionGroup,
  valueTypeLabel,
} from '../../views/label-groups/label-group-settings';

const props = defineProps<{
  modelValue: DynamicRuleFormState;
  sources: LabelGroupDynamicRuleSource[];
  relations: LabelGroupDynamicRuleRelation[];
  summary: string;
  previewing?: boolean;
}>();

const emit = defineEmits<{
  (event: 'preview'): void;
}>();

const sourceMap = computed(() => new Map(props.sources.map((source) => [source.key, source])));
const outputSource = computed(() => sourceMap.value.get(props.modelValue.outputSourceKey) ?? null);
const outputFieldOptions = computed(() => outputSource.value?.fields.filter((field) => field.outputSupported) ?? []);
const aggregateOptions = computed(() =>
  props.modelValue.aggregations.map((item) => ({ label: item.label || item.key, value: item.key })),
);
const advancedPanel = ref<string[]>([]);
const advancedEnabled = computed(() =>
  props.modelValue.relations.length > 0
  || props.modelValue.aggregations.length > 0
  || props.modelValue.havingGroup.conditions.length > 0,
);
const relatedSourceKeys = computed(() => {
  const keys = new Set<string>();
  for (const relation of props.modelValue.relations) {
    const sourceKey = relatedSourceOf(relation);
    if (sourceKey) {
      keys.add(sourceKey);
    }
  }
  for (const group of props.modelValue.filterGroup.groups) {
    if (group.sourceKey && group.sourceKey !== props.modelValue.outputSourceKey) {
      keys.add(group.sourceKey);
    }
  }
  return Array.from(keys);
});

watch(
  () => props.modelValue.outputSourceKey,
  (sourceKey) => {
    props.modelValue.filterGroup.sourceKey = sourceKey;
    props.modelValue.filterGroup.conditions = [];
    props.modelValue.filterGroup.groups = [];
    props.modelValue.outputFieldKey = '';
    props.modelValue.relations = [];
    props.modelValue.groupBy = [];
    props.modelValue.aggregations = [];
    props.modelValue.havingGroup = createEmptyConditionGroup();
    props.modelValue.sort = [];
  },
);

watch(
  () => props.modelValue.outputFieldKey,
  () => ensureOutputGrouping(),
);

watch(advancedEnabled, (enabled) => {
  if (enabled && !advancedPanel.value.includes('advanced')) {
    advancedPanel.value = ['advanced'];
  }
});

function sourceName(sourceKey: string) {
  return sourceMap.value.get(sourceKey)?.name ?? sourceKey;
}

function fieldName(sourceKey: string, fieldKey: string) {
  return sourceMap.value.get(sourceKey)?.fields.find((field) => field.key === fieldKey)?.name ?? fieldKey;
}

function sourceFieldOptions(sourceKey: string, predicate?: (field: RuleSourceFieldOption) => boolean): RuleSourceFieldOption[] {
  const source = sourceMap.value.get(sourceKey);
  if (!source) {
    return [];
  }
  return source.fields
    .map((field) => ({
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
    }))
    .filter((field) => (predicate ? predicate(field) : true));
}

function relationOptionsFor(sourceKey: string) {
  if (!props.modelValue.outputSourceKey || !sourceKey) {
    return [];
  }
  return props.relations.filter((relation) => {
    const pair = [relation.leftSourceKey, relation.rightSourceKey];
    return pair.includes(props.modelValue.outputSourceKey) && pair.includes(sourceKey);
  });
}

function relationLabel(relation: LabelGroupDynamicRuleRelation) {
  return `${sourceName(relation.leftSourceKey)}.${fieldName(relation.leftSourceKey, relation.leftFieldKey)} = ${sourceName(relation.rightSourceKey)}.${fieldName(relation.rightSourceKey, relation.rightFieldKey)}`;
}

function relationValue(relation: LabelGroupDynamicRuleRelation) {
  return [
    relation.leftSourceKey,
    relation.leftFieldKey,
    relation.rightSourceKey,
    relation.rightFieldKey,
    relation.matchOperator,
    relation.normalizer ?? 'NONE',
  ].join('|');
}

function addRelation() {
  const targetSourceKey = props.sources.find((source) => source.key !== props.modelValue.outputSourceKey)?.key ?? '';
  if (!targetSourceKey) {
    return;
  }
  const relation = relationOptionsFor(targetSourceKey)[0];
  props.modelValue.relations.push(relation
    ? toRelationForm(relation)
    : {
      leftSourceKey: props.modelValue.outputSourceKey,
      leftFieldKey: '',
      rightSourceKey: targetSourceKey,
      rightFieldKey: '',
      matchOperator: 'eq',
      normalizer: 'NONE',
    });
  ensureRelatedConditionGroup(targetSourceKey);
  ensureOutputGrouping();
}

function changeRelationSource(relation: RuleRelationFormState, sourceKey: string) {
  const registered = relationOptionsFor(sourceKey)[0];
  if (registered) {
    Object.assign(relation, toRelationForm(registered));
  } else {
    relation.leftSourceKey = props.modelValue.outputSourceKey;
    relation.leftFieldKey = '';
    relation.rightSourceKey = sourceKey;
    relation.rightFieldKey = '';
    relation.matchOperator = 'eq';
    relation.normalizer = 'NONE';
  }
  ensureRelatedConditionGroup(sourceKey);
}

function selectRegisteredRelation(relation: RuleRelationFormState, value: string | string[]) {
  const key = String(Array.isArray(value) ? value[0] ?? '' : value ?? '');
  const registered = props.relations.find((item) => relationValue(item) === key);
  if (registered) {
    Object.assign(relation, toRelationForm(registered));
    ensureRelatedConditionGroup(relatedSourceOf(relation));
  }
}

function removeRelation(index: number) {
  const [removed] = props.modelValue.relations.splice(index, 1);
  const sourceKey = removed ? relatedSourceOf(removed) : '';
  if (!sourceKey || props.modelValue.relations.some((relation) => relatedSourceOf(relation) === sourceKey)) {
    return;
  }
  props.modelValue.filterGroup.groups = props.modelValue.filterGroup.groups.filter((group) => group.sourceKey !== sourceKey);
  props.modelValue.aggregations = props.modelValue.aggregations.filter((item) => item.sourceKey !== sourceKey);
}

function addAggregation(sourceKey: string) {
  ensureOutputGrouping();
  const field = sourceFieldOptions(sourceKey, (item) => item.aggregateSupported)[0];
  if (!field) {
    return;
  }
  const key = nextAggregateKey(sourceKey);
  props.modelValue.aggregations.push({
    key,
    sourceKey,
    fieldKey: field.fieldKey,
    function: 'count',
    label: `${sourceName(sourceKey)}数量`,
  });
  props.modelValue.havingGroup.conditions.push({
    ...createEmptyCondition(),
    aggregateKey: key,
    sourceKey: '',
    fieldKey: '',
    operator: 'gt',
    value: '0',
  });
}

function removeAggregation(index: number) {
  const [removed] = props.modelValue.aggregations.splice(index, 1);
  if (!removed) {
    return;
  }
  props.modelValue.havingGroup.conditions = props.modelValue.havingGroup.conditions
    .filter((condition) => condition.aggregateKey !== removed.key);
}

function ensureOutputGrouping() {
  if (!props.modelValue.outputSourceKey || !props.modelValue.outputFieldKey) {
    return;
  }
  props.modelValue.groupBy = [{
    sourceKey: props.modelValue.outputSourceKey,
    fieldKey: props.modelValue.outputFieldKey,
  }];
}

function relatedSourceOf(relation: RuleRelationFormState) {
  if (relation.leftSourceKey === props.modelValue.outputSourceKey) {
    return relation.rightSourceKey;
  }
  if (relation.rightSourceKey === props.modelValue.outputSourceKey) {
    return relation.leftSourceKey;
  }
  return relation.rightSourceKey || relation.leftSourceKey;
}

function ensureRelatedConditionGroup(sourceKey: string) {
  let group = props.modelValue.filterGroup.groups.find((item) => item.sourceKey === sourceKey);
  if (!group) {
    group = createEmptyConditionGroup(sourceKey);
    props.modelValue.filterGroup.groups.push(group);
  }
  return group;
}

function nextAggregateKey(sourceKey: string) {
  const base = `${sourceKey.replace(/[^A-Za-z0-9_]/g, '_')}_count`;
  if (!props.modelValue.aggregations.some((item) => item.key === base)) {
    return base;
  }
  let index = 2;
  while (props.modelValue.aggregations.some((item) => item.key === `${base}_${index}`)) {
    index += 1;
  }
  return `${base}_${index}`;
}

function toRelationForm(relation: LabelGroupDynamicRuleRelation): RuleRelationFormState {
  return {
    leftSourceKey: relation.leftSourceKey,
    leftFieldKey: relation.leftFieldKey,
    rightSourceKey: relation.rightSourceKey,
    rightFieldKey: relation.rightFieldKey,
    matchOperator: relation.matchOperator,
    normalizer: relation.normalizer ?? 'NONE',
  };
}
</script>

<template>
  <div class="dynamic-rule-simple">
    <el-alert class="dynamic-rule-summary" type="info" :closable="false" :title="summary" />

    <el-form-item label="成员来源" required>
      <el-select v-model="modelValue.outputSourceKey" filterable placeholder="选择业务数据" class="dynamic-rule-control">
        <el-option v-for="source in sources" :key="source.key" :label="source.name" :value="source.key" />
      </el-select>
    </el-form-item>

    <el-form-item label="成员字段" required>
      <el-select v-model="modelValue.outputFieldKey" filterable placeholder="选择标签组成员值" class="dynamic-rule-control">
        <el-option
          v-for="field in outputFieldOptions"
          :key="field.key"
          :label="`${field.name} / ${valueTypeLabel(field.valueType)}`"
          :value="field.key"
        />
      </el-select>
    </el-form-item>

    <el-form-item label="成员条件">
      <DynamicRuleConditionGroup
        :group="modelValue.filterGroup"
        :sources="sources"
        :preferred-source-key="modelValue.outputSourceKey"
        :show-source-selector="false"
        :allow-child-groups="false"
        compact
      />
    </el-form-item>

    <el-form-item label="结果设置">
      <div class="dynamic-rule-inline">
        <el-switch v-model="modelValue.distinct" active-text="去重" inactive-text="不去重" />
        <span class="dynamic-rule-limit-label">最多保留</span>
        <el-input-number v-model="modelValue.limit" :min="1" :max="200" controls-position="right" />
        <span class="dynamic-rule-limit-label">个成员</span>
        <el-button type="primary" plain :loading="previewing" @click="emit('preview')">预览成员</el-button>
      </div>
    </el-form-item>

    <el-collapse v-model="advancedPanel" class="dynamic-rule-advanced">
      <el-collapse-item name="advanced" title="高级规则">
        <el-form-item label="关联数据">
          <div class="advanced-list">
            <div v-if="!modelValue.relations.length" class="advanced-empty">
              只有需要跨业务数据判断时才添加，例如“成员在最近 3 天有议题”。
            </div>
            <div v-for="(relation, index) in modelValue.relations" :key="`relation-${index}`" class="advanced-row">
              <el-select
                :model-value="relatedSourceOf(relation)"
                filterable
                placeholder="关联数据"
                @change="changeRelationSource(relation, String($event))"
              >
                <el-option
                  v-for="source in sources.filter((item) => item.key !== modelValue.outputSourceKey)"
                  :key="source.key"
                  :label="source.name"
                  :value="source.key"
                />
              </el-select>
              <el-select
                :model-value="relationValue({
                  name: '',
                  leftSourceKey: relation.leftSourceKey,
                  leftFieldKey: relation.leftFieldKey,
                  rightSourceKey: relation.rightSourceKey,
                  rightFieldKey: relation.rightFieldKey,
                  matchOperator: relation.matchOperator,
                  normalizer: relation.normalizer,
                })"
                filterable
                placeholder="匹配方式"
                @change="selectRegisteredRelation(relation, $event)"
              >
                <el-option
                  v-for="item in relationOptionsFor(relatedSourceOf(relation))"
                  :key="relationValue(item)"
                  :label="relationLabel(item)"
                  :value="relationValue(item)"
                />
              </el-select>
              <template v-if="!relationOptionsFor(relatedSourceOf(relation)).length">
                <el-select v-model="relation.leftFieldKey" filterable placeholder="成员字段">
                  <el-option
                    v-for="field in sourceFieldOptions(modelValue.outputSourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${valueTypeLabel(field.valueType)}`"
                    :value="field.fieldKey"
                  />
                </el-select>
                <el-select v-model="relation.rightFieldKey" filterable placeholder="关联字段">
                  <el-option
                    v-for="field in sourceFieldOptions(relatedSourceOf(relation))"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${valueTypeLabel(field.valueType)}`"
                    :value="field.fieldKey"
                  />
                </el-select>
              </template>
              <el-button :icon="Delete" text type="danger" @click="removeRelation(index)" />
            </div>
            <el-button :icon="Plus" plain @click="addRelation">添加关联数据</el-button>
          </div>
        </el-form-item>

        <template v-for="sourceKey in relatedSourceKeys" :key="sourceKey">
          <el-form-item :label="`${sourceName(sourceKey)}条件`">
            <div class="advanced-list">
              <DynamicRuleConditionGroup
                :group="ensureRelatedConditionGroup(sourceKey)"
                :sources="sources"
                :preferred-source-key="sourceKey"
                :show-source-selector="false"
                compact
              />
              <div class="dynamic-rule-inline">
                <el-button size="small" @click="addAggregation(sourceKey)">要求每个成员有关联数据</el-button>
              </div>
            </div>
          </el-form-item>
        </template>

        <el-form-item v-if="modelValue.aggregations.length" label="统计门槛">
          <div class="advanced-list">
            <div v-for="(aggregation, index) in modelValue.aggregations" :key="aggregation.key" class="advanced-row">
              <el-select v-model="aggregation.sourceKey" filterable placeholder="统计数据">
                <el-option v-for="source in sources" :key="source.key" :label="source.name" :value="source.key" />
              </el-select>
              <el-select v-model="aggregation.fieldKey" filterable placeholder="统计字段">
                <el-option
                  v-for="field in sourceFieldOptions(aggregation.sourceKey, (item) => item.aggregateSupported)"
                  :key="field.fieldKey"
                  :label="`${field.fieldName} / ${valueTypeLabel(field.valueType)}`"
                  :value="field.fieldKey"
                />
              </el-select>
              <el-select v-model="aggregation.function" placeholder="统计方式">
                <el-option label="数量" value="count" />
                <el-option label="去重数量" value="countDistinct" />
                <el-option label="求和" value="sum" />
                <el-option label="平均" value="avg" />
                <el-option label="最小" value="min" />
                <el-option label="最大" value="max" />
              </el-select>
              <el-button :icon="Delete" text type="danger" @click="removeAggregation(index)" />
            </div>
            <DynamicRuleConditionGroup
              :group="modelValue.havingGroup"
              :sources="sources"
              :aggregate-options="aggregateOptions"
              aggregate-mode
              compact
            />
          </div>
        </el-form-item>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<style scoped>
.dynamic-rule-simple {
  display: contents;
}

.dynamic-rule-summary {
  grid-column: 1 / -1;
}

.dynamic-rule-control {
  width: 100%;
}

.dynamic-rule-inline {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  width: 100%;
}

.dynamic-rule-limit-label {
  color: rgba(15, 23, 42, 0.62);
  font-size: 13px;
}

.dynamic-rule-advanced {
  grid-column: 1 / -1;
  border-top: 0;
  border-bottom: 0;
}

.dynamic-rule-advanced :deep(.el-collapse-item__header) {
  height: 38px;
  border-bottom: 0;
  color: rgba(15, 23, 42, 0.76);
  font-weight: 500;
}

.dynamic-rule-advanced :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}

.dynamic-rule-advanced :deep(.el-collapse-item__content) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
  padding-bottom: 0;
}

.dynamic-rule-advanced :deep(.el-form-item) {
  grid-column: 1 / -1;
}

.advanced-list {
  display: grid;
  gap: 8px;
  width: 100%;
}

.advanced-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr)) auto;
  gap: 8px;
  align-items: center;
}

.advanced-row :deep(.el-select) {
  width: 100%;
}

.advanced-empty {
  padding: 8px 10px;
  border: 1px dashed rgba(15, 23, 42, 0.16);
  border-radius: 6px;
  color: rgba(15, 23, 42, 0.52);
  font-size: 13px;
}

@media (max-width: 900px) {
  .dynamic-rule-advanced :deep(.el-collapse-item__content),
  .advanced-row {
    grid-template-columns: 1fr;
  }
}
</style>
