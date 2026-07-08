<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ArrowDown, ArrowRight, ArrowUp, Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import SmartTableHeader from '../components/base/SmartTableHeader.vue';
import TableFunctionBar from '../components/base/TableFunctionBar.vue';
import type {
  TestingPhaseDefinitionResponse,
  TestingPhaseDefinitionSaveRequest,
  TestingPhaseGroupResponse,
  TestingPhaseGroupSaveRequest,
  TestingPhaseProjectOptionResponse,
} from '../types/api';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;

const loading = ref(false);
const saving = ref(false);
const deletingId = ref<number | null>(null);
const groupDialogVisible = ref(false);
const childDialogVisible = ref(false);
const groupEditMode = ref(false);
const childEditMode = ref(false);
const selectedGroupId = ref<number | null>(null);
const projectId = ref<number>(LEGACY_CROWN_CAD_PROJECT_ID);
const keyword = ref('');
const enabledFilter = ref<string>('');
const groups = ref<TestingPhaseGroupResponse[]>([]);
const projectOptions = ref<TestingPhaseProjectOptionResponse[]>([]);

const groupForm = reactive<TestingPhaseGroupSaveRequest & { id?: number }>({
  projectId: LEGACY_CROWN_CAD_PROJECT_ID,
  name: '',
  sortOrder: null,
  enabled: true,
  remark: '',
});

const childForm = reactive<TestingPhaseDefinitionSaveRequest & { id?: number }>({
  projectId: LEGACY_CROWN_CAD_PROJECT_ID,
  legacySourceId: null,
  legacyPhaseName: '',
  legacySortOrder: null,
  phaseGroupId: null,
  childSortOrder: null,
  testingPhase: '',
  phaseStartAt: null,
  phaseEndAt: null,
  enabled: true,
  remark: '',
});

const enabledOptions = [
  { label: '全部', value: '' },
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' },
];

const selectedGroup = computed(() => groups.value.find((item) => item.id === selectedGroupId.value) ?? null);
const childRows = computed(() => selectedGroup.value?.children ?? []);
const projectSelectOptions = computed(() => {
  const options = projectOptions.value.map((item) => ({
    label: item.projectName ? `${item.projectName} / ${item.projectId}` : String(item.projectId),
    value: item.projectId,
  }));
  if (!options.some((item) => item.value === LEGACY_CROWN_CAD_PROJECT_ID)) {
    options.unshift({ label: `CrownCAD / ${LEGACY_CROWN_CAD_PROJECT_ID}`, value: LEGACY_CROWN_CAD_PROJECT_ID });
  }
  return options;
});

onMounted(async () => {
  await Promise.all([loadProjects(), loadGroups()]);
});

watch(projectId, async () => {
  selectedGroupId.value = null;
  await loadGroups();
});

async function loadProjects() {
  try {
    projectOptions.value = await api.getTestingPhaseProjectOptions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '项目选项加载失败');
  }
}

