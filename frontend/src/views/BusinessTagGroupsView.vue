<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Delete as DeleteIcon, Edit, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import { authState } from '../composables/auth-state';
import type {
  BusinessTagGroupResponse,
  BusinessTagGroupSaveRequest,
  BusinessTagGroupUpdateRequest,
  OptionItemResponse,
  SemanticTagGroupResponse,
  SemanticTagGroupCatalogResponse,
} from '../types/api';

type Visibility = BusinessTagGroupSaveRequest['visibility'];
type BusinessTagConditionOperator = 'IN' | 'EQ';

interface ApplicationScopeOption {
  key: string;
  label: string;
  moduleKey: string;
  moduleLabel: string;
  entityType: string;
  scenarioKey: string;
  supportedFieldKeys: string[];
}

interface BusinessTagConditionDraft {
  id: string;
  fieldKey: string;
  fieldLabel: string;
  operator: BusinessTagConditionOperator;
  values: string[];
}

const issueTableSupportedFieldKeys = [
  'severity_level',
  'urgency',
  'delay_cause',
  'customer_issue_closure_status',
  'defect_reason_standard',
  'person',
  'module',
  'owner_user',
  'project_version',
  'milestone',
  'testing_phase_dynamic',
];

const applicationScopeOptions: ApplicationScopeOption[] = [
  {
    key: 'quality_board_rd',
    label: '研发质量看板',
    moduleKey: 'quality-board',
    moduleLabel: '质量看板',
    entityType: 'issue',
    scenarioKey: 'quality_board',
    supportedFieldKeys: issueTableSupportedFieldKeys,
  },
  {
    key: 'review_data_management',
    label: '评审数据管理',
    moduleKey: 'review-data',
    moduleLabel: '评审数据',
    entityType: 'review_record',
    scenarioKey: 'review_data',
    supportedFieldKeys: [
      'module',
      'owner_user',
      'reviewer_user',
      'project_version',
      'review_type',
      'review_problem_status',
      'person',
    ],
  },
  {
    key: 'code_review_illegal_records',
    label: '代码走查非法数据',
    moduleKey: 'code-review',
    moduleLabel: '代码走查',
    entityType: 'merge_request',
    scenarioKey: 'code_review',
    supportedFieldKeys: ['person', 'module', 'owner_user', 'reviewer_user', 'project_version', 'mr_merge_state'],
  },
  {
    key: 'code_review_multi_board',
    label: '代码走查多元看板',
    moduleKey: 'code-review',
    moduleLabel: '代码走查',
    entityType: 'merge_request',
    scenarioKey: 'code_review_board',
    supportedFieldKeys: ['person', 'module', 'owner_user', 'reviewer_user', 'project_version', 'mr_merge_state'],
  },
  ...[
    ['question_metrics_home', '系统测试缺陷汇总'],
    ['question_metrics_multi_board', '议题多元看板'],
    ['question_metrics_delay_analysis', '申请延期缺陷分析'],
    ['question_metrics_illegal_records', '系统测试非法数据'],
    ['question_metrics_defect_cause', '缺陷原因分析'],
    ['question_metrics_phase_statistics', '议题阶段统计'],
    ['question_metrics_issue_search', '议题查询'],
  ].map(([key, label]) => ({
    key,
    label,
    moduleKey: 'question-metrics',
    moduleLabel: '系统测试',
    entityType: 'issue',
    scenarioKey: key,
    supportedFieldKeys: issueTableSupportedFieldKeys,
  })),
  ...[
    ['customer_issues_home', '缺陷汇总'],
    ['customer_issues_illegal_records', '缺陷非法数据'],
    ['customer_issues_defect_cause', '缺陷原因分析'],
    ['customer_issues_cc_product_issues', 'CC_PRODUCT议题'],
    ['customer_issues_delay_issues', '延期问题'],
    ['customer_issues_response_efficiency', '缺陷响应效率'],
    ['customer_issues_issue_by_function', '按功能展示缺陷数量'],
  ].map(([key, label]) => ({
    key,
    label,
    moduleKey: 'customer-issues',
    moduleLabel: '客户问题',
    entityType: 'issue',
    scenarioKey: key,
    supportedFieldKeys: issueTableSupportedFieldKeys,
  })),
];

