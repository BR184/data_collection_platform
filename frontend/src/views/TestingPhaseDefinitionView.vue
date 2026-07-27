<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ArrowDown, ArrowUp, Delete, Edit, Plus, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type { IssueScopeCatalogResponse, IssueScopeDiscoveredValueResponse, IssueScopeGroupResponse, IssueScopeGroupSaveRequest, IssueScopeMemberResponse, IssueScopeMemberSaveRequest } from '../types/api';

const loading = ref(false);
const saving = ref(false);
const catalogs = ref<IssueScopeCatalogResponse[]>([]);
const catalogId = ref<number | null>(null);
const groups = ref<IssueScopeGroupResponse[]>([]);
const unassigned = ref<IssueScopeDiscoveredValueResponse[]>([]);
const selectedGroupId = ref<number | null>(null);
const keyword = ref('');
const enabled = ref<string>('');
const groupDialog = ref(false);
const memberDialog = ref(false);
const groupForm = reactive<IssueScopeGroupSaveRequest & { id?: number }>({ catalogId: 0, businessKey: '', displayName: '', sortOrder: 0, enabled: true, remark: '' });
const memberForm = reactive<IssueScopeMemberSaveRequest & { id?: number }>({ catalogId: 0, groupId: 0, sourceValue: '', displayName: '', sortOrder: 0, activeFrom: null, activeUntil: null, enabled: true, sourceReferenceId: null, remark: '' });

const catalog = computed(() => catalogs.value.find((item) => item.id === catalogId.value) ?? null);
const selectedGroup = computed(() => groups.value.find((item) => item.id === selectedGroupId.value) ?? null);
const memberLabel = computed(() => catalog.value?.dimension === 'MILESTONE' ? '匹配里程碑' : '精确测试阶段');

onMounted(loadCatalogs);
watch(catalogId, () => { selectedGroupId.value = null; void loadData(); });

async function loadCatalogs() {
  catalogs.value = await api.getIssueScopeCatalogs();
  if (!catalogId.value || !catalogs.value.some((item) => item.id === catalogId.value)) catalogId.value = catalogs.value[0]?.id ?? null;
}
async function loadData() {
  if (!catalogId.value) { groups.value = []; unassigned.value = []; return; }
  loading.value = true;
  try {
    [groups.value, unassigned.value] = await Promise.all([
      api.getIssueScopeGroups(catalogId.value, { keyword: keyword.value, enabled: enabled.value }),
      api.getUnassignedIssueScopeValues(catalogId.value),
    ]);
  } finally { loading.value = false; }
}
function newGroup() { Object.assign(groupForm, { id: undefined, catalogId: catalogId.value ?? 0, businessKey: '', displayName: '', sortOrder: groups.value.length + 1, enabled: true, remark: '' }); groupDialog.value = true; }
function editGroup(row: IssueScopeGroupResponse) { Object.assign(groupForm, { id: row.id, catalogId: row.catalogId, businessKey: row.businessKey, displayName: row.displayName, sortOrder: row.sortOrder, enabled: row.enabled, remark: row.remark }); groupDialog.value = true; }
function newMember(value = '') { const group = selectedGroup.value; if (!group) return; Object.assign(memberForm, { id: undefined, catalogId: group.catalogId, groupId: group.id, sourceValue: value, displayName: value, sortOrder: group.members.length + 1, activeFrom: null, activeUntil: null, enabled: true, sourceReferenceId: null, remark: '' }); memberDialog.value = true; }
function editMember(row: IssueScopeMemberResponse) { Object.assign(memberForm, row); memberDialog.value = true; }
async function saveGroup() {
  saving.value = true;
  try {
    if (groupForm.id) await api.updateIssueScopeGroup(groupForm.id, groupForm);
    else await api.createIssueScopeGroup(groupForm);
    groupDialog.value = false;
    await loadData();
    ElMessage.success('议题范围已保存');
  } finally { saving.value = false; }
}
async function saveMember() {
  saving.value = true;
  try {
    if (memberForm.id) await api.updateIssueScopeMember(memberForm.id, memberForm);
    else await api.createIssueScopeMember(memberForm);
    memberDialog.value = false;
    await loadData();
    ElMessage.success('精确匹配值已保存');
  } finally { saving.value = false; }
}
async function toggleGroup(row: IssueScopeGroupResponse, value: boolean) { await api.setIssueScopeGroupEnabled(row.id, value); await loadData(); }
async function toggleMember(row: IssueScopeMemberResponse, value: boolean) { await api.setIssueScopeMemberEnabled(row.id, value); await loadData(); }
async function removeGroup(row: IssueScopeGroupResponse) { await ElMessageBox.confirm(`删除范围“${row.displayName}”及其全部匹配值？`, '确认删除', { type: 'warning' }); await api.deleteIssueScopeGroup(row.id); selectedGroupId.value = null; await loadData(); }
async function removeMember(row: IssueScopeMemberResponse) { await ElMessageBox.confirm(`删除匹配值“${row.sourceValue}”？`, '确认删除', { type: 'warning' }); await api.deleteIssueScopeMember(row.id); await loadData(); }
async function moveGroup(index: number, offset: number) { const target = index + offset; if (target < 0 || target >= groups.value.length || !catalogId.value) return; const ids = groups.value.map((item) => item.id); [ids[index], ids[target]] = [ids[target]!, ids[index]!]; await api.reorderIssueScopeGroups(catalogId.value, ids); await loadData(); }
async function moveMember(index: number, offset: number) { const group = selectedGroup.value; if (!group) return; const target = index + offset; if (target < 0 || target >= group.members.length) return; const ids = group.members.map((item) => item.id); [ids[index], ids[target]] = [ids[target]!, ids[index]!]; await api.reorderIssueScopeMembers(group.id, ids); await loadData(); }
</script>

