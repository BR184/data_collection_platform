<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  api,
  type TestingPhaseDefinitionResponse,
  type TestingPhaseDefinitionSaveRequest,
  type TestingPhaseProjectOptionResponse,
} from '../api';

interface FilterForm {
  projectId: string;
  keyword: string;
  enabled: string;
}

interface PhaseForm {
  id: number | null;
  projectId: number | null;
  testingPhase: string;
  phaseStartAt: string;
  phaseEndAt: string;
  enabled: boolean;
  remark: string;
}

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const rows = ref<TestingPhaseDefinitionResponse[]>([]);
const projectOptions = ref<TestingPhaseProjectOptionResponse[]>([]);
const formRef = ref<FormInstance>();

const filters = reactive<FilterForm>({
  projectId: String(route.query.projectId ?? ''),
  keyword: String(route.query.keyword ?? ''),
  enabled: String(route.query.enabled ?? ''),
});

const form = reactive<PhaseForm>({
  id: null,
  projectId: null,
  testingPhase: '',
  phaseStartAt: '',
  phaseEndAt: '',
  enabled: true,
  remark: '',
});

const rules: FormRules<PhaseForm> = {
  projectId: [{ required: true, message: '请输入项目 ID', trigger: 'blur' }],
  testingPhase: [{ required: true, message: '请输入测试阶段', trigger: 'blur' }],
  phaseStartAt: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
};

const enabledCount = computed(() => rows.value.filter((item) => item.enabled).length);
const disabledCount = computed(() => rows.value.length - enabledCount.value);
const relatedIssueCount = computed(() => rows.value.reduce((sum, item) => sum + (item.issueCount ?? 0), 0));
const dialogTitle = computed(() => (form.id == null ? '新增测试阶段' : '编辑测试阶段'));
const projectSelectOptions = computed(() =>
  projectOptions.value.map((item) => ({
    label: item.projectName ? `${item.projectName}（${item.projectId}）` : String(item.projectId),
    value: String(item.projectId),
  })),
);
const formProjectName = computed(() => {
  const projectId = form.projectId;
  if (projectId == null) {
    return '';
  }
  return projectOptions.value.find((item) => item.projectId === projectId)?.projectName ?? '';
});