const tagGroups = ref<BusinessTagGroupResponse[]>([]);
const semanticCatalog = ref<SemanticTagGroupCatalogResponse | null>(null);
let conditionSeed = 0;
const conditionDrafts = ref<BusinessTagConditionDraft[]>([createConditionDraft()]);
const loading = ref(false);
const catalogLoading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);
const editingId = ref<number | null>(null);
const errorMessage = ref('');

const form = reactive<BusinessTagGroupSaveRequest>({
  tagGroupName: '',
  ownerUserId: 'admin',
  visibility: 'TEAM',
  entityType: 'issue',
  scenarioKey: 'all_tables',
  scopeKey: 'all_fields',
  dslJson: '',
  tagSchemaHash: 'schema-v1',
  sourceDataWatermarkAtSave: '',
});

const editingTitle = computed(() => (editingId.value == null ? '新建业务标签组' : '编辑业务标签组'));
const groupCount = computed(() => tagGroups.value.length);
const publicCount = computed(() => tagGroups.value.filter((item) => item.visibility === 'PUBLIC').length);
const teamCount = computed(() => tagGroups.value.filter((item) => item.visibility === 'TEAM').length);
const tagTypeOptions = computed(() =>
  businessSemanticGroups.value.map((group) => ({
    label: businessSemanticLabel(group),
    value: group.groupKey,
  })) ?? [],
);
const businessSemanticGroups = computed(() =>
  semanticCatalog.value?.groups.filter((group) => !hiddenBusinessFieldKeys.has(group.groupKey)) ?? [],
);
const generatedDslJson = computed(() => buildDslJson(false) ?? '');
const currentOwnerUserId = computed(() =>
  authState.currentUser.authenticated ? authState.currentUser.username : 'admin',
);
const semanticCatalogStatus = computed(() => {
  if (catalogLoading.value) {
    return '语义目录加载中';
  }
  return semanticCatalog.value ? '语义目录已加载，保存时自动记录目录版本' : '语义目录未加载';
});
const compatibilityPreview = computed(() => pageCompatibilityFor(conditionDrafts.value));
const compatibilityPreviewByModule = computed(() => groupCompatibilityByModule(compatibilityPreview.value));

onMounted(() => {
  void loadTagGroups();
  void loadSemanticCatalog();
});

async function loadTagGroups() {
  loading.value = true;
  errorMessage.value = '';
  try {
    form.ownerUserId = currentOwnerUserId.value;
    tagGroups.value = await api.listBusinessTagGroups({ ownerUserId: currentOwnerUserId.value });
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '加载业务标签组失败';
  } finally {
    loading.value = false;
  }
}

async function loadSemanticCatalog() {
  catalogLoading.value = true;
  try {
    const staticCatalog = await api.getStaticSemanticTagGroups('issue');
    const dynamicGroups = await loadDynamicBusinessSemanticGroups();
    semanticCatalog.value = mergeSemanticCatalog(staticCatalog, dynamicGroups);
    form.tagSchemaHash = semanticCatalog.value.schemaHash;
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '加载语义标签类型目录失败');
  } finally {
    catalogLoading.value = false;
  }
}

async function loadDynamicBusinessSemanticGroups() {
  const [systemTestOptions, reviewOptions, codeReviewOptions] = await Promise.allSettled([
    api.getSystemTestIssueSearchFilterOptions(),
    api.getReviewDataFilterOptions(),
    api.getCodeReviewIllegalRecordFilterOptions(),
  ]);
  const systemTest = systemTestOptions.status === 'fulfilled' ? systemTestOptions.value : null;
  const review = reviewOptions.status === 'fulfilled' ? reviewOptions.value : null;
  const codeReview = codeReviewOptions.status === 'fulfilled' ? codeReviewOptions.value : null;

  return [
    dynamicGroup('person', '人员', [
      ...(systemTest?.assigneeNames ?? []),
      ...(review?.reviewOwners ?? []),
      ...(review?.reviewExperts ?? []),
    ], 80),
    dynamicGroup('module', '模块', [
      ...(systemTest?.moduleNames ?? []),
      ...(review?.moduleNames ?? []),
      ...(codeReview?.moduleNames ?? []),
    ], 90),
    dynamicGroup('owner_user', '负责人', [
      ...(systemTest?.assigneeNames ?? []),
      ...(review?.reviewOwners ?? []),
    ], 100),
    dynamicGroup('reviewer_user', '评审专家', review?.reviewExperts ?? [], 110),
    dynamicGroup('project_version', '项目/版本', [
      ...(systemTest?.projectNames ?? []),
      ...(review?.projectNames ?? []),
      ...(codeReview?.projectNames ?? []),
    ], 120),
    dynamicGroup('milestone', '里程碑', systemTest?.milestoneTitles ?? [], 130),
    dynamicGroup('testing_phase_dynamic', '测试阶段', systemTest?.testingPhases ?? [], 140),
    dynamicGroup('review_type', '评审类型', review?.reviewTypes ?? [], 150),
    dynamicGroup('review_problem_status', '问题状态', review?.problemStatuses ?? [], 160),
  ].filter((group) => group.values.length > 0);
}

