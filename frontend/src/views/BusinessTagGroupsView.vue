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
  SemanticTagGroupCatalogResponse,
} from '../types/api';

type Visibility = BusinessTagGroupSaveRequest['visibility'];
type BusinessTagConditionOperator = 'IN' | 'EQ';

interface ApplicationScopeOption {
  key: string;
  label: string;
  entityType: string;
  scenarioKey: string;
}

interface BusinessTagConditionDraft {
  id: string;
  fieldKey: string;
  fieldLabel: string;
  operator: BusinessTagConditionOperator;
  values: string[];
}

const applicationScopeOptions: ApplicationScopeOption[] = [
  { key: 'all_issue_tables', label: '全部议题表格', entityType: 'issue', scenarioKey: 'all_tables' },
  { key: 'system_test_tables', label: '系统测试表格', entityType: 'issue', scenarioKey: 'system_test' },
  { key: 'customer_issue_tables', label: '客户问题表格', entityType: 'issue', scenarioKey: 'customer_issue' },
  { key: 'review_record_tables', label: '评审数据表格', entityType: 'review_record', scenarioKey: 'review_data' },
  { key: 'code_review_tables', label: '代码走查表格', entityType: 'merge_request', scenarioKey: 'code_review' },
];

const fieldScopeOptions = [
  { label: '全部可筛选字段', value: 'all_fields' },
  { label: '人员字段', value: 'people_fields' },
  { label: '项目字段', value: 'project_fields' },
  { label: '模块字段', value: 'module_fields' },
  { label: '里程碑/轮次字段', value: 'milestone_fields' },
];

const tagGroups = ref<BusinessTagGroupResponse[]>([]);
const semanticCatalog = ref<SemanticTagGroupCatalogResponse | null>(null);
let conditionSeed = 0;
const conditionDrafts = ref<BusinessTagConditionDraft[]>([createConditionDraft()]);
const selectedApplicationScopeKey = ref('all_issue_tables');
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
  semanticCatalog.value?.groups.map((group) => ({
    label: group.label,
    value: group.groupKey,
  })) ?? [],
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
    semanticCatalog.value = await api.getStaticSemanticTagGroups(form.entityType || 'issue');
    form.tagSchemaHash = semanticCatalog.value.schemaHash;
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '加载语义标签类型目录失败');
  } finally {
    catalogLoading.value = false;
  }
}

async function handleEntityTypeChange() {
  conditionDrafts.value = [createConditionDraft()];
  await loadSemanticCatalog();
}

async function handleApplicationScopeChange(scopeKey: string) {
  const option = applicationScopeOptions.find((item) => item.key === scopeKey) ?? applicationScopeOptions[0];
  form.entityType = option.entityType;
  form.scenarioKey = option.scenarioKey;
  conditionDrafts.value = [createConditionDraft()];
  await loadSemanticCatalog();
}

function openCreateDrawer() {
  editingId.value = null;
  conditionDrafts.value = [createConditionDraft()];
  selectedApplicationScopeKey.value = 'all_issue_tables';
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
  selectedApplicationScopeKey.value = resolveApplicationScopeKey(tagGroup.entityType, tagGroup.scenarioKey);
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
  const entityType = form.entityType.trim();
  const scenarioKey = form.scenarioKey.trim();
  const scopeKey = form.scopeKey.trim();
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
  condition.fieldLabel = group?.label ?? condition.fieldKey;
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
      fieldLabel: draft.fieldLabel.trim() || group.label,
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
          fieldLabel: condition.fieldLabel || group?.label || fieldKey,
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

function resolveApplicationScopeKey(entityType: string, scenarioKey: string) {
  return applicationScopeOptions.find((item) => item.entityType === entityType && item.scenarioKey === scenarioKey)?.key
    ?? applicationScopeOptions[0].key;
}

function applicationScopeText(entityType: string, scenarioKey: string) {
  return applicationScopeOptions.find((item) => item.entityType === entityType && item.scenarioKey === scenarioKey)?.label
    ?? '未识别适用页面';
}

function fieldScopeText(scopeKey: string) {
  return fieldScopeOptions.find((item) => item.value === scopeKey)?.label ?? '未识别字段范围';
}
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
        <el-table-column label="适用页面" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ applicationScopeText(row.entityType, row.scenarioKey) }}
          </template>
        </el-table-column>
        <el-table-column label="字段范围" min-width="140" show-overflow-tooltip>
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
        <el-form-item label="适用页面">
          <el-select
            v-model="selectedApplicationScopeKey"
            style="width: 100%"
            placeholder="选择适用页面"
            @change="handleApplicationScopeChange"
          >
            <el-option
              v-for="option in applicationScopeOptions"
              :key="option.key"
              :label="option.label"
              :value="option.key"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="字段范围">
          <el-select v-model="form.scopeKey" style="width: 100%" placeholder="选择字段范围">
            <el-option
              v-for="option in fieldScopeOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
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

@media (max-width: 760px) {
  .business-tag-summary {
    display: grid;
  }

  .business-tag-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
