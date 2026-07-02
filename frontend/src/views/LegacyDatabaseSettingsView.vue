<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Check, Connection, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { api } from '../api';
import PageStateShell from '../components/base/PageStateShell.vue';
import type {
  CodeReviewMatchModeDbSettingsResponse,
  CodeReviewMatchModeDbSettingsSaveRequest,
} from '../types/api';
import { formatBeijingDateTime } from '../utils/beijing-time';

// 兼容模式-MatchMode：该页面只维护短期老平台数据库连接，不属于 GitLab 镜像设置。
const initialized = ref(false);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const syncing = ref(false);
const settings = ref<CodeReviewMatchModeDbSettingsResponse | null>(null);

const form = reactive<CodeReviewMatchModeDbSettingsSaveRequest>({
  enabled: true,
  syncEnabled: true,
  mysqlHost: '172.22.10.72',
  mysqlPort: 3306,
  mysqlDatabase: 'gitlab_spider',
  mysqlUsername: 'root',
  mysqlPassword: '',
  mysqlTableName: 'spider_crowncad_data',
  mysqlFetchSize: 1000,
  mongoUri: '',
  mongoDatabase: 'spider',
  mongoAnnotationCollection: 'annotationRateInfo',
});

const statusTagType = computed(() => {
  const status = settings.value?.syncStatus;
  if (status === 'SUCCESS') {
    return 'success';
  }
  if (status === 'RUNNING') {
    return 'warning';
  }
  if (status === 'FAILED') {
    return 'danger';
  }
  return 'info';
});

const statusText = computed(() => {
  switch (settings.value?.syncStatus) {
    case 'SUCCESS':
      return '同步成功';
    case 'RUNNING':
      return '同步中';
    case 'FAILED':
      return '同步失败';
    default:
      return '未同步';
  }
});

const lastSyncTime = computed(() => formatDateTime(settings.value?.syncFinishedAt || settings.value?.syncStartedAt));
const updatedTime = computed(() => formatDateTime(settings.value?.updatedAt));

onMounted(async () => {
  await loadSettings();
});

async function loadSettings() {
  loading.value = true;
  try {
    applySettings(await api.getCodeReviewMatchModeDbSettings());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载数据库设置失败');
  } finally {
    initialized.value = true;
    loading.value = false;
  }
}