function mergeSemanticCatalog(
  catalog: SemanticTagGroupCatalogResponse,
  dynamicGroups: SemanticTagGroupResponse[],
): SemanticTagGroupCatalogResponse {
  const merged = new Map<string, SemanticTagGroupResponse>();
  for (const group of catalog.groups) {
    merged.set(group.groupKey, group);
  }
  for (const group of dynamicGroups) {
    const current = merged.get(group.groupKey);
    merged.set(group.groupKey, current ? mergeSemanticGroupValues(current, group) : group);
  }
  return {
    ...catalog,
    schemaHash: catalog.schemaHash,
    groups: Array.from(merged.values()).sort((left, right) => left.sortOrder - right.sortOrder),
  };
}

function mergeSemanticGroupValues(
  left: SemanticTagGroupResponse,
  right: SemanticTagGroupResponse,
): SemanticTagGroupResponse {
  const values = new Map<string, SemanticTagGroupResponse['values'][number]>();
  for (const value of [...left.values, ...right.values]) {
    const key = value.canonicalValue || value.valueKey || value.label;
    values.set(key, value);
  }
  return {
    ...left,
    label: businessSemanticLabel(left),
    sourceMode: left.sourceMode === right.sourceMode ? left.sourceMode : 'HYBRID',
    values: Array.from(values.values()).sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN')),
  };
}

function dynamicGroup(
  groupKey: string,
  label: string,
  options: OptionItemResponse[],
  sortOrder: number,
): SemanticTagGroupResponse {
  const values = new Map<string, OptionItemResponse>();
  for (const option of options) {
    const value = option.value?.trim();
    const optionLabel = option.label?.trim() || value;
    if (value && optionLabel) {
      values.set(value, { label: optionLabel, value });
    }
  }
  return {
    domain: 'business',
    groupKey,
    label,
    sourceMode: 'DYNAMIC',
    rulePolicyKey: 'data_filter_options',
    selectionMode: 'MULTIPLE',
    matchStrategyName: 'EXACT',
    enabled: true,
    sortOrder,
    values: Array.from(values.values())
      .sort((left, right) => left.label.localeCompare(right.label, 'zh-Hans-CN'))
      .map((option, index) => ({
        valueKey: option.value,
        label: option.label,
        valueType: 'STRING',
        canonicalValue: option.value,
        enabled: true,
        sortOrder: (index + 1) * 10,
      })),
  };
}

function openCreateDrawer() {
  editingId.value = null;
  conditionDrafts.value = [createConditionDraft()];
  const applicationScope = applicationScopeOptions[0];
  Object.assign(form, {
    tagGroupName: '',
    ownerUserId: currentOwnerUserId.value,
    visibility: 'TEAM' as Visibility,
    entityType: applicationScope.entityType,
    scenarioKey: applicationScope.scenarioKey,
    scopeKey: 'all_fields',
    dslJson: '',
    tagSchemaHash: semanticCatalog.value?.schemaHash ?? 'schema-v1',
    sourceDataWatermarkAtSave: '',
  });
  drawerVisible.value = true;
}

function openEditDrawer(tagGroup: BusinessTagGroupResponse) {
  editingId.value = tagGroup.id;
  Object.assign(form, {
    tagGroupName: tagGroup.tagGroupName,
    ownerUserId: tagGroup.ownerUserId,
    visibility: tagGroup.visibility,
    entityType: tagGroup.entityType,
    scenarioKey: tagGroup.scenarioKey,
    scopeKey: tagGroup.scopeKey,
    dslJson: tagGroup.dslJson,
    tagSchemaHash: tagGroup.tagSchemaHash,
    sourceDataWatermarkAtSave: tagGroup.sourceDataWatermarkAtSave ?? '',
  });
  conditionDrafts.value = parseDslToConditionDrafts(tagGroup.dslJson);
  drawerVisible.value = true;
}

