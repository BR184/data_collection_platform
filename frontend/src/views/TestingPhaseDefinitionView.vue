<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type {
  IssueScopeCatalogResponse,
  IssueScopeGroupResponse,
  IssueScopeGroupSaveRequest,
  IssueScopeMemberResponse,
  IssueScopeMemberSaveRequest,
} from '../types/api';

const loading = ref(false);
const saving = ref(false);
const catalogs = ref<IssueScopeCatalogResponse[]>([]);
const catalogId = ref<number | null>(null);
const groups = ref<IssueScopeGroupResponse[]>([]);
const selectedGroupId = ref<number | null>(null);
const keyword = ref('');
const enabled = ref('');
const groupDialogVisible = ref(false);
const memberDialogVisible = ref(false);

const groupForm = reactive<IssueScopeGroupSaveRequest & { id?: number }>({
  catalogId: 0,
  businessKey: '',
  displayName: '',
  sortOrder: 0,
  enabled: true,
  remark: '',
});
const memberForm = reactive<IssueScopeMemberSaveRequest & { id?: number }>({
  catalogId: 0,
  groupId: 0,
  sourceValue: '',
  displayName: '',
  sortOrder: 0,
  activeFrom: null,
  activeUntil: null,
  enabled: true,
  sourceReferenceId: null,
  remark: '',
});

const catalog = computed(() => catalogs.value.find((item) => item.id === catalogId.value) ?? null);
const selectedGroup = computed(() => groups.value.find((item) => item.id === selectedGroupId.value) ?? null);
const isMilestoneCatalog = computed(() => catalog.value?.dimension === 'MILESTONE');
const groupNameLabel = computed(() => isMilestoneCatalog.value ? '里程碑名称' : '阶段名称');
const memberNameLabel = computed(() => isMilestoneCatalog.value ? '里程碑名称' : '测试阶段名称');
const groupNoun = computed(() => isMilestoneCatalog.value ? '里程碑' : '阶段');
const memberNoun = computed(() => isMilestoneCatalog.value ? '里程碑' : '测试阶段');
const addGroupLabel = computed(() => `新增${groupNoun.value}`);
const groupListTitle = computed(() => `${groupNoun.value}与默认顺序`);

onMounted(loadCatalogs);
watch(catalogId, () => {
  selectedGroupId.value = null;
  void loadData();
});

async function loadCatalogs() {
  catalogs.value = await api.getIssueScopeCatalogs();
  if (!catalogId.value || !catalogs.value.some((item) => item.id === catalogId.value)) {
    catalogId.value = catalogs.value[0]?.id ?? null;
  }
}

async function loadData() {
  if (!catalogId.value) {
    groups.value = [];
    return;
  }
  loading.value = true;
  try {
    groups.value = await api.getIssueScopeGroups(catalogId.value, {
      keyword: keyword.value,
      enabled: enabled.value,
    });
  } finally {
    loading.value = false;
  }
}

function createBusinessKey(name: string) {
  return name.replace(/\s+/g, '');
}

function newGroup() {
  Object.assign(groupForm, {
    id: undefined,
    catalogId: catalogId.value ?? 0,
    businessKey: '',
    displayName: '',
    sortOrder: groups.value.length + 1,
    enabled: true,
    remark: '',
  });
  groupDialogVisible.value = true;
}

function editGroup(row: IssueScopeGroupResponse) {
  Object.assign(groupForm, {
    id: row.id,
    catalogId: row.catalogId,
    businessKey: row.businessKey,
    displayName: row.displayName,
    sortOrder: row.sortOrder,
    enabled: row.enabled,
    remark: row.remark,
  });
  groupDialogVisible.value = true;
}

function newMember() {
  const group = selectedGroup.value;
  if (!group) {
    return;
  }
  Object.assign(memberForm, {
    id: undefined,
    catalogId: group.catalogId,
    groupId: group.id,
    sourceValue: '',
    displayName: '',
    sortOrder: group.members.length + 1,
    activeFrom: null,
    activeUntil: null,
    enabled: true,
    sourceReferenceId: null,
    remark: '',
  });
  memberDialogVisible.value = true;
}