async function loadProjectOptions() {
  try {
    projectOptions.value = await api.getTestingPhaseProjectOptions();
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
}

async function loadRows() {
  loading.value = true;
  try {
    rows.value = await api.getTestingPhases({
      projectId: filters.projectId,
      keyword: filters.keyword,
      enabled: filters.enabled,
    });
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    loading.value = false;
  }
}

async function applyFilters() {
  await router.replace({
    query: {
      ...route.query,
      projectId: filters.projectId || undefined,
      keyword: filters.keyword || undefined,
      enabled: filters.enabled || undefined,
    },
  });
  await loadRows();
}

async function resetFilters() {
  filters.projectId = '';
  filters.keyword = '';
  filters.enabled = '';
  await applyFilters();
}

function openCreateDialog() {
  resetForm();
  dialogVisible.value = true;
}

function openEditDialog(row: TestingPhaseDefinitionResponse) {
  form.id = row.id;
  form.projectId = row.projectId;
  form.testingPhase = row.testingPhase;
  form.phaseStartAt = normalizeDateTimeValue(row.phaseStartAt);
  form.phaseEndAt = normalizeDateTimeValue(row.phaseEndAt);
  form.enabled = row.enabled;
  form.remark = row.remark ?? '';
  dialogVisible.value = true;
}

async function savePhase() {
  const instance = formRef.value;
  if (!instance) {
    return;
  }
  const valid = await instance.validate().catch(() => false);
  if (!valid || form.projectId == null) {
    return;
  }
  saving.value = true;
  try {
    const payload: TestingPhaseDefinitionSaveRequest = {
      projectId: form.projectId,
      testingPhase: form.testingPhase.trim(),
      phaseStartAt: form.phaseStartAt,
      phaseEndAt: form.phaseEndAt || null,
      enabled: form.enabled,
      remark: form.remark.trim() || null,
    };
    if (form.id == null) {
      await api.createTestingPhase(payload);
      ElMessage.success('测试阶段已新增');
    } else {
      await api.updateTestingPhase(form.id, payload);
      ElMessage.success('测试阶段已更新');
    }
    dialogVisible.value = false;
    await Promise.all([loadProjectOptions(), loadRows()]);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(row: TestingPhaseDefinitionResponse) {
  const nextEnabled = row.enabled;
  try {
    await api.setTestingPhaseEnabled(row.id, nextEnabled);
    ElMessage.success(nextEnabled ? '测试阶段已启用' : '测试阶段已停用');
    await loadRows();
  } catch (error) {
    row.enabled = !nextEnabled;
    ElMessage.error((error as Error).message);
  }
}

async function deletePhase(row: TestingPhaseDefinitionResponse) {
  const issueHint = row.issueCount > 0 ? `当前已有 ${row.issueCount} 条事实数据关联该阶段，` : '';
  try {
    await ElMessageBox.confirm(
      `${issueHint}删除后新一轮事实重建将不再按该定义归档。确认删除“${row.testingPhase}”吗？`,
      '删除测试阶段',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
    await api.deleteTestingPhase(row.id);
    ElMessage.success('测试阶段已删除');
    await loadRows();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error((error as Error).message);
    }
  }
}

function resetForm() {
  form.id = null;
  form.projectId = filters.projectId ? Number(filters.projectId) : null;
  form.testingPhase = '';
  form.phaseStartAt = '';
  form.phaseEndAt = '';
  form.enabled = true;
  form.remark = '';
  formRef.value?.clearValidate();
}

function normalizeDateTimeValue(value?: string | null) {
  if (!value) {
    return '';
  }
  return value.length > 19 ? value.slice(0, 19) : value;
}

function formatDateTime(value?: string | null) {
  const normalized = normalizeDateTimeValue(value);
  return normalized ? normalized.replace('T', ' ') : '-';
}

onMounted(async () => {
  await Promise.all([loadProjectOptions(), loadRows()]);
});
</script>

<template>
  <div class="testing-phase-page">
    <el-card class="phase-hero" shadow="never">
      <div class="phase-hero__content">
        <div>
          <p class="phase-eyebrow">系统设置 / 事实层基础配置</p>
          <h2>议题测试阶段定义</h2>
          <p class="phase-description">
            维护系统测试议题的阶段时间窗。事实层重建时会按这里的项目、阶段和日期区间归档，后续统计页直接复用。
          </p>
        </div>
        <el-button :icon="Plus" type="primary" @click="openCreateDialog">新增阶段</el-button>
      </div>
      <div class="phase-stats">
        <div class="phase-stat">
          <span>阶段定义</span>
          <strong>{{ rows.length }}</strong>
        </div>
        <div class="phase-stat">
          <span>启用中</span>
          <strong>{{ enabledCount }}</strong>
        </div>
        <div class="phase-stat">
          <span>停用</span>
          <strong>{{ disabledCount }}</strong>
        </div>
        <div class="phase-stat">
          <span>关联议题</span>
          <strong>{{ relatedIssueCount }}</strong>
        </div>
      </div>
    </el-card>

    <el-card class="phase-card" shadow="never">
      <div class="phase-toolbar">
        <el-select v-model="filters.projectId" clearable filterable placeholder="全部项目" style="width: 240px">
          <el-option
            v-for="item in projectSelectOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="filters.enabled" clearable placeholder="启用状态" style="width: 140px">
          <el-option label="全部状态" value="" />
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-input
          v-model="filters.keyword"
          :prefix-icon="Search"
          clearable
          placeholder="搜索阶段、项目或备注"
          style="width: 260px"
          @keyup.enter="applyFilters"
        />
        <el-button type="primary" @click="applyFilters">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button :icon="Refresh" @click="loadRows">刷新</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" border stripe height="calc(100vh - 360px)">
        <el-table-column label="项目" min-width="220">
          <template #default="{ row }">
            <div class="project-cell">
              <strong>{{ row.projectName || '未识别项目名称' }}</strong>
              <span>ID: {{ row.projectId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="testingPhase" label="测试阶段" min-width="180" />
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.phaseStartAt) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.phaseEndAt) }}</template>
        </el-table-column>
        <el-table-column prop="issueCount" label="关联议题" width="110" align="right" />
        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        <el-table-column label="更新时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button :icon="Edit" link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button :icon="Delete" link type="danger" @click="deletePhase(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="620px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="项目 ID" prop="projectId">
          <el-input-number v-model="form.projectId" :min="1" :precision="0" controls-position="right" />
          <span v-if="formProjectName" class="form-hint">{{ formProjectName }}</span>
        </el-form-item>
        <el-form-item label="测试阶段" prop="testingPhase">
          <el-input v-model="form.testingPhase" maxlength="128" placeholder="例如：CC2026R2 第一轮系统测试" />
        </el-form-item>
        <el-form-item label="开始时间" prop="phaseStartAt">
          <el-date-picker
            v-model="form.phaseStartAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker
            v-model="form.phaseEndAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="可留空，表示持续到下一阶段或当前"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            maxlength="255"
            placeholder="记录阶段来源、适用版本或临时说明"
            show-word-limit
            type="textarea"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button :loading="saving" type="primary" @click="savePhase">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.testing-phase-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.phase-hero {
  border: 1px solid #dbeafe;
  background:
    radial-gradient(circle at 20% 20%, rgba(59, 130, 246, 0.14), transparent 30%),
    linear-gradient(135deg, #f8fafc 0%, #eff6ff 100%);
}

.phase-hero__content {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.phase-eyebrow {
  margin: 0 0 6px;
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.phase-hero h2 {
  margin: 0;
  color: #0f172a;
  font-size: 24px;
}

.phase-description {
  max-width: 760px;
  margin: 8px 0 0;
  color: #475569;
  line-height: 1.7;
}

.phase-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 12px;
  margin-top: 18px;
}

.phase-stat {
  padding: 14px 16px;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
}

.phase-stat span {
  display: block;
  color: #64748b;
  font-size: 13px;
}

.phase-stat strong {
  display: block;
  margin-top: 6px;
  color: #0f172a;
  font-size: 24px;
}

.phase-card {
  border: 1px solid #e5e7eb;
}

.phase-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.project-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.project-cell span {
  color: #64748b;
  font-size: 12px;
}

.form-hint {
  margin-left: 12px;
  color: #64748b;
}

@media (max-width: 900px) {
  .phase-hero__content {
    flex-direction: column;
  }

  .phase-stats {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
