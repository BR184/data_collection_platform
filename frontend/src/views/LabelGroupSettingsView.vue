<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import DynamicRuleConditionGroup from '../components/label-groups/DynamicRuleConditionGroup.vue';
import LabelGroupMemberPicker from '../components/label-groups/LabelGroupMemberPicker.vue';
import type {
  LabelDimension,
  LabelGroup,
  LabelGroupDynamicRulePreview,
  LabelGroupDynamicRuleRelation,
  LabelGroupDynamicRuleSource,
} from '../types/api';
import {
  buildChildGroupExpandedPreview,
  buildLabelGroupSaveRequest,
  buildMemberPreview,
  buildDynamicRuleSummary,
  buildRuleConfig,
  createEmptyLabelGroupForm,
  createLabelGroupForm,
  inferValueTypeFromMembers,
  unavailableMemberCount,
  validateDynamicRuleForm,
  validateLabelGroupForm,
  valueTypeLabel,
  type LabelGroupFormState,
  type LabelGroupType,
  type RuleSourceFieldOption,
} from './label-groups/label-group-settings';

const loading = ref(false);
const saving = ref(false);
const previewingDynamicRule = ref(false);
const deletingId = ref<number | null>(null);
const dialogVisible = ref(false);
const editMode = ref(false);
const keyword = ref('');
const valueTypeFilter = ref('');
const candidateDimensionKey = ref('');
const dimensions = ref<LabelDimension[]>([]);
const groups = ref<LabelGroup[]>([]);
const dynamicRuleSources = ref<LabelGroupDynamicRuleSource[]>([]);
const dynamicRuleRelations = ref<LabelGroupDynamicRuleRelation[]>([]);
const dynamicRulePreview = ref<LabelGroupDynamicRulePreview | null>(null);
const form = ref<LabelGroupFormState>(createEmptyLabelGroupForm());

const valueTypeOptions = [
  { label: '字符串', value: 'STRING' },
  { label: '数字', value: 'NUMBER' },
  { label: '日期', value: 'DATE' },
  { label: '布尔', value: 'BOOLEAN' },
];

const groupTypeOptions: Array<{ label: string; value: LabelGroupType }> = [
  { label: '静态组', value: 'STATIC' },
  { label: '动态组', value: 'DYNAMIC' },
  { label: '组合组', value: 'COMPOSITE' },
];

const candidateDimensions = computed(() => dimensions.value.filter((item) => item.staticSupported));
const dynamicRuleSourceMap = computed(() => new Map(dynamicRuleSources.value.map((item) => [item.key, item])));
const currentDynamicSource = computed(() => dynamicRuleSourceMap.value.get(form.value.dynamicRule.outputSourceKey));
const currentDynamicField = computed(
  () => currentDynamicSource.value?.fields.find((field) => field.key === form.value.dynamicRule.outputFieldKey),
);
const dynamicFieldOptions = computed(() => currentDynamicSource.value?.fields ?? []);
const dynamicRuleSummary = computed(() => buildDynamicRuleSummary(
  form.value.dynamicRule,
  currentDynamicSource.value?.name ?? '',
  currentDynamicField.value?.name ?? '',
));
const aggregationOptions = computed(() =>
  form.value.dynamicRule.aggregations.map((item) => ({
    label: item.label || item.key,
    value: item.key,
  })),
);
const selectedChildGroups = computed(() =>
  form.value.childGroupIds
    .map((id) => groups.value.find((group) => group.id === id))
    .filter((group): group is LabelGroup => Boolean(group)),
);
const inferredMemberValueType = computed(() => inferValueTypeFromMembers(form.value.members));
const inferredChildValueType = computed(() => {
  const first = selectedChildGroups.value.find((group) => group.valueType)?.valueType ?? '';
  if (!first) {
    return '';
  }
  return selectedChildGroups.value.every((group) => !group.valueType || group.valueType === first)
    ? first
    : 'MIXED';
});
const currentValueType = computed(() =>
  inferredMemberValueType.value
  || inferredChildValueType.value
  || (form.value.groupType === 'DYNAMIC' ? currentDynamicField.value?.valueType ?? '' : ''),
);
const childGroupOptions = computed(() => {
  const currentId = form.value.id;
  const valueType = currentValueType.value;
  return groups.value
    .filter((group) => group.id !== currentId)
    .filter((group) => group.enabled)
    .filter((group) => form.value.groupType === 'COMPOSITE' || group.groupType === 'STATIC')
    .filter((group) => !valueType || valueType === 'MIXED' || !group.valueType || group.valueType === valueType);
});
const childGroupExpandedPreview = computed(() =>
  buildChildGroupExpandedPreview(groups.value, form.value.childGroupIds),
);

