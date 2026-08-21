<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Check, Refresh, RefreshLeft } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import TableFunctionBar from '../components/base/TableFunctionBar.vue';
import {
  permissionSettingsApi,
  type PermissionCatalogItem,
  type PermissionRole,
  type PermissionSettingsResponse,
} from '../api-client/permission-settings-api';
import { loadCurrentUser } from '../composables/auth-state';
import { getErrorMessage } from '../utils/user-message';

const loading = ref(false);
const saving = ref(false);
const restoring = ref(false);
const settings = ref<PermissionSettingsResponse>({
  permissions: [],
  roles: [],
  initialLdapSyncCompleted: false,
});
const selectedRoleCode = ref('');
const draftPermissionCodes = ref<string[]>([]);

const selectedRole = computed<PermissionRole | null>(() =>
  settings.value.roles.find((role) => role.roleCode === selectedRoleCode.value) ?? null,
);

const orderedRoles = computed(() => [...settings.value.roles].sort((left, right) => {
  const leftOrder = left.displayOrder ?? Number.MAX_SAFE_INTEGER;
  const rightOrder = right.displayOrder ?? Number.MAX_SAFE_INTEGER;
  return leftOrder - rightOrder || left.roleName.localeCompare(right.roleName, 'zh-CN');
}));

const permissionGroups = computed(() => {
  const groups = new Map<string, PermissionCatalogItem[]>();
  for (const permission of settings.value.permissions) {
    const items = groups.get(permission.moduleName) ?? [];
    items.push(permission);
    groups.set(permission.moduleName, items);
  }
  return Array.from(groups, ([moduleName, permissions]) => ({ moduleName, permissions }));
});

function selectRole(role: PermissionRole) {
  selectedRoleCode.value = role.roleCode;
  draftPermissionCodes.value = [...role.permissionCodes];
}

function moduleSelectedCount(permissions: PermissionCatalogItem[]) {
  return permissions.filter((permission) => draftPermissionCodes.value.includes(permission.permissionCode)).length;
}

function setModulePermissions(permissions: PermissionCatalogItem[], enabled: boolean) {
  const moduleCodes = new Set(permissions.map((permission) => permission.permissionCode));
  const next = new Set(draftPermissionCodes.value);
  for (const code of moduleCodes) {
    if (enabled) {
      next.add(code);
    } else {
      next.delete(code);
    }
  }
  draftPermissionCodes.value = Array.from(next);
}

async function loadSettings(preferredRoleCode = selectedRoleCode.value) {
  loading.value = true;
  try {
    settings.value = await permissionSettingsApi.load();
    const role = settings.value.roles.find((item) => item.roleCode === preferredRoleCode)
      ?? orderedRoles.value[0];
    if (role) {
      selectRole(role);
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '权限设置加载失败'));
  } finally {
    loading.value = false;
  }
}

async function savePermissions() {
  const role = selectedRole.value;
  if (!role || saving.value || restoring.value) {
    return;
  }
  saving.value = true;
  try {
    settings.value = await permissionSettingsApi.saveRole(role.roleCode, draftPermissionCodes.value);
    const refreshedRole = settings.value.roles.find((item) => item.roleCode === role.roleCode);
    if (refreshedRole) {
      selectRole(refreshedRole);
    }
    await loadCurrentUser();
    ElMessage.success('角色权限已保存');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '角色权限保存失败'));
  } finally {
    saving.value = false;
  }
}

async function restoreDefaults() {
  if (restoring.value || saving.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      '恢复默认设置会覆盖五个 LDAP 角色当前的本地权限配置，是否继续？',
      '恢复默认设置',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }

  restoring.value = true;
  try {
    settings.value = await permissionSettingsApi.restoreDefaults();
    const role = settings.value.roles.find((item) => item.roleCode === selectedRoleCode.value)
      ?? orderedRoles.value[0];
    if (role) {
      selectRole(role);
    }
    await loadCurrentUser();
    ElMessage.success('已恢复默认权限');
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '恢复默认权限失败'));
  } finally {
    restoring.value = false;
  }
}

onMounted(() => loadSettings());
</script>