async function saveTagGroup() {
  const payload = normalizeForm();
  if (!payload) {
    return;
  }
  saving.value = true;
  try {
    if (editingId.value == null) {
      await api.createBusinessTagGroup(payload);
      ElMessage.success('业务标签组已创建');
    } else {
      const updatePayload: BusinessTagGroupUpdateRequest = {
        tagGroupName: payload.tagGroupName,
        visibility: payload.visibility,
        entityType: payload.entityType,
        scenarioKey: payload.scenarioKey,
        scopeKey: payload.scopeKey,
        dslJson: payload.dslJson,
        tagSchemaHash: payload.tagSchemaHash,
        sourceDataWatermarkAtSave: payload.sourceDataWatermarkAtSave,
      };
      await api.updateBusinessTagGroup(editingId.value, updatePayload);
      ElMessage.success('业务标签组已更新');
    }
    drawerVisible.value = false;
    await loadTagGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存业务标签组失败');
  } finally {
    saving.value = false;
  }
}

async function deleteTagGroup(tagGroup: BusinessTagGroupResponse) {
  try {
    await ElMessageBox.confirm(`确认删除业务标签组“${tagGroup.tagGroupName}”吗？`, '删除业务标签组', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    await api.deleteBusinessTagGroup(tagGroup.id);
    ElMessage.success('业务标签组已删除');
    await loadTagGroups();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '删除业务标签组失败');
    }
  }
}

function normalizeForm(): BusinessTagGroupSaveRequest | null {
  const tagGroupName = form.tagGroupName.trim();
  const ownerUserId = currentOwnerUserId.value;
  const compatibleScope = resolveCompatibleScope(conditionDrafts.value);
  const entityType = compatibleScope.entityType;
  const scenarioKey = compatibleScope.scenarioKey;
  const scopeKey = resolveFieldScope(conditionDrafts.value);
  const dslJson = buildDslJson(true);
  const tagSchemaHash = form.tagSchemaHash.trim();
  if (!tagGroupName || !ownerUserId || !entityType || !scenarioKey || !scopeKey || !tagSchemaHash) {
    ElMessage.warning('请补齐标签组名称、负责人、适用范围和语义目录版本');
    return null;
  }
  if (!dslJson) {
    return null;
  }
  form.dslJson = dslJson;
  return {
    tagGroupName,
    ownerUserId,
    visibility: form.visibility,
    entityType,
    scenarioKey,
    scopeKey,
    dslJson,
    tagSchemaHash,
    sourceDataWatermarkAtSave: form.sourceDataWatermarkAtSave?.trim() || null,
  };
}

function createConditionDraft(): BusinessTagConditionDraft {
  conditionSeed += 1;
  return {
    id: `business-tag-condition-${conditionSeed}`,
    fieldKey: '',
    fieldLabel: '',
    operator: 'IN',
    values: [],
  };
}

function addCondition() {
  conditionDrafts.value = [...conditionDrafts.value, createConditionDraft()];
}

function removeCondition(id: string) {
  if (conditionDrafts.value.length === 1) {
    conditionDrafts.value = [createConditionDraft()];
    return;
  }
  conditionDrafts.value = conditionDrafts.value.filter((condition) => condition.id !== id);
}

function handleFieldKeyChange(condition: BusinessTagConditionDraft) {
  const group = findSemanticGroup(condition.fieldKey);
  condition.fieldLabel = group ? businessSemanticLabel(group) : condition.fieldKey;
  condition.values = [];
}

function valueOptions(fieldKey: string) {
  return (
    findSemanticGroup(fieldKey)?.values.map((value) => ({
      label: value.label,
      value: value.canonicalValue || value.valueKey,
    })) ?? []
  );
}

function conditionValueText(fieldKey: string, values: string[]) {
  const labels = new Map(valueOptions(fieldKey).map((option) => [option.value, option.label]));
  return values.map((value) => labels.get(value) ?? value).join('、');
}

function findSemanticGroup(fieldKey: string) {
  return semanticCatalog.value?.groups.find((group) => group.groupKey === fieldKey);
}

