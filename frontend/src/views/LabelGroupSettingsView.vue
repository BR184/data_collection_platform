<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import DynamicLabelGroupRuleBuilder from '../components/label-groups/DynamicLabelGroupRuleBuilder.vue';
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
} from './label-groups/label-group-settings';

const loading = ref(false);
const saving = ref(false);
const previewingDynamicRule = ref(false);
const deletingId = ref<number | null>(null);
const togglingId = ref<number | null>(null);
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

const applicableScopeOptions = [
  { label: '同类型字段可用', value: 'SAME_TYPE' },
  { label: '仅同来源字段可用', value: 'SAME_FIELD' },
];

const candidateDimensions = computed(() => dimensions.value.filter((item) => item.staticSupported));
const sourceFieldOptions = computed(() =>
  candidateDimensions.value.map((dimension) => ({ label: dimension.name, value: dimension.key })),
);
const dynamicRuleSourceMap = computed(() => new Map(dynamicRuleSources.value.map((item) => [item.key, item])));
const currentDynamicSource = computed(() => dynamicRuleSourceMap.value.get(form.value.dynamicRule.outputSourceKey));
const currentDynamicField = computed(
  () => currentDynamicSource.value?.fields.find((field) => field.key === form.value.dynamicRule.outputFieldKey),
);
const dynamicRuleSummary = computed(() => buildDynamicRuleSummary(
  form.value.dynamicRule,
  currentDynamicSource.value?.name ?? '',
  currentDynamicField.value?.name ?? '',
));
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
  await Promise.all([
    loadDimensions(),
    loadGroups(),
    loadDynamicRuleSources(),
    loadDynamicRuleRelations(),
  ]);
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
  if (group.systemDefault) {
    ElMessage.warning('系统默认标签组不能删除，可编辑成员、备注或停用');
    return;
  }
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