onMounted(async () => {
  await Promise.all([loadDimensions(), loadGroups(), loadDynamicRuleSources(), loadDynamicRuleRelations()]);
});

watch(
  () => form.value.groupType,
  (groupType) => {
    if (groupType === 'COMPOSITE') {
      form.value.members = [];
    }
    if (groupType === 'DYNAMIC') {
      form.value.childGroupIds = [];
      form.value.members = [];
    }
    if (groupType === 'STATIC') {
      form.value.childGroupIds = form.value.childGroupIds.filter((id) => {
        const group = groups.value.find((item) => item.id === id);
        return group?.groupType === 'STATIC';
      });
    }
  },
);

watch(currentValueType, (valueType) => {
  if (!valueType || valueType === 'MIXED') {
    return;
  }
  form.value.childGroupIds = form.value.childGroupIds.filter((id) => {
    const group = groups.value.find((item) => item.id === id);
    return !group?.valueType || group.valueType === valueType;
  });
});

function sourceFieldOptions(sourceKey: string): RuleSourceFieldOption[] {
  const source = dynamicRuleSourceMap.value.get(sourceKey);
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
  }));
}

function relationDisplay(relation: {
  leftSourceKey: string;
  leftFieldKey: string;
  rightSourceKey: string;
  rightFieldKey: string;
  normalizer?: string | null;
}) {
  const leftSource = dynamicRuleSourceMap.value.get(relation.leftSourceKey);
  const rightSource = dynamicRuleSourceMap.value.get(relation.rightSourceKey);
  const leftField = leftSource?.fields.find((field) => field.key === relation.leftFieldKey);
  const rightField = rightSource?.fields.find((field) => field.key === relation.rightFieldKey);
  return `${leftSource?.name ?? relation.leftSourceKey}.${leftField?.name ?? relation.leftFieldKey} = ${rightSource?.name ?? relation.rightSourceKey}.${rightField?.name ?? relation.rightFieldKey}`;
}

async function loadDimensions() {
  try {
    dimensions.value = await api.listLabelDimensions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '候选来源加载失败');
  }
}