function buildDslJson(showWarning: boolean) {
  const conditions = [];
  for (const draft of conditionDrafts.value) {
    const fieldKey = draft.fieldKey.trim();
    const values = draft.values.map((value) => value.trim()).filter(Boolean);
    if (!fieldKey && values.length === 0) {
      continue;
    }
    if (!fieldKey || values.length === 0) {
      if (showWarning) {
        ElMessage.warning('请为每条条件选择标签类型和至少一个标签值');
      }
      return null;
    }
    const group = findSemanticGroup(fieldKey);
    if (!group) {
      if (showWarning) {
        ElMessage.warning('标签类型必须来自语义标签类型目录');
      }
      return null;
    }
    const allowedValues = new Set(group.values.map((value) => value.canonicalValue || value.valueKey));
    if (values.some((value) => !allowedValues.has(value))) {
      if (showWarning) {
        ElMessage.warning('标签值必须来自当前标签类型下的已归类选项');
      }
      return null;
    }
    conditions.push({
      fieldKey,
      fieldLabel: draft.fieldLabel.trim() || businessSemanticLabel(group),
      operator: draft.operator,
      values: draft.operator === 'EQ' ? values.slice(0, 1) : values,
    });
  }
  if (!conditions.length) {
    if (showWarning) {
      ElMessage.warning('请至少添加一条标签组条件');
    }
    return null;
  }
  return JSON.stringify({ logic: 'AND', conditions });
}

function parseDslToConditionDrafts(dslJson: string) {
  try {
    const parsed = JSON.parse(dslJson) as {
      conditions?: Array<{
        fieldKey?: string;
        fieldFamily?: string;
        fieldLabel?: string;
        operator?: string;
        value?: string;
        values?: string[];
      }>;
    };
    const parsedConditions = parsed.conditions ?? [];
    const drafts = parsedConditions
      .map((condition) => {
        const fieldKey = condition.fieldKey || condition.fieldFamily || '';
        const group = findSemanticGroup(fieldKey);
        const values = Array.isArray(condition.values)
          ? condition.values
          : condition.value
            ? [condition.value]
            : [];
        return {
          ...createConditionDraft(),
          fieldKey,
          fieldLabel: condition.fieldLabel || (group ? businessSemanticLabel(group) : fieldKey),
          operator: condition.operator === 'EQ' ? 'EQ' : 'IN',
          values,
        } satisfies BusinessTagConditionDraft;
      })
      .filter((condition) => condition.fieldKey || condition.values.length);
    return drafts.length ? drafts : [createConditionDraft()];
  } catch {
    return [createConditionDraft()];
  }
}

function visibilityText(visibility: string) {
  return {
    PRIVATE: '仅自己',
    TEAM: '团队',
    PUBLIC: '公开',
  }[visibility] ?? visibility;
}

function operatorText(operator: BusinessTagConditionOperator) {
  return operator === 'EQ' ? '等于' : '包含任一';
}

function applicationScopeText(entityType: string, scenarioKey: string) {
  if (entityType === 'issue' && scenarioKey === 'all_tables') {
    return '议题类表格';
  }
  return applicationScopeOptions.find((item) => item.entityType === entityType && item.scenarioKey === scenarioKey)?.label
    ?? '系统自动判断';
}

function fieldScopeText(scopeKey: string) {
  return {
    all_fields: '复合条件',
    people_fields: '人员',
    project_fields: '项目/版本',
    module_fields: '模块',
    milestone_fields: '里程碑/轮次',
  }[scopeKey] ?? '系统自动判断';
}

function businessSemanticLabel(group: Pick<SemanticTagGroupResponse, 'groupKey' | 'label'>) {
  return {
    person: '人员',
    severity_level: '缺陷等级',
    urgency: '紧急程度',
    customer_issue_closure_status: '处理状态',
    defect_reason_standard: '缺陷原因',
  }[group.groupKey] ?? group.label;
}

function selectedFieldKeys(drafts: BusinessTagConditionDraft[]) {
  return Array.from(new Set(drafts.map((draft) => draft.fieldKey.trim()).filter(Boolean)));
}

function pageCompatibilityFor(drafts: BusinessTagConditionDraft[]) {
  const fieldKeys = selectedFieldKeys(drafts);
  return applicationScopeOptions.map((page) => {
    const missing = fieldKeys.filter((fieldKey) => !page.supportedFieldKeys.includes(fieldKey));
    return {
      ...page,
      compatible: fieldKeys.length > 0 && missing.length === 0,
      reason: fieldKeys.length === 0
        ? '选择标签条件后自动判断'
        : missing.length
          ? `缺少：${missing.map(fieldKeyText).join('、')}`
          : '可应用',
    };
  });
}