<template>
  <section class="permission-page" v-loading="loading">
    <el-card class="panel-card permission-toolbar-card">
      <TableFunctionBar>
        <template #status>
          <el-tag :type="settings.initialLdapSyncCompleted ? 'success' : 'warning'" effect="plain">
            {{ settings.initialLdapSyncCompleted ? 'LDAP 用户已完成首次同步' : 'LDAP 用户尚未完成首次同步' }}
          </el-tag>
        </template>
        <template #actions>
          <el-button :icon="Refresh" plain :loading="loading" @click="loadSettings()">刷新</el-button>
          <el-button :icon="RefreshLeft" plain :loading="restoring" :disabled="saving" @click="restoreDefaults">
            恢复默认设置
          </el-button>
          <el-button type="primary" :icon="Check" :loading="saving" :disabled="!selectedRole || restoring" @click="savePermissions">
            保存权限
          </el-button>
        </template>
      </TableFunctionBar>
    </el-card>

    <div class="permission-workspace">
      <el-card class="panel-card role-panel">
        <template #header>
          <div class="panel-title">角色</div>
        </template>
        <aside class="role-list" aria-label="角色列表">
          <button
            v-for="role in orderedRoles"
            :key="role.roleCode"
            type="button"
            class="role-option"
            :class="{ active: role.roleCode === selectedRoleCode }"
            @click="selectRole(role)"
          >
            <span>{{ role.roleName }}</span>
            <small>{{ role.permissionCodes.length }} 项权限</small>
          </button>
        </aside>
      </el-card>

      <el-card v-if="selectedRole" class="panel-card permission-editor">
        <template #header>
          <div class="permission-editor-title">
            <h3>{{ selectedRole.roleName }}</h3>
            <el-tag size="small" effect="plain">当前选择 {{ draftPermissionCodes.length }} 项权限</el-tag>
          </div>
        </template>

        <el-checkbox-group v-model="draftPermissionCodes" class="permission-groups">
          <section v-for="group in permissionGroups" :key="group.moduleName" class="permission-group">
            <header class="permission-group-header">
              <div>
                <h4>{{ group.moduleName }}</h4>
                <span>{{ moduleSelectedCount(group.permissions) }} / {{ group.permissions.length }}</span>
              </div>
              <div>
                <el-button text @click="setModulePermissions(group.permissions, true)">全选</el-button>
                <el-button text @click="setModulePermissions(group.permissions, false)">清空</el-button>
              </div>
            </header>
            <div class="permission-grid">
              <el-checkbox
                v-for="permission in group.permissions"
                :key="permission.permissionCode"
                :value="permission.permissionCode"
                class="permission-option"
              >
                <span class="permission-name">{{ permission.permissionName }}</span>
                <small>{{ permission.description }}</small>
              </el-checkbox>
            </div>
          </section>
        </el-checkbox-group>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.permission-page {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.permission-editor h3,
.permission-group h4 {
  margin: 0;
  letter-spacing: 0;
}

.permission-group-header span {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.permission-toolbar-card {
  min-width: 0;
}

.permission-workspace {
  display: grid;
  grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}

.role-list {
  display: grid;
  gap: 8px;
}

.role-panel,
.permission-editor {
  min-width: 0;
}

.panel-title {
  font-weight: 600;
}

.role-option {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
}

.role-option:hover,
.role-option.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-fill-color-light);
}

.role-option.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.role-option span {
  font-weight: 600;
}

.role-option small {
  color: var(--el-text-color-secondary);
}

.permission-editor {
  overflow: hidden;
}

.permission-editor-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.permission-editor h3 {
  font-size: 18px;
}

.permission-groups {
  display: grid;
  gap: 20px;
  max-height: calc(100vh - 230px);
  padding-right: 2px;
  overflow: auto;
}

.permission-group {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}

.permission-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.permission-group-header > div:first-child {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.permission-group h4 {
  font-size: 15px;
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(200px, 1fr));
  gap: 8px 12px;
}

.permission-option {
  align-items: flex-start;
  min-height: 58px;
  height: auto;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.permission-option :deep(.el-checkbox__label) {
  display: grid;
  gap: 3px;
  min-width: 0;
  white-space: normal;
}

.permission-name {
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.permission-option small {
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

@media (max-width: 1000px) {
  .permission-workspace {
    grid-template-columns: 1fr;
  }

  .role-list {
    grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  }

  .permission-grid {
    grid-template-columns: repeat(2, minmax(180px, 1fr));
  }
}

@media (max-width: 640px) {
  .permission-groups {
    max-height: none;
    overflow: visible;
  }

  .permission-grid {
    grid-template-columns: 1fr;
  }
}
</style>