async function saveSettings() {
  saving.value = true;
  try {
    applySettings(await api.saveCodeReviewMatchModeDbSettings(buildPayload()));
    ElMessage.success('数据库设置已保存');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存数据库设置失败');
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  try {
    const result = await api.testCodeReviewMatchModeDbConnection(buildPayload());
    if (result.success) {
      ElMessage.success(`${result.message}，当前表 ${result.recordCount} 条`);
    } else {
      ElMessage.warning(result.message || '老平台 MySQL 连接失败');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试连接失败');
  } finally {
    testing.value = false;
  }
}

async function syncNow() {
  syncing.value = true;
  try {
    const result = await api.syncCodeReviewMatchModeDbNow();
    ElMessage.success(result.message || '兼容模式同步完成');
    applySettings(await api.getCodeReviewMatchModeDbSettings());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '立即同步失败');
  } finally {
    syncing.value = false;
  }
}

function applySettings(nextSettings: CodeReviewMatchModeDbSettingsResponse) {
  settings.value = nextSettings;
  form.enabled = nextSettings.enabled;
  form.syncEnabled = nextSettings.syncEnabled;
  form.mysqlHost = nextSettings.mysqlHost || '172.22.10.72';
  form.mysqlPort = nextSettings.mysqlPort || 3306;
  form.mysqlDatabase = nextSettings.mysqlDatabase || '';
  form.mysqlUsername = nextSettings.mysqlUsername || '';
  form.mysqlPassword = '';
  form.mysqlTableName = nextSettings.mysqlTableName || 'spider_crowncad_data';
  form.mysqlFetchSize = nextSettings.mysqlFetchSize || 1000;
  form.mongoUri = '';
  form.mongoDatabase = nextSettings.mongoDatabase || '';
  form.mongoAnnotationCollection = nextSettings.mongoAnnotationCollection || 'annotationRateInfo';
}

function buildPayload(): CodeReviewMatchModeDbSettingsSaveRequest {
  return {
    enabled: form.enabled,
    syncEnabled: form.syncEnabled,
    mysqlHost: form.mysqlHost.trim(),
    mysqlPort: Number(form.mysqlPort || 3306),
    mysqlDatabase: form.mysqlDatabase.trim(),
    mysqlUsername: form.mysqlUsername.trim(),
    mysqlPassword: form.mysqlPassword?.trim() || null,
    mysqlTableName: form.mysqlTableName.trim() || 'spider_crowncad_data',
    mysqlFetchSize: Number(form.mysqlFetchSize || 1000),
    mongoUri: form.mongoUri?.trim() || null,
    mongoDatabase: form.mongoDatabase?.trim() || null,
    mongoAnnotationCollection: form.mongoAnnotationCollection.trim() || 'annotationRateInfo',
  };
}

function formatDateTime(value?: string | null) {
  return value ? formatBeijingDateTime(value, '-') : '-';
}
</script>

<template>
  <PageStateShell :ready="initialized">
    <div class="legacy-db-page">
      <el-card shadow="never" class="panel-card legacy-db-status-card">
        <template #header>
          <div class="legacy-db-card-header">
            <div class="legacy-db-card-title">数据库设置</div>
            <el-tag :type="statusTagType" effect="plain" round>{{ statusText }}</el-tag>
          </div>
        </template>

        <div class="legacy-db-status-grid">
          <div class="legacy-db-status-item">
            <span>兼容模式</span>
            <strong>{{ form.enabled ? '开启' : '关闭' }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>自动同步</span>
            <strong>{{ form.syncEnabled ? '开启' : '关闭' }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>兼容表记录数</span>
            <strong>{{ settings?.syncRecordCount ?? 0 }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>最近同步</span>
            <strong>{{ lastSyncTime }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>配置更新时间</span>
            <strong>{{ updatedTime }}</strong>
          </div>
        </div>

        <el-alert
          v-if="settings?.syncMessage"
          class="legacy-db-message"
          :type="settings.syncStatus === 'FAILED' ? 'error' : 'info'"
          :closable="false"
          show-icon
          :title="settings.syncMessage"
        />
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="legacy-db-card-header">
            <div class="legacy-db-card-title">代码走查兼容数据源</div>
            <el-button :icon="Refresh" :loading="loading" @click="loadSettings">刷新</el-button>
          </div>
        </template>

        <el-form v-loading="loading" label-width="148px" class="legacy-db-form">
          <el-form-item label="兼容模式">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="每小时自动同步">
            <el-switch v-model="form.syncEnabled" :disabled="!form.enabled" />
          </el-form-item>

          <el-divider>MySQL</el-divider>

          <div class="legacy-db-form-grid">
            <el-form-item label="主机">
              <el-input v-model="form.mysqlHost" placeholder="172.22.10.72" />
            </el-form-item>
            <el-form-item label="端口">
              <el-input-number v-model="form.mysqlPort" :min="1" :max="65535" controls-position="right" />
            </el-form-item>
            <el-form-item label="数据库">
              <el-input v-model="form.mysqlDatabase" />
            </el-form-item>
            <el-form-item label="用户名">
              <el-input v-model="form.mysqlUsername" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="form.mysqlPassword"
                type="password"
                show-password
                :placeholder="settings?.mysqlPasswordConfigured ? '已配置，留空不修改' : ''"
              />
            </el-form-item>
            <el-form-item label="数据表">
              <el-input v-model="form.mysqlTableName" />
            </el-form-item>
            <el-form-item label="抓取批量">
              <el-input-number
                v-model="form.mysqlFetchSize"
                :min="1"
                :max="100000"
                controls-position="right"
              />
            </el-form-item>
          </div>

          <el-divider>MongoDB 注释率</el-divider>

          <div class="legacy-db-form-grid">
            <el-form-item label="连接 URI">
              <el-input
                v-model="form.mongoUri"
                type="password"
                show-password
                :placeholder="settings?.mongoUriConfigured ? '已配置，留空不修改' : '可选'"
              />
            </el-form-item>
            <el-form-item label="数据库">
              <el-input v-model="form.mongoDatabase" placeholder="可选" />
            </el-form-item>
            <el-form-item label="集合">
              <el-input v-model="form.mongoAnnotationCollection" />
            </el-form-item>
          </div>

          <div class="legacy-db-actions">
            <el-button type="primary" :icon="Check" :loading="saving" @click="saveSettings">保存设置</el-button>
            <el-button :icon="Connection" :loading="testing" @click="testConnection">测试连接</el-button>
            <el-button
              :icon="Refresh"
              :loading="syncing"
              :disabled="!form.enabled || !form.syncEnabled"
              @click="syncNow"
            >
              立即同步
            </el-button>
          </div>
        </el-form>
      </el-card>
    </div>
  </PageStateShell>
</template>

<style scoped>
.legacy-db-page {
  display: grid;
  gap: 12px;
}

.legacy-db-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.legacy-db-card-title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.legacy-db-status-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(140px, 1fr));
  gap: 10px;
}

.legacy-db-status-item {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.legacy-db-status-item span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.legacy-db-status-item strong {
  min-width: 0;
  color: var(--el-text-color-primary);
  font-size: 14px;
  word-break: break-word;
}

.legacy-db-message {
  margin-top: 12px;
}

.legacy-db-form {
  max-width: 1180px;
}

.legacy-db-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(260px, 1fr));
  column-gap: 18px;
}

.legacy-db-form-grid :deep(.el-input-number) {
  width: 100%;
}

.legacy-db-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-left: 148px;
}

@media (max-width: 1180px) {
  .legacy-db-status-grid {
    grid-template-columns: repeat(2, minmax(160px, 1fr));
  }
}

@media (max-width: 760px) {
  .legacy-db-form-grid,
  .legacy-db-status-grid {
    grid-template-columns: 1fr;
  }

  .legacy-db-actions {
    padding-left: 0;
  }
}
</style>