function editMember(row: IssueScopeMemberResponse) {
  Object.assign(memberForm, row);
  memberDialogVisible.value = true;
}

async function saveGroup() {
  const displayName = groupForm.displayName.trim();
  if (!displayName) {
    ElMessage.warning(`请输入${groupNameLabel.value}`);
    return;
  }
  const payload: IssueScopeGroupSaveRequest = {
    ...groupForm,
    displayName,
    businessKey: groupForm.id ? groupForm.businessKey : createBusinessKey(displayName),
  };
  saving.value = true;
  try {
    if (groupForm.id) {
      await api.updateIssueScopeGroup(groupForm.id, payload);
    } else {
      await api.createIssueScopeGroup(payload);
    }
    groupDialogVisible.value = false;
    await loadData();
    ElMessage.success(`${groupNoun.value}已保存`);
  } finally {
    saving.value = false;
  }
}

async function saveMember() {
  const name = memberForm.sourceValue.trim();
  if (!name) {
    ElMessage.warning(`请输入${memberNameLabel.value}`);
    return;
  }
  const payload: IssueScopeMemberSaveRequest = {
    ...memberForm,
    sourceValue: name,
    displayName: name,
  };
  saving.value = true;
  try {
    if (memberForm.id) {
      await api.updateIssueScopeMember(memberForm.id, payload);
    } else {
      await api.createIssueScopeMember(payload);
    }
    memberDialogVisible.value = false;
    await loadData();
    ElMessage.success(`${memberNoun.value}已保存`);
  } finally {
    saving.value = false;
  }
}

async function toggleGroup(row: IssueScopeGroupResponse, value: boolean) {
  await api.setIssueScopeGroupEnabled(row.id, value);
  await loadData();
}

async function toggleMember(row: IssueScopeMemberResponse, value: boolean) {
  await api.setIssueScopeMemberEnabled(row.id, value);
  await loadData();
}

async function removeGroup(row: IssueScopeGroupResponse) {
  await ElMessageBox.confirm(
    `删除${groupNoun.value}“${row.displayName}”及其全部${memberNoun.value}？`,
    '确认删除',
    { type: 'warning' },
  );
  await api.deleteIssueScopeGroup(row.id);
  selectedGroupId.value = null;
  await loadData();
}

async function removeMember(row: IssueScopeMemberResponse) {
  await ElMessageBox.confirm(`删除${memberNoun.value}“${row.sourceValue}”？`, '确认删除', { type: 'warning' });
  await api.deleteIssueScopeMember(row.id);
  await loadData();
}

async function moveGroup(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= groups.value.length || !catalogId.value) {
    return;
  }
  const ids = groups.value.map((item) => item.id);
  [ids[index], ids[target]] = [ids[target]!, ids[index]!];
  await api.reorderIssueScopeGroups(catalogId.value, ids);
  await loadData();
}

async function moveMember(index: number, offset: number) {
  const group = selectedGroup.value;
  if (!group) {
    return;
  }
  const target = index + offset;
  if (target < 0 || target >= group.members.length) {
    return;
  }
  const ids = group.members.map((item) => item.id);
  [ids[index], ids[target]] = [ids[target]!, ids[index]!];
  await api.reorderIssueScopeMembers(group.id, ids);
  await loadData();
}
</script>