async function loadGroups() {
  loading.value = true;
  try {
    groups.value = await api.getTestingPhaseGroups({
      projectId: projectId.value,
      keyword: keyword.value,
      enabled: enabledFilter.value,
    });
    if (selectedGroupId.value && !groups.value.some((item) => item.id === selectedGroupId.value)) {
      selectedGroupId.value = null;
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段定义加载失败');
  } finally {
    loading.value = false;
  }
}

function selectGroup(row: TestingPhaseGroupResponse) {
  selectedGroupId.value = selectedGroupId.value === row.id ? null : row.id;
}

function resetGroupForm() {
  groupForm.id = undefined;
  groupForm.projectId = projectId.value || LEGACY_CROWN_CAD_PROJECT_ID;
  groupForm.name = '';
  groupForm.sortOrder = nextGroupSortOrder();
  groupForm.enabled = true;
  groupForm.remark = '';
}

function resetChildForm() {
  const group = selectedGroup.value;
  childForm.id = undefined;
  childForm.projectId = group?.projectId ?? projectId.value ?? LEGACY_CROWN_CAD_PROJECT_ID;
  childForm.legacySourceId = null;
  childForm.legacyPhaseName = group?.name ?? '';
  childForm.legacySortOrder = group?.sortOrder ?? null;
  childForm.phaseGroupId = group?.id ?? null;
  childForm.childSortOrder = nextChildSortOrder();
  childForm.testingPhase = group?.name ? `${group.name}第一轮系统测试` : '';
  childForm.phaseStartAt = null;
  childForm.phaseEndAt = null;
  childForm.enabled = true;
  childForm.remark = '';
}

function openCreateGroupDialog() {
  groupEditMode.value = false;
  resetGroupForm();
  groupDialogVisible.value = true;
}

function openEditGroupDialog(row: TestingPhaseGroupResponse) {
  groupEditMode.value = true;
  groupForm.id = row.id;
  groupForm.projectId = row.projectId;
  groupForm.name = row.name;
  groupForm.sortOrder = row.sortOrder;
  groupForm.enabled = row.enabled;
  groupForm.remark = row.remark ?? '';
  groupDialogVisible.value = true;
}

function openCreateChildDialog() {
  if (!selectedGroup.value) {
    ElMessage.warning('请先选择阶段名称');
    return;
  }
  childEditMode.value = false;
  resetChildForm();
  childDialogVisible.value = true;
}

function openEditChildDialog(row: TestingPhaseDefinitionResponse) {
  childEditMode.value = true;
  childForm.id = row.id;
  childForm.projectId = row.projectId;
  childForm.legacySourceId = row.legacySourceId ?? null;
  childForm.legacyPhaseName = row.legacyPhaseName ?? selectedGroup.value?.name ?? '';
  childForm.legacySortOrder = row.legacySortOrder ?? selectedGroup.value?.sortOrder ?? null;
  childForm.phaseGroupId = row.phaseGroupId ?? selectedGroup.value?.id ?? null;
  childForm.childSortOrder = row.childSortOrder ?? null;
  childForm.testingPhase = row.testingPhase;
  childForm.phaseStartAt = row.phaseStartAt ?? null;
  childForm.phaseEndAt = row.phaseEndAt ?? null;
  childForm.enabled = row.enabled;
  childForm.remark = row.remark ?? '';
  childDialogVisible.value = true;
}

function buildGroupPayload(): TestingPhaseGroupSaveRequest {
  return {
    projectId: Number(groupForm.projectId),
    name: String(groupForm.name ?? '').trim(),
    sortOrder: groupForm.sortOrder == null ? null : Number(groupForm.sortOrder),
    enabled: groupForm.enabled,
    remark: String(groupForm.remark ?? '').trim() || null,
  };
}

function buildChildPayload(): TestingPhaseDefinitionSaveRequest {
  const group = selectedGroup.value;
  return {
    projectId: Number(childForm.projectId),
    legacySourceId: childForm.legacySourceId ? Number(childForm.legacySourceId) : null,
    legacyPhaseName: String(childForm.legacyPhaseName || group?.name || '').trim(),
    legacySortOrder: childForm.legacySortOrder == null ? group?.sortOrder ?? null : Number(childForm.legacySortOrder),
    phaseGroupId: childForm.phaseGroupId ?? group?.id ?? null,
    childSortOrder: childForm.childSortOrder == null ? null : Number(childForm.childSortOrder),
    testingPhase: String(childForm.testingPhase).trim(),
    phaseStartAt: childForm.phaseStartAt || null,
    phaseEndAt: childForm.phaseEndAt || null,
    enabled: childForm.enabled,
    remark: String(childForm.remark ?? '').trim() || null,
  };
}

async function submitGroupForm() {
  const payload = buildGroupPayload();
  if (!payload.projectId || payload.projectId <= 0) {
    ElMessage.warning('项目 ID 不能为空');
    return;
  }
  if (!payload.name) {
    ElMessage.warning('阶段名称不能为空');
    return;
  }
  saving.value = true;
  try {
    const saved = groupEditMode.value && groupForm.id
      ? await api.updateTestingPhaseGroup(groupForm.id, payload)
      : await api.createTestingPhaseGroup(payload);
    selectedGroupId.value = saved.id;
    groupDialogVisible.value = false;
    ElMessage.success(groupEditMode.value ? '阶段名称已更新' : '阶段名称已创建');
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '阶段名称保存失败');
  } finally {
    saving.value = false;
  }
}

async function submitChildForm() {
  const payload = buildChildPayload();
  if (!payload.phaseGroupId) {
    ElMessage.warning('阶段名称不能为空');
    return;
  }
  if (!payload.testingPhase) {
    ElMessage.warning('测试阶段名称不能为空');
    return;
  }
  saving.value = true;
  try {
    if (childEditMode.value && childForm.id) {
      await api.updateTestingPhase(childForm.id, payload);
      ElMessage.success('测试阶段名称已更新');
    } else {
      await api.createTestingPhase(payload);
      ElMessage.success('测试阶段名称已创建');
    }
    childDialogVisible.value = false;
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段名称保存失败');
  } finally {
    saving.value = false;
  }
}

