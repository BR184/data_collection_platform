<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type {
  TestingPhaseDefinitionResponse,
  TestingPhaseDefinitionSaveRequest,
  TestingPhaseProjectOptionResponse,
} from '../types/api';

const LEGACY_CROWN_CAD_PROJECT_ID = 9;

const loading = ref(false);
const saving = ref(false);
const deletingId = ref<number | null>(null);
const dialogVisible = ref(false);
const editMode = ref(false);
const projectId = ref<number>(LEGACY_CROWN_CAD_PROJECT_ID);
const keyword = ref('');
const enabledFilter = ref<string>('true');
const definitions = ref<TestingPhaseDefinitionResponse[]>([]);
const projectOptions = ref<TestingPhaseProjectOptionResponse[]>([]);

const form = reactive<TestingPhaseDefinitionSaveRequest & { id?: number }>({
  projectId: LEGACY_CROWN_CAD_PROJECT_ID,
  legacySourceId: null,
  legacyPhaseName: '',
  legacySortOrder: null,
  testingPhase: '',
  phaseStartAt: null,
  phaseEndAt: null,
  enabled: true,
  remark: '',
});

const enabledOptions = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' },
];

const tableData = computed(() => definitions.value);
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
  await Promise.all([loadProjects(), loadDefinitions()]);
});

watch(projectId, async () => {
  await loadDefinitions();
});

async function loadProjects() {
  try {
    projectOptions.value = await api.getTestingPhaseProjectOptions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '项目选项加载失败');
  }
}