async function setGroupEnabled(group: LabelGroup, enabled: boolean) {
  if (group.enabled === enabled) {
    return;
  }
  const previous = group.enabled;
  group.enabled = enabled;
  togglingId.value = group.id;
  try {
    await api.updateLabelGroup(group.id, {
      name: group.name,
      groupType: group.groupType,
      applicableScope: group.applicableScope === 'SAME_FIELD' ? 'SAME_FIELD' : 'SAME_TYPE',
      sourceFieldKey: group.applicableScope === 'SAME_FIELD' ? group.sourceFieldKey ?? null : null,
      description: group.description ?? null,
      enabled,
      members: group.members,
      childGroupIds: (group.childGroups ?? []).map((child) => child.id),
      dynamicRule: group.dynamicRule ? { ruleConfig: group.dynamicRule.ruleConfig } : null,
    });
    ElMessage.success(enabled ? '标签组已启用' : '标签组已停用');
    await loadGroups();
  } catch (error) {
    group.enabled = previous;
    ElMessage.error(error instanceof Error ? error.message : '标签组状态更新失败');
  } finally {
    togglingId.value = null;
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
            fit-input-width
            placeholder="全部值类型"
            popper-class="platform-select-dropdown"
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
          <el-tooltip content="刷新" placement="top">
            <el-button
              class="label-group-icon-button"
              :icon="Refresh"
              :loading="loading"
              aria-label="刷新"
              @click="loadGroups"
            />
          </el-tooltip>
        </div>
        <el-tooltip content="新建标签组" placement="top">
          <el-button
            class="label-group-icon-button"
            type="primary"
            :icon="Plus"
            aria-label="新建标签组"
            @click="openCreateDialog"
          />
        </el-tooltip>
      </div>
    </el-card>

    <el-card class="panel-card">
      <el-table v-loading="loading" :data="groups" row-key="id" border>
        <el-table-column label="标签组名称" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="label-group-name-cell">
              <el-tooltip :content="row.name" placement="top" :show-after="300">
                <span>{{ row.name }}</span>
              </el-tooltip>
              <el-tag v-if="row.systemDefault" size="small" effect="plain">系统默认</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.groupType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="值类型" width="100">
          <template #default="{ row }">{{ valueTypeLabel(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="适用范围" width="150">
          <template #default="{ row }">
            {{ row.applicableScope === 'SAME_FIELD' ? '仅同来源字段' : '同类型字段' }}
          </template>
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
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              class="label-group-status-switch"
              :model-value="row.enabled"
              :loading="togglingId === row.id"
              aria-label="切换标签组状态"
              @change="setGroupEnabled(row, Boolean($event))"
            />
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="104" fixed="right">
          <template #default="{ row }">
            <div class="label-group-row-actions">
              <el-tooltip content="编辑" placement="top">
                <el-button
                  class="label-group-icon-button"
                  link
                  type="primary"
                  :icon="Edit"
                  aria-label="编辑"
                  @click="openEditDialog(row)"
                />
              </el-tooltip>
              <el-tooltip
                :content="row.systemDefault ? '系统默认标签组不能删除，可编辑成员、备注或停用' : '删除'"
                placement="top"
              >
                <span>
                  <el-button
                    class="label-group-icon-button"
                    link
                    type="danger"
                    :icon="Delete"
                    :disabled="row.systemDefault"
                    :loading="deletingId === row.id"
                    aria-label="删除"
                    @click="deleteGroup(row)"
                  />
                </span>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editMode ? '编辑标签组' : '新建标签组'"
      width="1120px"
      destroy-on-close
    >
      <el-form label-position="top" class="label-group-form">
        <el-form-item label="标签组名称" required>
          <el-input
            v-model="form.name"
            maxlength="100"
            show-word-limit
            :disabled="form.systemDefault"
            placeholder="例如：核心人员、重点关注人员"
          />
        </el-form-item>
        <el-form-item label="标签组类型" required>
          <el-segmented v-model="form.groupType" :options="groupTypeOptions" :disabled="form.systemDefault" />
        </el-form-item>
        <el-form-item label="当前值类型">
          <el-tag :type="currentValueType === 'MIXED' ? 'danger' : 'primary'" effect="plain">
            {{ valueTypeLabel(currentValueType) }}
          </el-tag>
        </el-form-item>
        <el-form-item label="适用范围" required>
          <el-segmented
            v-model="form.applicableScope"
            :options="applicableScopeOptions"
            :disabled="form.systemDefault"
          />
        </el-form-item>
        <el-form-item v-if="form.applicableScope === 'SAME_FIELD'" label="来源字段" required>
          <el-select
            v-model="form.sourceFieldKey"
            filterable
            fit-input-width
            :disabled="form.systemDefault"
            placeholder="选择同字段限制来源"
            popper-class="platform-select-dropdown"
            style="width: 100%"
          >
            <el-option
              v-for="item in sourceFieldOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>

        <template v-if="form.groupType === 'DYNAMIC'">
          <DynamicLabelGroupRuleBuilder
            :model-value="form.dynamicRule"
            :sources="dynamicRuleSources"
            :relations="dynamicRuleRelations"
            :summary="dynamicRuleSummary"
            :previewing="previewingDynamicRule"
            @preview="previewDynamicRule"
          />
          <el-form-item v-if="dynamicRulePreview" label="预览结果">
            <div class="dynamic-rule-preview">
              <el-tag size="small" :type="dynamicRulePreview.members.length ? 'success' : 'info'">
                {{ dynamicRulePreview.message }}
              </el-tag>
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
              fit-input-width
              filterable
              placeholder="选择成员值来源"
              popper-class="platform-select-dropdown"
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
            fit-input-width
            filterable
            placeholder="选择同值类型标签组"
            popper-class="platform-select-dropdown"
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

.label-group-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.label-group-name-cell span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.label-group-icon-button {
  width: 34px;
  height: 34px;
  padding: 0;
  font-size: 17px;
}

.label-group-row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.label-group-status-switch {
  transform: scale(1.08);
  transform-origin: left center;
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