<template>
  <div class="scope-page">
    <el-card>
      <div class="toolbar">
        <el-select v-model="catalogId" placeholder="选择项目" class="catalog-select">
          <el-option
            v-for="item in catalogs"
            :key="item.id"
            :value="item.id"
            :label="`${item.projectName} / ${item.projectId} · ${item.dimensionName}`"
          />
        </el-select>
        <el-select v-model="enabled" class="status-select" @change="loadData">
          <el-option label="全部状态" value="" />
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-input
          v-model="keyword"
          clearable
          :placeholder="`搜索${groupNameLabel}`"
          class="search-input"
          @keyup.enter="loadData"
          @clear="loadData"
        />
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!catalogId" @click="newGroup">
          {{ addGroupLabel }}
        </el-button>
      </div>
    </el-card>

    <div class="layout">
      <el-card>
        <template #header><b>{{ groupListTitle }}</b></template>
        <div v-loading="loading" class="group-list">
          <div
            v-for="(row, index) in groups"
            :key="row.id"
            class="group-row"
            :class="{ selected: row.id === selectedGroupId }"
            @click="selectedGroupId = row.id"
          >
            <span class="order">{{ index + 1 }}</span>
            <span class="main">
              <b>{{ row.displayName }}</b>
              <small>{{ row.members.length }} 个{{ memberNoun }} · {{ row.issueCount }} 条议题</small>
            </span>
            <el-switch :model-value="row.enabled" @click.stop @change="toggleGroup(row, Boolean($event))" />
            <span class="row-actions" @click.stop>
              <el-button link :icon="ArrowUp" :disabled="index === 0" title="上移" @click="moveGroup(index, -1)" />
              <el-button link :icon="ArrowDown" :disabled="index === groups.length - 1" title="下移" @click="moveGroup(index, 1)" />
              <el-button link :icon="Edit" title="编辑" @click="editGroup(row)" />
              <el-button link type="danger" :icon="Delete" title="删除" @click="removeGroup(row)" />
            </span>
          </div>
          <el-empty v-if="!loading && !groups.length" :description="`尚未配置${groupNoun}`" />
        </div>
      </el-card>

      <el-card>
        <template #header>
          <div class="header">
            <b>{{ selectedGroup ? `${selectedGroup.displayName} · ${memberNameLabel}` : memberNameLabel }}</b>
            <el-button v-if="selectedGroup" link type="primary" :icon="Plus" @click="newMember">
              新增
            </el-button>
          </div>
        </template>
        <el-table v-if="selectedGroup" :data="selectedGroup.members" border>
          <el-table-column label="顺序" width="70">
            <template #default="{ $index }">{{ $index + 1 }}</template>
          </el-table-column>
          <el-table-column prop="sourceValue" :label="memberNameLabel" min-width="260" />
          <el-table-column prop="issueCount" label="议题数" width="90" />
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="toggleMember(row, Boolean($event))" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row, $index }">
              <el-button link :icon="ArrowUp" :disabled="$index === 0" title="上移" @click="moveMember($index, -1)" />
              <el-button link :icon="ArrowDown" :disabled="$index === selectedGroup.members.length - 1" title="下移" @click="moveMember($index, 1)" />
              <el-button link :icon="Edit" title="编辑" @click="editMember(row)" />
              <el-button link type="danger" :icon="Delete" title="删除" @click="removeMember(row)" />
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="`请选择左侧${groupNoun}`" />
      </el-card>
    </div>

    <el-dialog
      v-model="groupDialogVisible"
      :title="groupForm.id ? `编辑${groupNoun}` : addGroupLabel"
      width="480px"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="groupNameLabel" required>
          <el-input v-model="groupForm.displayName" maxlength="128" show-word-limit @keyup.enter="saveGroup" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="memberDialogVisible"
      :title="memberForm.id ? `编辑${memberNoun}` : `新增${memberNoun}`"
      width="480px"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item :label="memberNameLabel" required>
          <el-input v-model="memberForm.sourceValue" maxlength="255" show-word-limit @keyup.enter="saveMember" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMember">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.scope-page {
  display: grid;
  gap: 16px;
}

.toolbar,
.header,
.row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.toolbar {
  flex-wrap: wrap;
}

.header {
  justify-content: space-between;
}

.catalog-select {
  width: 300px;
}

.status-select {
  width: 120px;
}

.search-input {
  width: 280px;
}

.layout {
  display: grid;
  grid-template-columns: minmax(430px, 0.9fr) minmax(560px, 1.1fr);
  gap: 16px;
}

.group-list {
  display: grid;
  gap: 8px;
}

.group-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  cursor: pointer;
}

.group-row.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.order,
.main small {
  color: var(--el-text-color-secondary);
}

.main {
  display: grid;
  gap: 4px;
  min-width: 0;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