async function loadDefinitions() {
  loading.value = true;
  try {
    definitions.value = await api.getTestingPhases({
      projectId: projectId.value,
      keyword: keyword.value,
      enabled: enabledFilter.value,
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段定义加载失败');
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.id = undefined;
  form.projectId = projectId.value || LEGACY_CROWN_CAD_PROJECT_ID;
  form.legacySourceId = null;
  form.legacyPhaseName = '';
  form.legacySortOrder = null;
  form.testingPhase = '';
  form.phaseStartAt = null;
  form.phaseEndAt = null;
  form.enabled = true;
  form.remark = '';
}

function openCreateDialog() {
  editMode.value = false;
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: TestingPhaseDefinitionResponse) {
  editMode.value = true;
  form.id = row.id;
  form.projectId = row.projectId;
  form.legacySourceId = row.legacySourceId ?? null;
  form.legacyPhaseName = row.legacyPhaseName ?? '';
  form.legacySortOrder = row.legacySortOrder ?? null;
  form.testingPhase = row.testingPhase;
  form.phaseStartAt = row.phaseStartAt ?? null;
  form.phaseEndAt = row.phaseEndAt ?? null;
  form.enabled = row.enabled;
  form.remark = row.remark ?? '';
  dialogVisible.value = true;
}

function validateForm() {
  if (!form.projectId || form.projectId <= 0) {
    return '项目 ID 不能为空';
  }
  if (!String(form.legacyPhaseName ?? '').trim()) {
    return '里程碑不能为空';
  }
  if (!String(form.testingPhase ?? '').trim()) {
    return '测试阶段不能为空';
  }
  return '';
}

function buildPayload(): TestingPhaseDefinitionSaveRequest {
  return {
    projectId: Number(form.projectId),
    legacySourceId: form.legacySourceId ? Number(form.legacySourceId) : null,
    legacyPhaseName: String(form.legacyPhaseName ?? '').trim(),
    legacySortOrder: form.legacySortOrder == null ? null : Number(form.legacySortOrder),
    testingPhase: String(form.testingPhase).trim(),
    phaseStartAt: form.phaseStartAt || null,
    phaseEndAt: form.phaseEndAt || null,
    enabled: form.enabled,
    remark: String(form.remark ?? '').trim() || null,
  };
}

async function submitForm() {
  const errorMessage = validateForm();
  if (errorMessage) {
    ElMessage.warning(errorMessage);
    return;
  }
  saving.value = true;
  try {
    if (editMode.value && form.id) {
      await api.updateTestingPhase(form.id, buildPayload());
      ElMessage.success('测试阶段定义已更新');
    } else {
      await api.createTestingPhase(buildPayload());
      ElMessage.success('测试阶段定义已创建');
    }
    dialogVisible.value = false;
    await loadDefinitions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段定义保存失败');
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: TestingPhaseDefinitionResponse) {
  try {
    await api.setTestingPhaseEnabled(row.id, !row.enabled);
    ElMessage.success(!row.enabled ? '测试阶段已启用' : '测试阶段已停用');
    await loadDefinitions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段状态更新失败');
  }
}

async function deleteDefinition(row: TestingPhaseDefinitionResponse) {
  try {
    await ElMessageBox.confirm(`确认删除“${row.testingPhase}”？删除后相关统计将无法通过该定义展开轮次。`, '删除测试阶段定义', {
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
    ElMessage.success('测试阶段定义已删除');
    await loadDefinitions();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试阶段定义删除失败');
  } finally {
    deletingId.value = null;
  }
}
</script>

<template>
  <div class="testing-phase-definition-page">
    <el-card class="panel-card testing-phase-toolbar-card">
      <div class="testing-phase-toolbar">
        <div class="testing-phase-toolbar-main">
          <el-select
            v-model="projectId"
            filterable
            fit-input-width
            placeholder="选择项目"
            popper-class="platform-select-dropdown"
            style="width: 220px"
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
            style="width: 130px"
            @change="loadDefinitions"
            @clear="loadDefinitions"
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
            placeholder="搜索里程碑或测试阶段"
            style="width: 280px"
            :prefix-icon="Search"
            @keyup.enter="loadDefinitions"
            @clear="loadDefinitions"
          />
          <el-button :icon="Refresh" :loading="loading" @click="loadDefinitions">刷新</el-button>
        </div>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建测试阶段</el-button>
      </div>
    </el-card>

    <el-card class="panel-card">
      <el-table v-loading="loading" :data="tableData" row-key="id" border>
        <el-table-column prop="legacyPhaseName" label="里程碑" min-width="130" />
        <el-table-column prop="testingPhase" label="测试阶段" min-width="220" />
        <el-table-column prop="legacySortOrder" label="序号" width="90" />
        <el-table-column prop="legacySourceId" label="老平台ID" min-width="170" />
        <el-table-column prop="issueCount" label="议题数" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="primary" @click="toggleEnabled(row)">
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :loading="deletingId === row.id"
              @click="deleteDefinition(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogVisible"
      :title="editMode ? '编辑测试阶段定义' : '新建测试阶段定义'"
      width="720px"
      destroy-on-close
    >
      <el-form label-position="top" class="testing-phase-form">
        <div class="testing-phase-form-grid">
          <el-form-item label="项目 ID" required>
            <el-input-number v-model="form.projectId" :min="1" :controls="false" style="width: 100%" />
          </el-form-item>
          <el-form-item label="序号">
            <el-input-number v-model="form.legacySortOrder" :controls="false" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="里程碑" required>
          <el-input v-model="form.legacyPhaseName" maxlength="128" placeholder="例如：CC2026R3" />
        </el-form-item>
        <el-form-item label="测试阶段" required>
          <el-input v-model="form.testingPhase" maxlength="128" placeholder="例如：CC2026R3第一轮系统测试" />
        </el-form-item>
        <el-form-item label="老平台ID">
          <el-input-number v-model="form.legacySourceId" :controls="false" style="width: 100%" />
        </el-form-item>
        <div class="testing-phase-form-grid">
          <el-form-item label="开始时间">
            <el-date-picker v-model="form.phaseStartAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束时间">
            <el-date-picker v-model="form.phaseEndAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" show-word-limit />
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
.testing-phase-definition-page {
  display: grid;
  gap: 16px;
}

.testing-phase-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.testing-phase-toolbar-main {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
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

@media (max-width: 760px) {
  .testing-phase-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .testing-phase-toolbar-main,
  .testing-phase-form-grid {
    grid-template-columns: 1fr;
  }

  .testing-phase-toolbar-main > * {
    width: 100% !important;
  }
}
</style>