async function setGroupEnabled(row: TestingPhaseGroupResponse, enabled: boolean) {
  if (row.enabled === enabled) {
    return;
  }
  try {
    await api.setTestingPhaseGroupEnabled(row.id, enabled);
    ElMessage.success(enabled ? '阶段名称已启用' : '阶段名称已停用');
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '阶段名称状态更新失败');
  }
}

async function setChildEnabled(row: TestingPhaseDefinitionResponse, enabled: boolean) {
  if (row.enabled === enabled) {
    return;
  }
  try {
    await api.setTestingPhaseEnabled(row.id, enabled);
    ElMessage.success(enabled ? '测试阶段名称已启用' : '测试阶段名称已停用');
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段名称状态更新失败');
  }
}

async function deleteGroup(row: TestingPhaseGroupResponse) {
  try {
    await ElMessageBox.confirm(`确认删除“${row.name}”及其下 ${row.children.length} 个测试阶段名称？`, '删除阶段名称', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  deletingId.value = row.id;
  try {
    await api.deleteTestingPhaseGroup(row.id);
    ElMessage.success('阶段名称已删除');
    if (selectedGroupId.value === row.id) {
      selectedGroupId.value = null;
    }
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '阶段名称删除失败');
  } finally {
    deletingId.value = null;
  }
}

async function deleteChild(row: TestingPhaseDefinitionResponse) {
  try {
    await ElMessageBox.confirm(`确认删除“${row.testingPhase}”？`, '删除测试阶段名称', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  deletingId.value = row.id;
  try {
    await api.deleteTestingPhase(row.id);
    ElMessage.success('测试阶段名称已删除');
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段名称删除失败');
  } finally {
    deletingId.value = null;
  }
}

async function moveGroup(row: TestingPhaseGroupResponse, direction: -1 | 1) {
  const index = groups.value.findIndex((item) => item.id === row.id);
  const target = groups.value[index + direction];
  if (!target) {
    return;
  }
  await swapGroupOrder(row, target);
}

async function moveChild(row: TestingPhaseDefinitionResponse, direction: -1 | 1) {
  const rows = childRows.value;
  const index = rows.findIndex((item) => item.id === row.id);
  const target = rows[index + direction];
  if (!target) {
    return;
  }
  await swapChildOrder(row, target);
}

async function swapGroupOrder(left: TestingPhaseGroupResponse, right: TestingPhaseGroupResponse) {
  try {
    await Promise.all([
      api.updateTestingPhaseGroup(left.id, groupPayloadFromRow(left, right.sortOrder)),
      api.updateTestingPhaseGroup(right.id, groupPayloadFromRow(right, left.sortOrder)),
    ]);
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '阶段名称排序更新失败');
  }
}

async function swapChildOrder(left: TestingPhaseDefinitionResponse, right: TestingPhaseDefinitionResponse) {
  try {
    await Promise.all([
      api.updateTestingPhase(left.id, childPayloadFromRow(left, right.childSortOrder ?? nextChildSortOrder())),
      api.updateTestingPhase(right.id, childPayloadFromRow(right, left.childSortOrder ?? nextChildSortOrder())),
    ]);
    await loadGroups();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段名称排序更新失败');
  }
}

function groupPayloadFromRow(row: TestingPhaseGroupResponse, sortOrder: number): TestingPhaseGroupSaveRequest {
  return {
    projectId: row.projectId,
    name: row.name,
    sortOrder,
    enabled: row.enabled,
    remark: row.remark ?? null,
  };
}

function childPayloadFromRow(row: TestingPhaseDefinitionResponse, childSortOrder: number): TestingPhaseDefinitionSaveRequest {
  return {
    projectId: row.projectId,
    legacySourceId: row.legacySourceId ?? null,
    legacyPhaseName: row.legacyPhaseName ?? selectedGroup.value?.name ?? '',
    legacySortOrder: row.legacySortOrder ?? selectedGroup.value?.sortOrder ?? null,
    phaseGroupId: row.phaseGroupId ?? selectedGroup.value?.id ?? null,
    childSortOrder,
    testingPhase: row.testingPhase,
    phaseStartAt: row.phaseStartAt ?? null,
    phaseEndAt: row.phaseEndAt ?? null,
    enabled: row.enabled,
    remark: row.remark ?? null,
  };
}

function nextGroupSortOrder() {
  return Math.max(0, ...groups.value.map((item) => item.sortOrder ?? 0)) + 1;
}

function nextChildSortOrder() {
  return Math.max(0, ...childRows.value.map((item) => item.childSortOrder ?? 0)) + 1;
}
</script>

<template>
  <div class="testing-phase-definition-page">
    <el-card class="panel-card testing-phase-toolbar-card">
      <TableFunctionBar>
        <template #status>
          <div class="testing-phase-toolbar-main">
            <el-select
              v-model="projectId"
              filterable
              fit-input-width
              placeholder="选择项目"
              popper-class="platform-select-dropdown"
              class="testing-phase-toolbar__project"
            >
              <el-option
                v-for="option in projectSelectOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-select
              v-model="enabledFilter"
              clearable
              fit-input-width
              placeholder="全部状态"
              popper-class="platform-select-dropdown"
              class="testing-phase-toolbar__status"
              @change="loadGroups"
              @clear="loadGroups"
            >
              <el-option
                v-for="option in enabledOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-input
              v-model="keyword"
              clearable
              placeholder="搜索阶段或测试阶段名称"
              class="testing-phase-toolbar__keyword"
              :prefix-icon="Search"
              @keyup.enter="loadGroups"
              @clear="loadGroups"
            />
            <el-button :icon="Refresh" :loading="loading" @click="loadGroups">刷新</el-button>
          </div>
        </template>
        <template #actions>
          <el-button type="primary" :icon="Plus" @click="openCreateGroupDialog">新增阶段名称</el-button>
        </template>
      </TableFunctionBar>
    </el-card>

    <div class="testing-phase-layout">
      <el-card class="panel-card phase-list-panel">
        <template #header>
          <div class="phase-panel-header">
            <span>阶段名称</span>
            <el-button link type="primary" :icon="Plus" @click="openCreateGroupDialog">新增</el-button>
          </div>
        </template>
        <div v-loading="loading" class="phase-list">
          <div
            v-for="(row, index) in groups"
            :key="row.id"
            class="phase-list-row"
            :class="{ active: selectedGroupId === row.id, disabled: !row.enabled }"
            role="button"
            tabindex="0"
            @click="selectGroup(row)"
            @keydown.enter="selectGroup(row)"
            @keydown.space.prevent="selectGroup(row)"
          >
            <span class="phase-row-order">{{ row.sortOrder }}</span>
            <span class="phase-row-main">
              <span class="phase-row-title">{{ row.name }}</span>
              <span class="phase-row-meta">
                <span>{{ row.children.length }} 个测试阶段</span>
                <span>{{ row.issueCount }} 个议题</span>
              </span>
            </span>
            <el-switch
              :model-value="row.enabled"
              size="small"
              :aria-label="row.enabled ? '停用阶段名称' : '启用阶段名称'"
              @click.stop
              @change="setGroupEnabled(row, Boolean($event))"
            />
            <span class="phase-row-actions" @click.stop>
              <el-button link :icon="ArrowUp" :disabled="index === 0" @click="moveGroup(row, -1)" />
              <el-button link :icon="ArrowDown" :disabled="index === groups.length - 1" @click="moveGroup(row, 1)" />
              <el-button link type="primary" :icon="Edit" @click="openEditGroupDialog(row)" />
              <el-button
                link
                type="danger"
                :icon="Delete"
                :loading="deletingId === row.id"
                @click="deleteGroup(row)"
              />
            </span>
            <el-icon class="phase-row-chevron"><ArrowRight /></el-icon>
          </div>
          <el-empty v-if="!loading && groups.length === 0" description="暂无阶段名称" />
        </div>
      </el-card>

      <el-card class="panel-card phase-detail-panel">
        <template #header>
          <div v-if="selectedGroup" class="phase-panel-header">
            <div class="phase-detail-title">
              <span>{{ selectedGroup.name }}</span>
              <el-tag :type="selectedGroup.enabled ? 'success' : 'info'" size="small" effect="plain">
                {{ selectedGroup.enabled ? '启用' : '停用' }}
              </el-tag>
            </div>
            <el-button type="primary" link :icon="Plus" @click="openCreateChildDialog">新增测试阶段</el-button>
          </div>
          <div v-else class="phase-panel-header">
            <span>测试阶段名称</span>
          </div>
        </template>
        <el-table v-if="selectedGroup" v-loading="loading" :data="childRows" row-key="id" border>
          <el-table-column prop="childSortOrder" label="排序" width="74" />
          <el-table-column prop="testingPhase" label="测试阶段名称" min-width="220">
            <template #header>
              <SmartTableHeader label="测试阶段名称" />
            </template>
          </el-table-column>
          <el-table-column prop="legacySourceId" label="老平台ID" min-width="166" />
          <el-table-column prop="issueCount" label="议题数" width="82" />
          <el-table-column label="状态" width="82">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                :aria-label="row.enabled ? '停用测试阶段名称' : '启用测试阶段名称'"
                @change="setChildEnabled(row, Boolean($event))"
              />
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="168" fixed="right">
            <template #default="{ row, $index }">
              <el-button link :icon="ArrowUp" :disabled="$index === 0" @click="moveChild(row, -1)" />
              <el-button link :icon="ArrowDown" :disabled="$index === childRows.length - 1" @click="moveChild(row, 1)" />
              <el-button link type="primary" :icon="Edit" @click="openEditChildDialog(row)" />
              <el-button
                link
                type="danger"
                :icon="Delete"
                :loading="deletingId === row.id"
                @click="deleteChild(row)"
              />
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="请选择左侧阶段名称" />
      </el-card>

    </div>

    <el-dialog v-model="groupDialogVisible" :title="groupEditMode ? '编辑阶段名称' : '新增阶段名称'" width="520px">
      <el-form label-position="top" class="testing-phase-form">
        <div class="testing-phase-form-grid">
          <el-form-item label="项目 ID" required>
            <el-input-number v-model="groupForm.projectId" :min="1" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="groupForm.sortOrder" :controls="false" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="阶段名称" required>
          <el-input v-model="groupForm.name" maxlength="128" placeholder="例如：CC2026R3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="groupForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="groupForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitGroupForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="childDialogVisible"
      :title="childEditMode ? '编辑测试阶段名称' : '新增测试阶段名称'"
      width="720px"
    >
      <el-form label-position="top" class="testing-phase-form">
        <div class="testing-phase-form-grid">
          <el-form-item label="阶段名称">
            <el-input v-model="childForm.legacyPhaseName" disabled />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="childForm.childSortOrder" :controls="false" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="测试阶段名称" required>
          <el-input v-model="childForm.testingPhase" maxlength="128" placeholder="例如：CC2026R3第一轮系统测试" />
        </el-form-item>
        <el-form-item label="老平台ID">
          <el-input-number v-model="childForm.legacySourceId" :controls="false" style="width: 100%" />
        </el-form-item>
        <div class="testing-phase-form-grid">
          <el-form-item label="开始时间">
            <el-date-picker v-model="childForm.phaseStartAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker v-model="childForm.phaseEndAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="状态">
          <el-switch v-model="childForm.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="childForm.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="childDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitChildForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.testing-phase-definition-page {
  display: grid;
  gap: 16px;
}

.testing-phase-toolbar-main {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}

.testing-phase-toolbar__project {
  width: clamp(180px, 24vw, 240px);
}

.testing-phase-toolbar__status {
  width: 132px;
}

.testing-phase-toolbar__keyword {
  width: clamp(220px, 30vw, 340px);
}

.testing-phase-layout {
  display: grid;
  grid-template-columns: minmax(420px, 0.85fr) minmax(540px, 1.15fr);
  gap: 16px;
  min-width: 0;
}

.phase-list-panel,
.phase-detail-panel {
  min-width: 0;
}

.phase-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}