async function loadGroups() {
  loading.value = true;
  try {
    groups.value = await api.listLabelGroups({
      valueType: valueTypeFilter.value || undefined,
      keyword: keyword.value || undefined,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '标签组加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadDynamicRuleSources() {
  try {
    dynamicRuleSources.value = await api.listDynamicRuleSources();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '动态规则数据源加载失败');
  }
}

async function loadDynamicRuleRelations() {
  try {
    dynamicRuleRelations.value = await api.listDynamicRuleRelations();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '动态规则逻辑关联加载失败');
  }
}

function openCreateDialog() {
  editMode.value = false;
  form.value = createEmptyLabelGroupForm();
  dynamicRulePreview.value = null;
  candidateDimensionKey.value = '';
  dialogVisible.value = true;
}

function openEditDialog(group: LabelGroup) {
  editMode.value = true;
  form.value = createLabelGroupForm(group);
  dynamicRulePreview.value = null;
  candidateDimensionKey.value = '';
  dialogVisible.value = true;
}

function addDynamicRuleRelation() {
  const fallback = dynamicRuleRelations.value[0];
  form.value.dynamicRule.relations.push({
    leftSourceKey: fallback?.leftSourceKey ?? '',
    leftFieldKey: fallback?.leftFieldKey ?? '',
    rightSourceKey: fallback?.rightSourceKey ?? '',
    rightFieldKey: fallback?.rightFieldKey ?? '',
    matchOperator: fallback?.matchOperator ?? 'eq',
    normalizer: fallback?.normalizer ?? 'NONE',
  });
}

function addDynamicRuleGroupBy() {
  form.value.dynamicRule.groupBy.push({ sourceKey: '', fieldKey: '' });
}

function addDynamicRuleAggregation() {
  form.value.dynamicRule.aggregations.push({
    key: `agg_${form.value.dynamicRule.aggregations.length + 1}`,
    sourceKey: '',
    fieldKey: '',
    function: 'count',
    label: '',
  });
}

function addDynamicRuleSort() {
  form.value.dynamicRule.sort.push({ sourceKey: '', fieldKey: '', aggregateKey: '', direction: 'desc' });
}

async function previewDynamicRule() {
  const dynamicRuleErrorMessage = validateDynamicRuleForm(form.value.dynamicRule);
  if (dynamicRuleErrorMessage) {
    ElMessage.warning(dynamicRuleErrorMessage);
    return false;
  }
  if (form.value.groupType !== 'DYNAMIC') {
    return true;
  }
  previewingDynamicRule.value = true;
  try {
    dynamicRulePreview.value = await api.previewDynamicRule({
      ruleConfig: buildRuleConfig(form.value.dynamicRule),
    });
    return true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '动态规则预览失败');
    return false;
  } finally {
    previewingDynamicRule.value = false;
  }
}

async function submitForm() {
  const errorMessage = validateLabelGroupForm(form.value);
  if (errorMessage) {
    ElMessage.warning(errorMessage);
    return;
  }
  if (form.value.groupType === 'DYNAMIC' && !(await previewDynamicRule())) {
    return;
  }
  if (currentValueType.value === 'MIXED') {
    ElMessage.warning('成员值类型不一致，请拆分到不同标签组');
    return;
  }
  saving.value = true;
  try {
    const payload = buildLabelGroupSaveRequest(form.value);
    if (editMode.value && form.value.id != null) {
      await api.updateLabelGroup(form.value.id, payload);
      ElMessage.success('标签组已更新');
    } else {
      await api.createLabelGroup(payload);
      ElMessage.success('标签组已创建');
    }
    dialogVisible.value = false;
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '标签组保存失败');
  } finally {
    saving.value = false;
  }
}