function groupCompatibilityByModule(pages: ReturnType<typeof pageCompatibilityFor>) {
  const modules = new Map<string, {
    key: string;
    label: string;
    totalCount: number;
    compatibleCount: number;
    pages: ReturnType<typeof pageCompatibilityFor>;
  }>();
  for (const page of pages) {
    const current = modules.get(page.moduleKey) ?? {
      key: page.moduleKey,
      label: page.moduleLabel,
      totalCount: 0,
      compatibleCount: 0,
      pages: [],
    };
    current.totalCount += 1;
    current.compatibleCount += page.compatible ? 1 : 0;
    current.pages.push(page);
    modules.set(page.moduleKey, current);
  }
  return Array.from(modules.values());
}

function resolveCompatibleScope(drafts: BusinessTagConditionDraft[]) {
  return pageCompatibilityFor(drafts).find((page) => page.compatible) ?? applicationScopeOptions[0];
}

function resolveFieldScope(drafts: BusinessTagConditionDraft[]) {
  const keys = selectedFieldKeys(drafts);
  if (keys.length === 0 || keys.length > 1) {
    return 'all_fields';
  }
  const key = keys[0];
  if (['owner_user', 'reviewer_user'].includes(key)) {
    return 'people_fields';
  }
  if (key === 'person') {
    return 'people_fields';
  }
  if (key === 'module') {
    return 'module_fields';
  }
  if (key === 'project_version') {
    return 'project_fields';
  }
  if (['milestone', 'testing_phase_dynamic'].includes(key)) {
    return 'milestone_fields';
  }
  return 'all_fields';
}

function fieldKeyText(fieldKey: string) {
  const group = findSemanticGroup(fieldKey);
  return group ? businessSemanticLabel(group) : fieldKey;
}

const hiddenBusinessFieldKeys = new Set([
  'system_test_exclusion_type',
  'illegal_type',
  'ratio_empty_value_policy',
]);
</script>