.phase-detail-title {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  gap: 8px;
}

.phase-list {
  display: grid;
  gap: 8px;
  min-height: 180px;
}

.phase-list-row {
  display: grid;
  grid-template-columns: 48px minmax(160px, 1fr) auto auto 18px;
  align-items: center;
  width: 100%;
  min-height: 58px;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.16s ease, background-color 0.16s ease;
}

.phase-list-row:hover {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-fill-color-light);
}

.phase-list-row.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.phase-list-row.disabled {
  color: var(--el-text-color-secondary);
}

.phase-row-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 28px;
  border-radius: 4px;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.phase-row-main {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.phase-row-title {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.phase-row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.phase-row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  white-space: nowrap;
}

.phase-row-chevron {
  color: var(--el-text-color-placeholder);
}

.phase-detail-panel :deep(.el-table__header th) {
  vertical-align: middle;
  padding: 8px 0;
}

.phase-detail-panel :deep(.el-table .cell) {
  display: flex;
  align-items: center;
  min-height: 28px;
}

.testing-phase-form {
  display: grid;
  gap: 4px;
}

.testing-phase-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1120px) {
  .testing-phase-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .testing-phase-form-grid {
    grid-template-columns: 1fr;
  }

  .testing-phase-toolbar-main > * {
    width: 100% !important;
  }

  .phase-list-row {
    grid-template-columns: 42px minmax(0, 1fr) auto;
  }

  .phase-row-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .phase-row-chevron {
    display: none;
  }
}
</style>