async function deleteGroup(group: LabelGroup) {
  try {
    await ElMessageBox.confirm(`确认删除标签组“${group.name}”？删除后业务页面将无法继续选择该标签组。`, '删除标签组', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  deletingId.value = group.id;
  try {
    await api.deleteLabelGroup(group.id);
    ElMessage.success('标签组已删除');
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '标签组删除失败');
  } finally {
    deletingId.value = null;
  }
}
</script>

<template>
  <div class="label-group-settings-page">
    <el-card class="panel-card label-group-toolbar-card">
      <div class="label-group-toolbar">
        <div class="label-group-toolbar-main">
          <el-select
            v-model="valueTypeFilter"
            clearable
            placeholder="全部值类型"
            style="width: 150px"
            @change="loadGroups"
            @clear="loadGroups"
          >
            <el-option
              v-for="item in valueTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-input
            v-model="keyword"
            clearable
            placeholder="搜索标签组名称"
            style="width: 260px"
            :prefix-icon="Search"
            @keyup.enter="loadGroups"
            @clear="loadGroups"
          />
          <el-button :icon="Refresh" :loading="loading" @click="loadGroups">刷新</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建标签组</el-button>
      </div>
    </el-card>

    <el-card class="panel-card">
      <el-table v-loading="loading" :data="groups" row-key="id" border>
        <el-table-column prop="name" label="标签组名称" min-width="150" />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.groupType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="值类型" width="100">
          <template #default="{ row }">{{ valueTypeLabel(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="成员" min-width="260">
          <template #default="{ row }">
            <div class="label-group-member-cell">
              <span>{{ buildMemberPreview(row) }}</span>
              <el-tag v-if="unavailableMemberCount(row.members)" size="small" type="warning">
                {{ unavailableMemberCount(row.members) }} 个暂无命中
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="子组" min-width="180">
          <template #default="{ row }">
            <span>{{ row.childGroups?.map((child: any) => child.name).join('、') || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成员数" width="90">
          <template #default="{ row }">{{ row.memberCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEditDialog(row)">编辑</el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :loading="deletingId === row.id"
              @click="deleteGroup(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editMode ? '编辑标签组' : '新建标签组'"
      width="960px"
      destroy-on-close
    >
      <el-form label-position="top" class="label-group-form">
        <el-form-item label="标签组名称" required>
          <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="例如：核心人员、重点关注人员" />
        </el-form-item>
        <el-form-item label="标签组类型" required>
          <el-segmented v-model="form.groupType" :options="groupTypeOptions" />
        </el-form-item>
        <el-form-item label="当前值类型">
          <el-tag :type="currentValueType === 'MIXED' ? 'danger' : 'primary'" effect="plain">
            {{ valueTypeLabel(currentValueType) }}
          </el-tag>
        </el-form-item>

        <template v-if="form.groupType === 'DYNAMIC'">
          <el-alert
            class="dynamic-rule-summary-alert"
            type="info"
            :closable="false"
            show-icon
            :title="dynamicRuleSummary"
          />
          <el-form-item label="结果来源" required>
            <el-select v-model="form.dynamicRule.outputSourceKey" filterable placeholder="选择数据源" style="width: 100%">
              <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
            </el-select>
          </el-form-item>
          <el-form-item label="输出字段" required>
            <el-select v-model="form.dynamicRule.outputFieldKey" filterable placeholder="选择输出字段" style="width: 100%">
              <el-option
                v-for="field in dynamicFieldOptions"
                :key="field.key"
                :label="`${field.name} / ${field.valueType}`"
                :value="field.key"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="过滤条件组">
            <DynamicRuleConditionGroup
              :group="form.dynamicRule.filterGroup"
              :sources="dynamicRuleSources"
            />
          </el-form-item>
          <el-form-item label="逻辑关联">
            <div class="dynamic-rule-block">
              <el-button size="small" @click="addDynamicRuleRelation">添加关联</el-button>
              <div v-for="(relation, index) in form.dynamicRule.relations" :key="`relation-${index}`" class="dynamic-rule-row">
                <el-select v-model="relation.leftSourceKey" filterable placeholder="左数据源" style="width: 160px">
                  <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
                </el-select>
                <el-select v-model="relation.leftFieldKey" filterable placeholder="左字段" style="width: 180px">
                  <el-option
                    v-for="field in sourceFieldOptions(relation.leftSourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${field.valueType}`"
                    :value="field.fieldKey"
                  />
                </el-select>
                <el-select v-model="relation.matchOperator" placeholder="关系" style="width: 110px">
                  <el-option label="等于" value="eq" />
                </el-select>
                <el-select v-model="relation.rightSourceKey" filterable placeholder="右数据源" style="width: 160px">
                  <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
                </el-select>
                <el-select v-model="relation.rightFieldKey" filterable placeholder="右字段" style="width: 180px">
                  <el-option
                    v-for="field in sourceFieldOptions(relation.rightSourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${field.valueType}`"
                    :value="field.fieldKey"
                  />
                </el-select>
                <el-select v-model="relation.normalizer" placeholder="规范化" style="width: 130px">
                  <el-option label="不处理" value="NONE" />
                  <el-option label="去空格" value="TRIM" />
                  <el-option label="小写" value="LOWER" />
                  <el-option label="去空格并小写" value="TRIM_LOWER" />
                </el-select>
                <el-tag effect="plain" size="small">{{ relationDisplay(relation) }}</el-tag>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="分组 / 聚合 / 排序">
            <div class="dynamic-rule-block">
              <div class="dynamic-rule-actions">
                <el-button size="small" @click="addDynamicRuleGroupBy">添加分组</el-button>
                <el-button size="small" @click="addDynamicRuleAggregation">添加聚合</el-button>
                <el-button size="small" @click="addDynamicRuleSort">添加排序</el-button>
                <el-switch v-model="form.dynamicRule.distinct" active-text="去重" inactive-text="不去重" />
              </div>
              <div v-for="(groupByItem, index) in form.dynamicRule.groupBy" :key="`group-${index}`" class="dynamic-rule-row">
                <el-select v-model="groupByItem.sourceKey" filterable placeholder="数据源" style="width: 160px">
                  <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
                </el-select>
                <el-select v-model="groupByItem.fieldKey" filterable placeholder="字段" style="width: 220px">
                  <el-option
                    v-for="field in sourceFieldOptions(groupByItem.sourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${field.valueType}`"
                    :value="field.fieldKey"
                  />
                </el-select>
              </div>
              <div v-for="(aggregation, index) in form.dynamicRule.aggregations" :key="`agg-${index}`" class="dynamic-rule-row">
                <el-input v-model="aggregation.key" placeholder="聚合标识" style="width: 140px" />
                <el-select v-model="aggregation.sourceKey" filterable placeholder="数据源" style="width: 160px">
                  <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
                </el-select>
                <el-select v-model="aggregation.fieldKey" filterable placeholder="字段" style="width: 220px">
                  <el-option
                    v-for="field in sourceFieldOptions(aggregation.sourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${field.valueType}`"
                    :value="field.fieldKey"
                  />
                </el-select>
                <el-select v-model="aggregation.function" placeholder="函数" style="width: 140px">
                  <el-option label="计数" value="count" />
                  <el-option label="去重计数" value="countDistinct" />
                  <el-option label="求和" value="sum" />
                  <el-option label="平均" value="avg" />
                  <el-option label="最小" value="min" />
                  <el-option label="最大" value="max" />
                </el-select>
                <el-input v-model="aggregation.label" placeholder="显示名" style="width: 160px" />
              </div>
              <div v-for="(sortItem, index) in form.dynamicRule.sort" :key="`sort-${index}`" class="dynamic-rule-row">
                <el-select v-model="sortItem.sourceKey" filterable placeholder="数据源" style="width: 160px">
                  <el-option v-for="source in dynamicRuleSources" :key="source.key" :label="source.name" :value="source.key" />
                </el-select>
                <el-select v-model="sortItem.fieldKey" filterable placeholder="字段" style="width: 220px">
                  <el-option
                    v-for="field in sourceFieldOptions(sortItem.sourceKey)"
                    :key="field.fieldKey"
                    :label="`${field.fieldName} / ${field.valueType}`"
                    :value="field.fieldKey"
                  />
                </el-select>
                <el-input v-model="sortItem.aggregateKey" placeholder="聚合标识" style="width: 140px" />
                <el-select v-model="sortItem.direction" style="width: 100px">
                  <el-option label="升序" value="asc" />
                  <el-option label="降序" value="desc" />
                </el-select>
              </div>
            </div>
          </el-form-item>
          <el-form-item v-if="form.dynamicRule.aggregations.length" label="聚合结果过滤">
            <DynamicRuleConditionGroup
              :group="form.dynamicRule.havingGroup"
              :sources="dynamicRuleSources"
              :aggregate-options="aggregationOptions"
              aggregate-mode
            />
          </el-form-item>
          <el-form-item label="结果成员上限">
            <el-input-number v-model="form.dynamicRule.limit" :min="1" :max="200" controls-position="right" />
          </el-form-item>
          <el-form-item label="输出成员">
            <div class="dynamic-rule-summary">
              <span>成员由动态规则计算生成，保存后按最近一次计算结果展开。</span>
              <el-button size="small" :loading="previewingDynamicRule" @click="previewDynamicRule">预览成员</el-button>
            </div>
            <div v-if="dynamicRulePreview" class="dynamic-rule-preview">
              <div class="dynamic-rule-preview__header">
                <el-tag size="small" :type="dynamicRulePreview.members.length ? 'success' : 'info'">
                  {{ dynamicRulePreview.message }}
                </el-tag>
              </div>
              <div v-if="dynamicRulePreview.members.length" class="label-member-selected-list">
                <el-tag
                  v-for="member in dynamicRulePreview.members"
                  :key="member.value"
                  type="primary"
                  :disable-transitions="true"
                >
                  {{ member.label || member.value }}
                </el-tag>
              </div>
            </div>
          </el-form-item>
        </template>

        <template v-if="form.groupType === 'STATIC'">
          <el-form-item label="候选来源">
            <el-select
              v-model="candidateDimensionKey"
              clearable
              filterable
              placeholder="可选，仅用于搜索候选值"
              style="width: 100%"
            >
              <el-option
                v-for="dimension in candidateDimensions"
                :key="dimension.key"
                :label="dimension.name"
                :value="dimension.key"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="成员值" required>
            <LabelGroupMemberPicker
              v-model="form.members"
              :dimension-key="candidateDimensionKey"
              :value-type="currentValueType && currentValueType !== 'MIXED' ? currentValueType : null"
            />
          </el-form-item>
        </template>

        <el-form-item v-if="form.groupType !== 'DYNAMIC'" label="子标签组" :required="form.groupType === 'COMPOSITE'">
          <el-select
            v-model="form.childGroupIds"
            multiple
            filterable
            placeholder="选择同值类型标签组"
            style="width: 100%"
          >
            <el-option
              v-for="group in childGroupOptions"
              :key="group.id"
              :label="`${group.name} / ${valueTypeLabel(group.valueType)} / ${group.groupType}`"
              :value="group.id"
            />
          </el-select>
          <div v-if="form.childGroupIds.length" class="child-group-preview">
            <div class="child-group-preview__header">
              <el-tag size="small" :type="childGroupExpandedPreview.overLimit ? 'danger' : 'success'">
                展开后 {{ childGroupExpandedPreview.total }} 个成员
              </el-tag>
              <span v-if="childGroupExpandedPreview.overLimit">超过 200 个，请拆分后保存</span>
              <span v-else>子组结果会并集去重。</span>
            </div>
            <div v-if="childGroupExpandedPreview.members.length" class="label-member-selected-list">
              <el-tag
                v-for="member in childGroupExpandedPreview.members"
                :key="member.value"
                type="primary"
                effect="plain"
                :disable-transitions="true"
              >
                {{ member.label || member.value }}
              </el-tag>
              <el-tag v-if="childGroupExpandedPreview.hiddenCount" type="info" effect="plain">
                还有 {{ childGroupExpandedPreview.hiddenCount }} 个
              </el-tag>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item v-if="editMode" label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.label-group-settings-page {
  display: grid;
  gap: 12px;
}

.label-group-toolbar-card > :deep(.el-card__body) {
  padding: 12px 14px !important;
}

.label-group-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.label-group-toolbar-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.label-group-member-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.label-group-member-cell span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dynamic-rule-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: rgba(0, 0, 0, 0.65);
  line-height: 1.6;
}

.dynamic-rule-block {
  display: grid;
  gap: 8px;
  width: 100%;
}

.dynamic-rule-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dynamic-rule-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.dynamic-rule-preview {
  display: grid;
  gap: 8px;
  margin-top: 8px;
  width: 100%;
}

.dynamic-rule-preview__header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.child-group-preview {
  display: grid;
  gap: 8px;
  margin-top: 8px;
  width: 100%;
}

.child-group-preview__header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  color: rgba(0, 0, 0, 0.65);
}

.label-member-selected-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.label-group-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.label-group-form .el-form-item:nth-child(n + 5) {
  grid-column: 1 / -1;
}

@media (max-width: 760px) {
  .label-group-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .label-group-form {
    grid-template-columns: 1fr;
  }
}
</style>