<template>
  <section class="business-tag-page">
    <el-card class="panel-card business-tag-summary-card">
      <div class="business-tag-summary">
        <div>
          <h2 class="content-title">业务标签组</h2>
          <p class="business-tag-subtitle">
            在这里创建和维护可跨页面应用的业务筛选标签组。业务表格页只选择已保存标签组，不提供新建和编辑入口。
          </p>
        </div>
        <el-space wrap>
          <el-button :icon="Refresh" :loading="loading" @click="loadTagGroups">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="openCreateDrawer">新建业务标签组</el-button>
        </el-space>
      </div>

      <el-alert
        v-if="errorMessage"
        class="business-tag-alert"
        type="error"
        :title="errorMessage"
        show-icon
        :closable="false"
      />

      <div class="business-tag-metrics">
        <div class="business-tag-metric">
          <span>标签组</span>
          <strong>{{ groupCount }}</strong>
        </div>
        <div class="business-tag-metric">
          <span>团队可见</span>
          <strong>{{ teamCount }}</strong>
        </div>
        <div class="business-tag-metric">
          <span>公开可见</span>
          <strong>{{ publicCount }}</strong>
        </div>
      </div>
    </el-card>

    <el-card class="panel-card">
      <el-table v-loading="loading" :data="tagGroups" row-key="id" border stripe>
        <el-table-column prop="tagGroupName" label="标签组名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="可见范围" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ visibilityText(row.visibility) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="可应用页面" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ applicationScopeText(row.entityType, row.scenarioKey) }}
          </template>
        </el-table-column>
        <el-table-column label="条件类型" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ fieldScopeText(row.scopeKey) }}
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" :icon="Edit" @click="openEditDrawer(row)">编辑</el-button>
            <el-button size="small" type="danger" plain :icon="DeleteIcon" @click="deleteTagGroup(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有业务标签组" />
        </template>
      </el-table>
    </el-card>

    <el-drawer v-model="drawerVisible" :title="editingTitle" size="520px" destroy-on-close>
      <el-form label-position="top" class="business-tag-form">
        <el-form-item label="标签组名称">
          <el-input v-model="form.tagGroupName" placeholder="例如：领导、核心模块、重点关注" />
        </el-form-item>
        <el-form-item label="可见范围">
          <el-radio-group v-model="form.visibility">
            <el-radio-button value="PRIVATE">仅自己</el-radio-button>
            <el-radio-button value="TEAM">团队</el-radio-button>
            <el-radio-button value="PUBLIC">公开</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="semanticCatalogStatus"
        />

        <el-form-item label="标签条件">
          <div class="business-tag-condition-editor">
            <div class="business-tag-condition-toolbar">
              <span class="form-help-text">
                先选择标签类型，再从该类型下选择已归类标签值。未被规则归类的值不会出现在对应类型下。
              </span>
              <el-button :icon="Refresh" :loading="catalogLoading" @click="loadSemanticCatalog">
                刷新类型和值
              </el-button>
            </div>

            <div
              v-for="condition in conditionDrafts"
              :key="condition.id"
              class="business-tag-condition-row"
            >
              <el-select
                v-model="condition.fieldKey"
                class="business-tag-field-select"
                filterable
                :loading="catalogLoading"
                placeholder="标签类型"
                @change="handleFieldKeyChange(condition)"
              >
                <el-option
                  v-for="option in tagTypeOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-select
                v-model="condition.operator"
                class="business-tag-operator-select"
                placeholder="关系"
              >
                <el-option label="包含任一" value="IN" />
                <el-option label="等于" value="EQ" />
              </el-select>
              <el-select
                v-model="condition.values"
                class="business-tag-value-select"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                :max-collapse-tags="2"
                :disabled="!condition.fieldKey"
                placeholder="标签值"
              >
                <el-option
                  v-for="option in valueOptions(condition.fieldKey)"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-button :icon="DeleteIcon" plain @click="removeCondition(condition.id)">移除</el-button>
            </div>

            <el-button :icon="Plus" plain @click="addCondition">添加条件</el-button>
          </div>
        </el-form-item>

        <el-form-item label="可应用页面">
          <div class="business-tag-compatibility-list">
            <el-collapse>
              <el-collapse-item
                v-for="module in compatibilityPreviewByModule"
                :key="module.key"
                :name="module.key"
              >
                <template #title>
                  <div class="business-tag-compatibility-module-title">
                    <span>{{ module.label }}</span>
                    <el-tag size="small" :type="module.compatibleCount > 0 ? 'success' : 'info'" effect="plain">
                      {{ module.compatibleCount }}/{{ module.totalCount }} 可用
                    </el-tag>
                  </div>
                </template>
                <div
                  v-for="page in module.pages"
                  :key="page.key"
                  class="business-tag-compatibility-item"
                >
                  <span>{{ page.label }}</span>
                  <el-tag size="small" :type="page.compatible ? 'success' : 'info'" effect="plain">
                    {{ page.reason }}
                  </el-tag>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-form-item>

        <el-form-item label="条件预览">
          <div class="business-tag-condition-preview">
            <el-tag
              v-for="condition in conditionDrafts.filter((item) => item.fieldKey && item.values.length)"
              :key="condition.id"
              effect="plain"
            >
              {{ condition.fieldLabel || condition.fieldKey }} {{ operatorText(condition.operator) }}
              {{ conditionValueText(condition.fieldKey, condition.values) }}
            </el-tag>
            <span v-if="!conditionDrafts.some((item) => item.fieldKey && item.values.length)" class="form-help-text">
              暂无有效条件
            </span>
          </div>
        </el-form-item>

        <input type="hidden" :value="generatedDslJson" aria-hidden="true">
      </el-form>
      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveTagGroup">保存</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.business-tag-page {
  display: grid;
  gap: 12px;
}

.business-tag-summary-card {
  min-height: 150px;
}

.business-tag-summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.business-tag-subtitle {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.5;
}

.business-tag-alert {
  margin-top: 12px;
}

.business-tag-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.business-tag-metric {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: #f8fafc;
}

.business-tag-metric span {
  color: #64748b;
  font-size: 12px;
}

.business-tag-metric strong {
  color: #0f172a;
  font-size: 18px;
  font-weight: 700;
}

.business-tag-form {
  padding-right: 8px;
}

.business-tag-compatibility-list {
  display: grid;
  gap: 8px;
  width: 100%;
}

.business-tag-compatibility-list :deep(.el-collapse) {
  width: 100%;
  border-top: 0;
}

.business-tag-compatibility-module-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding-right: 8px;
}

.business-tag-compatibility-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  background: #f8fafc;
}

@media (max-width: 760px) {
  .business-tag-summary {
    display: grid;
  }

  .business-tag-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