<template>
  <div class="scope-page">
    <el-card>
      <div class="toolbar">
        <el-select v-model="catalogId" placeholder="选择范围目录" style="width: 300px">
          <el-option v-for="item in catalogs" :key="item.id" :value="item.id" :label="`${item.projectName} / ${item.projectId} · ${item.dimensionName}`" />
        </el-select>
        <el-select v-model="enabled" style="width: 120px" @change="loadData"><el-option label="全部状态" value="" /><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select>
        <el-input v-model="keyword" clearable placeholder="搜索业务键、显示名或匹配值" style="width: 280px" @keyup.enter="loadData" @clear="loadData" />
        <el-button :icon="Refresh" :loading="loading" @click="loadData">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!catalogId" @click="newGroup">新增范围</el-button>
      </div>
      <el-alert v-if="catalog" :closable="false" type="info" show-icon :title="`页面使用稳定业务键；${catalog.dimensionName}按精确事实值匹配。拖动顺序的第一项是该项目默认范围。`" />
    </el-card>
    <div class="layout">
      <el-card><template #header><b>范围与默认顺序</b></template>
        <div v-loading="loading" class="group-list">
          <div v-for="(row, index) in groups" :key="row.id" class="group-row" :class="{ selected: row.id === selectedGroupId }" @click="selectedGroupId = row.id">
            <span class="order">{{ index + 1 }}</span><span class="main"><b>{{ row.displayName }}</b><small>{{ row.businessKey }} · {{ row.members.length }} 个匹配值 · {{ row.issueCount }} 条议题</small></span>
            <el-switch :model-value="row.enabled" @click.stop @change="toggleGroup(row, Boolean($event))" />
            <span @click.stop><el-button link :icon="ArrowUp" :disabled="index === 0" @click="moveGroup(index, -1)" /><el-button link :icon="ArrowDown" :disabled="index === groups.length - 1" @click="moveGroup(index, 1)" /><el-button link :icon="Edit" @click="editGroup(row)" /><el-button link type="danger" :icon="Delete" @click="removeGroup(row)" /></span>
          </div><el-empty v-if="!loading && !groups.length" description="该目录尚未配置范围" />
        </div>
      </el-card>
      <el-card><template #header><div class="header"><b>{{ selectedGroup ? `${selectedGroup.displayName} · ${memberLabel}` : memberLabel }}</b><el-button v-if="selectedGroup" link type="primary" :icon="Plus" @click="newMember()">新增</el-button></div></template>
        <el-table v-if="selectedGroup" :data="selectedGroup.members" border>
          <el-table-column label="顺序" width="70"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column><el-table-column prop="sourceValue" label="精确事实值" min-width="190" /><el-table-column prop="displayName" label="显示名" min-width="150" /><el-table-column prop="issueCount" label="议题数" width="80" />
          <el-table-column label="状态" width="75"><template #default="{ row }"><el-switch :model-value="row.enabled" @change="toggleMember(row, Boolean($event))" /></template></el-table-column>
          <el-table-column label="操作" width="180"><template #default="{ row, $index }"><el-button link :icon="ArrowUp" :disabled="$index === 0" @click="moveMember($index, -1)" /><el-button link :icon="ArrowDown" :disabled="$index === selectedGroup.members.length - 1" @click="moveMember($index, 1)" /><el-button link :icon="Edit" @click="editMember(row)" /><el-button link type="danger" :icon="Delete" @click="removeMember(row)" /></template></el-table-column>
        </el-table><el-empty v-else description="请选择左侧范围" />
      </el-card>
    </div>
    <el-card><template #header><b>未纳入目录的事实值（{{ unassigned.length }}）</b></template><div class="chips"><el-tag v-for="item in unassigned" :key="item.value" class="chip" effect="plain" @click="selectedGroup && newMember(item.value)">{{ item.value }}（{{ item.issueCount }}）</el-tag><el-empty v-if="!unassigned.length" description="没有未纳入值" /></div></el-card>

    <el-dialog v-model="groupDialog" :title="groupForm.id ? '编辑议题范围' : '新增议题范围'" width="520px"><el-form label-position="top"><el-form-item label="稳定业务键" required><el-input v-model="groupForm.businessKey" :disabled="Boolean(groupForm.id)" /></el-form-item><el-form-item label="显示名称" required><el-input v-model="groupForm.displayName" /></el-form-item><el-form-item label="备注"><el-input v-model="groupForm.remark" type="textarea" /></el-form-item><el-form-item label="状态"><el-switch v-model="groupForm.enabled" /></el-form-item></el-form><template #footer><el-button @click="groupDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button></template></el-dialog>
    <el-dialog v-model="memberDialog" :title="memberForm.id ? `编辑${memberLabel}` : `新增${memberLabel}`" width="620px"><el-form label-position="top"><el-form-item label="精确事实值" required><el-input v-model="memberForm.sourceValue" /></el-form-item><el-form-item label="显示名称" required><el-input v-model="memberForm.displayName" /></el-form-item><div class="form-grid"><el-form-item label="生效时间"><el-date-picker v-model="memberForm.activeFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item label="截止时间"><el-date-picker v-model="memberForm.activeUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></div><el-form-item label="备注"><el-input v-model="memberForm.remark" type="textarea" /></el-form-item><el-form-item label="状态"><el-switch v-model="memberForm.enabled" /></el-form-item></el-form><template #footer><el-button @click="memberDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveMember">保存</el-button></template></el-dialog>
  </div>
</template>

<style scoped>
.scope-page{display:grid;gap:16px}.toolbar,.header{display:flex;align-items:center;gap:10px}.toolbar{flex-wrap:wrap;margin-bottom:14px}.header{justify-content:space-between}.layout{display:grid;grid-template-columns:minmax(430px,.9fr) minmax(560px,1.1fr);gap:16px}.group-list{display:grid;gap:8px}.group-row{display:grid;grid-template-columns:38px minmax(0,1fr) auto auto;align-items:center;gap:8px;padding:10px;border:1px solid var(--el-border-color);border-radius:6px;cursor:pointer}.group-row.selected{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.order{color:var(--el-text-color-secondary)}.main{display:grid;gap:4px;min-width:0}.main small{color:var(--el-text-color-secondary)}.chips{display:flex;flex-wrap:wrap;gap:8px}.chip{cursor:pointer}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}@media(max-width:1100px){.layout{grid-template-columns:1fr}}
</style>
