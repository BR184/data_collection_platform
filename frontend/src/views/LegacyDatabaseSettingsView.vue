<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Check, Connection, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from '../element-plus-services';
import { api } from '../api';
import PageStateShell from '../components/base/PageStateShell.vue';
import SmartSelect from '../components/base/SmartSelect.vue';
import type {
  CodeReviewMatchModeCollectionOptionResponse,
  CodeReviewMatchModeDbSettingsResponse,
  CodeReviewMatchModeDbSettingsSaveRequest,
  CodeReviewMatchModeTableOptionResponse,
} from '../types/api';
import type { RecordTableFilterOption } from '../types/record-table';
import { formatBeijingDateTime } from '../utils/beijing-time';

// 兼容模式-MatchMode：该页面只维护短期老平台数据库连接，不属于 GitLab 镜像设置。
const defaultSelectedTableNames = ['spider_crowncad_data'];
const defaultSelectedMongoCollectionNames = ['reviewReport', 'problemDetail'];

const initialized = ref(false);
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const mongoTesting = ref(false);
const syncing = ref(false);
const mongoSyncing = ref(false);
const formalImporting = ref(false);
const tableOptionsLoading = ref(false);
const tableOptionsLoaded = ref(false);
const tableOptions = ref<CodeReviewMatchModeTableOptionResponse[]>([]);
const mongoCollectionOptionsLoading = ref(false);
const mongoCollectionOptionsLoaded = ref(false);
const mongoCollectionOptions = ref<CodeReviewMatchModeCollectionOptionResponse[]>([]);
const settings = ref<CodeReviewMatchModeDbSettingsResponse | null>(null);
const formalImportConfirmationText = '我确认要将数据源导入新采集平台中';
const formalImportConfirmation = ref('');
const formalImportSelection = ref<Array<'review' | 'codeReview'>>(['review', 'codeReview']);

const form = reactive<CodeReviewMatchModeDbSettingsSaveRequest>({
  enabled: true,
  syncEnabled: true,
  mysqlHost: '172.22.10.72',
  mysqlPort: 3306,
  mysqlDatabase: 'gitlab_spider',
  dgmMysqlDatabase: 'gitlab_spider_dgm',
  mysqlUsername: 'root',
  mysqlPassword: '',
  mysqlTableName: 'spider_crowncad_data',
  legacyApiBaseUrl: 'http://172.22.10.72:8091',
  dgmLegacyApiBaseUrl: '',
  selectedTableNames: [...defaultSelectedTableNames],
  mysqlFetchSize: 1000,
  mongoUri: '',
  mongoDatabase: 'spider',
  selectedMongoCollectionNames: [...defaultSelectedMongoCollectionNames],
  reviewReportCollectionName: 'reviewReport',
  reviewProblemCollectionName: 'problemDetail',
  reviewDataReadMode: 'compatibility',
  codeReviewReadMode: 'compatibility',
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
const reviewDataReadModeText = computed(() => readModeText(form.reviewDataReadMode));
const codeReviewReadModeText = computed(() => readModeText(form.codeReviewReadMode));
const formalImportDisabled = computed(
  () =>
    formalImportSelection.value.length === 0 ||
    formalImportConfirmation.value.trim() !== formalImportConfirmationText,
);
const selectedImportScopeText = computed(() => {
  const scopes: string[] = [];
  if (form.selectedTableNames.length > 0) {
    scopes.push(`MySQL：${form.selectedTableNames.join('、')}`);
  }
  if (form.selectedMongoCollectionNames.length > 0) {
    scopes.push(`MongoDB：${form.selectedMongoCollectionNames.join('、')}`);
  }
  return scopes.length > 0 ? scopes.join('；') : '未选择';
});
const tableSelectOptions = computed<RecordTableFilterOption[]>(() => {
  const known = new Set<string>();
  const options = tableOptions.value.map((option) => {
    known.add(option.tableName);
    return {
      label: option.label || option.tableName,
      value: option.tableName,
    };
  });
  for (const tableName of form.selectedTableNames) {
    if (!known.has(tableName)) {
      options.push({ label: tableName, value: tableName });
    }
  }
  return options;
});
const mongoCollectionSelectOptions = computed<RecordTableFilterOption[]>(() => {
  const known = new Set<string>();
  const options = mongoCollectionOptions.value.map((option) => {
    known.add(option.collectionName);
    return {
      label: option.label || option.collectionName,
      value: option.collectionName,
    };
  });
  for (const collectionName of form.selectedMongoCollectionNames) {
    if (!known.has(collectionName)) {
      options.push({ label: collectionName, value: collectionName });
    }
  }
  return options;
});

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
      ElMessage.success(result.message);
    } else {
      ElMessage.warning(result.message || '老平台 MySQL 连接失败');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试 MySQL 连接失败');
  } finally {
    testing.value = false;
  }
}

async function testMongoConnection() {
  mongoTesting.value = true;
  try {
    const result = await api.testCodeReviewMatchModeMongoConnection(buildPayload());
    if (result.success) {
      ElMessage.success(result.message);
    } else {
      ElMessage.warning(result.message || '老平台 MongoDB 连接失败');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试 MongoDB 连接失败');
  } finally {
    mongoTesting.value = false;
  }
}

async function syncMysqlNow() {
  syncing.value = true;
  try {
    const result = await api.syncCodeReviewMatchModeDbNow();
    ElMessage.success(result.message || '兼容模式代码走查数据导入完成');
    applySettings(await api.getCodeReviewMatchModeDbSettings());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入代码走查数据失败');
  } finally {
    syncing.value = false;
  }
}

async function syncMongoReviewNow() {
  mongoSyncing.value = true;
  try {
    const result = await api.syncCodeReviewMatchModeMongoNow(buildPayload());
    ElMessage.success(result.message || '兼容模式评审数据导入完成');
    applySettings(await api.getCodeReviewMatchModeDbSettings());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入评审数据失败');
  } finally {
    mongoSyncing.value = false;
  }
}

async function importLegacyPlatformToFormal() {
  formalImporting.value = true;
  try {
    const result = await api.importLegacyPlatformToFormal({
      importReviewData: formalImportSelection.value.includes('review'),
      importCodeReviewData: formalImportSelection.value.includes('codeReview'),
      confirmationText: formalImportConfirmation.value.trim(),
    });
    ElMessage.success(
      `${result.message}；评审新增 ${result.reviewInsertedCount}、更新 ${result.reviewUpdatedCount}；代码走查新增 ${result.codeReviewInsertedCount}、更新 ${result.codeReviewUpdatedCount}`,
    );
    formalImportConfirmation.value = '';
    applySettings(await api.getCodeReviewMatchModeDbSettings());
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '老平台数据转正式失败');
  } finally {
    formalImporting.value = false;
  }
}

async function ensureTableOptions(force = false) {
  if (tableOptionsLoading.value) {
    return;
  }
  if (!force && tableOptionsLoaded.value) {
    return;
  }
  tableOptionsLoading.value = true;
  try {
    tableOptions.value = await api.getCodeReviewMatchModeTableOptions(buildPayload());
    tableOptionsLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载老平台 MySQL 表列表失败');
  } finally {
    tableOptionsLoading.value = false;
  }
}

async function ensureMongoCollectionOptions(force = false) {
  if (mongoCollectionOptionsLoading.value) {
    return;
  }
  if (!force && mongoCollectionOptionsLoaded.value) {
    return;
  }
  mongoCollectionOptionsLoading.value = true;
  try {
    mongoCollectionOptions.value = await api.getCodeReviewMatchModeMongoCollectionOptions(buildPayload());
    mongoCollectionOptionsLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载老平台 MongoDB 集合列表失败');
  } finally {
    mongoCollectionOptionsLoading.value = false;
  }
}

function handleTableSelectVisibleChange(visible: boolean) {
  if (visible) {
    void ensureTableOptions();
  }
}

function handleMongoCollectionSelectVisibleChange(visible: boolean) {
  if (visible) {
    void ensureMongoCollectionOptions();
  }
}

function applySettings(nextSettings: CodeReviewMatchModeDbSettingsResponse) {
  settings.value = nextSettings;
  form.enabled = nextSettings.enabled;
  form.syncEnabled = nextSettings.syncEnabled;
  form.mysqlHost = nextSettings.mysqlHost || '172.22.10.72';
  form.mysqlPort = nextSettings.mysqlPort || 3306;
  form.mysqlDatabase = nextSettings.mysqlDatabase || '';
  form.dgmMysqlDatabase = nextSettings.dgmMysqlDatabase || 'gitlab_spider_dgm';
  form.mysqlUsername = nextSettings.mysqlUsername || '';
  form.mysqlPassword = '';
  form.mysqlTableName = nextSettings.mysqlTableName || 'spider_crowncad_data';
  form.legacyApiBaseUrl = nextSettings.legacyApiBaseUrl || legacyApiDefaultBaseUrl();
  form.dgmLegacyApiBaseUrl = nextSettings.dgmLegacyApiBaseUrl || '';
  form.selectedTableNames = normalizeSelectedTableNames(nextSettings.selectedTableNames);
  form.mysqlFetchSize = nextSettings.mysqlFetchSize || 1000;
  form.mongoUri = '';
  form.mongoDatabase = nextSettings.mongoDatabase || 'spider';
  form.selectedMongoCollectionNames = normalizeSelectedMongoCollectionNames(
    nextSettings.selectedMongoCollectionNames,
  );
  form.reviewReportCollectionName = nextSettings.reviewReportCollectionName || 'reviewReport';
  form.reviewProblemCollectionName = nextSettings.reviewProblemCollectionName || 'problemDetail';
  form.reviewDataReadMode = nextSettings.reviewDataReadMode || 'compatibility';
  form.codeReviewReadMode = nextSettings.codeReviewReadMode || 'compatibility';
}

function buildPayload(): CodeReviewMatchModeDbSettingsSaveRequest {
  return {
    enabled: form.enabled,
    syncEnabled: form.syncEnabled,
    mysqlHost: form.mysqlHost.trim(),
    mysqlPort: Number(form.mysqlPort || 3306),
    mysqlDatabase: form.mysqlDatabase.trim(),
    dgmMysqlDatabase: form.dgmMysqlDatabase.trim() || 'gitlab_spider_dgm',
    mysqlUsername: form.mysqlUsername.trim(),
    mysqlPassword: form.mysqlPassword?.trim() || null,
    mysqlTableName: form.mysqlTableName.trim() || 'spider_crowncad_data',
    legacyApiBaseUrl: form.legacyApiBaseUrl.trim() || legacyApiDefaultBaseUrl(),
    dgmLegacyApiBaseUrl: form.dgmLegacyApiBaseUrl.trim(),
    selectedTableNames: normalizeSelectedTableNames(form.selectedTableNames),
    mysqlFetchSize: Number(form.mysqlFetchSize || 1000),
    mongoUri: form.mongoUri?.trim() || null,
    mongoDatabase: form.mongoDatabase.trim() || 'spider',
    selectedMongoCollectionNames: normalizeSelectedMongoCollectionNames(form.selectedMongoCollectionNames),
    reviewReportCollectionName: form.reviewReportCollectionName.trim() || 'reviewReport',
    reviewProblemCollectionName: form.reviewProblemCollectionName.trim() || 'problemDetail',
    reviewDataReadMode: form.reviewDataReadMode,
    codeReviewReadMode: form.codeReviewReadMode,
  };
}

function normalizeSelectedTableNames(tableNames?: string[] | null) {
  const normalized = Array.from(
    new Set((tableNames ?? []).map((tableName) => tableName.trim()).filter(Boolean)),
  );
  return normalized.length > 0 ? normalized : [...defaultSelectedTableNames];
}

function normalizeSelectedMongoCollectionNames(collectionNames?: string[] | null) {
  const normalized = Array.from(
    new Set((collectionNames ?? []).map((collectionName) => collectionName.trim()).filter(Boolean)),
  );
  return normalized.length > 0 ? normalized : [...defaultSelectedMongoCollectionNames];
}

function formatDateTime(value?: string | null) {
  return value ? formatBeijingDateTime(value, '-') : '-';
}

function legacyApiDefaultBaseUrl() {
  return `http://${form.mysqlHost || '172.22.10.72'}:8091`;
}

function readModeText(mode?: 'compatibility' | 'formal') {
  return mode === 'formal' ? '正式数据' : '老平台兼容数据';
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
            <span>导入记录数</span>
            <strong>{{ settings?.syncRecordCount ?? 0 }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>导入范围</span>
            <strong>{{ selectedImportScopeText }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>评审数据读源</span>
            <strong>{{ reviewDataReadModeText }}</strong>
          </div>
          <div class="legacy-db-status-item">
            <span>代码走查读源</span>
            <strong>{{ codeReviewReadModeText }}</strong>
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
            <div class="legacy-db-card-title">兼容模式数据源</div>
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
          <el-form-item label="评审数据读源">
            <el-radio-group v-model="form.reviewDataReadMode">
              <el-radio-button label="compatibility">老平台兼容数据</el-radio-button>
              <el-radio-button label="formal">新平台正式数据</el-radio-button>
            </el-radio-group>
            <div class="form-help-text">转正式成功后会自动切到新平台正式数据；交接期也可单独切回查看。</div>
          </el-form-item>
          <el-form-item label="代码走查读源">
            <el-radio-group v-model="form.codeReviewReadMode">
              <el-radio-button label="compatibility">老平台兼容数据</el-radio-button>
              <el-radio-button label="formal">新平台正式数据</el-radio-button>
            </el-radio-group>
            <div class="form-help-text">读取正式数据时，代码走查非法数据页不再直接读取兼容表。</div>
          </el-form-item>

          <el-divider>MySQL</el-divider>

          <el-form-item label="导入表白名单">
            <div class="legacy-db-table-select">
              <SmartSelect
                v-model="form.selectedTableNames"
                multiple
                style="width: 100%"
                placeholder="选择老平台 MySQL 表"
                :loading="tableOptionsLoading"
                :options="tableSelectOptions"
                @visible-change="handleTableSelectVisibleChange"
              />
              <el-button :icon="Refresh" :loading="tableOptionsLoading" @click="ensureTableOptions(true)">
                刷新表列表
              </el-button>
            </div>
            <div class="form-help-text">
              {{
                tableOptionsLoaded
                  ? `已加载 ${tableOptions.length} 张可选表，已选择 ${form.selectedTableNames.length} 张。`
                  : '打开下拉菜单后加载当前 MySQL 数据库中的表。'
              }}
            </div>
          </el-form-item>

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
            <el-form-item label="DGM 数据库">
              <el-input v-model="form.dgmMysqlDatabase" placeholder="gitlab_spider_dgm" />
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
            <el-form-item label="代码走查兼容表">
              <el-input v-model="form.mysqlTableName" />
            </el-form-item>
            <el-form-item label="老平台接口地址">
              <el-input v-model="form.legacyApiBaseUrl" placeholder="http://172.22.10.72:8091" />
            </el-form-item>
            <el-form-item label="DGM 接口地址">
              <el-input v-model="form.dgmLegacyApiBaseUrl" placeholder="留空时使用老平台接口地址" />
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

          <el-divider>MongoDB</el-divider>

          <el-form-item label="导入集合白名单">
            <div class="legacy-db-table-select">
              <SmartSelect
                v-model="form.selectedMongoCollectionNames"
                multiple
                style="width: 100%"
                placeholder="选择老平台 MongoDB 集合"
                :loading="mongoCollectionOptionsLoading"
                :options="mongoCollectionSelectOptions"
                @visible-change="handleMongoCollectionSelectVisibleChange"
              />
              <el-button
                :icon="Refresh"
                :loading="mongoCollectionOptionsLoading"
                @click="ensureMongoCollectionOptions(true)"
              >
                刷新集合列表
              </el-button>
            </div>
            <div class="form-help-text">
              {{
                mongoCollectionOptionsLoaded
                  ? `已加载 ${mongoCollectionOptions.length} 个可选集合，已选择 ${form.selectedMongoCollectionNames.length} 个。`
                  : '打开下拉菜单后加载当前 MongoDB 数据库中的集合。'
              }}
            </div>
          </el-form-item>

          <div class="legacy-db-form-grid">
            <el-form-item label="Mongo URI">
              <el-input
                v-model="form.mongoUri"
                type="password"
                show-password
                :placeholder="settings?.mongoUriConfigured ? '已配置，留空不修改' : 'mongodb://172.22.10.72/?waitQueueMultiple=20'"
              />
            </el-form-item>
            <el-form-item label="Mongo 数据库">
              <el-input v-model="form.mongoDatabase" placeholder="spider" />
            </el-form-item>
            <el-form-item label="评审数据集合">
              <el-input v-model="form.reviewReportCollectionName" placeholder="reviewReport" />
            </el-form-item>
            <el-form-item label="评审问题集合">
              <el-input v-model="form.reviewProblemCollectionName" placeholder="problemDetail" />
            </el-form-item>
          </div>

          <el-divider>老平台数据转正式数据</el-divider>

          <div class="legacy-db-formal-import">
            <el-alert
              type="warning"
              :closable="false"
              show-icon
              title="该操作会先从已配置的老平台数据源拉取一次最新数据，再写入新平台正式业务表；重复数据会更新，不会追加双份。"
            />
            <el-form-item label="导入内容">
              <el-checkbox-group v-model="formalImportSelection">
                <el-checkbox label="review">评审数据</el-checkbox>
                <el-checkbox label="codeReview">代码走查数据</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="确认文本">
              <el-input
                v-model="formalImportConfirmation"
                :placeholder="formalImportConfirmationText"
                clearable
              />
              <div class="form-help-text">请输入完整确认文本：{{ formalImportConfirmationText }}</div>
            </el-form-item>
            <div class="legacy-db-formal-actions">
              <el-button
                type="warning"
                :icon="Refresh"
                :loading="formalImporting"
                :disabled="formalImportDisabled"
                @click="importLegacyPlatformToFormal"
              >
                老平台数据转正式数据
              </el-button>
            </div>
          </div>

          <div class="legacy-db-actions">
            <el-button type="primary" :icon="Check" :loading="saving" @click="saveSettings">保存设置</el-button>
            <el-button :icon="Connection" :loading="testing" @click="testConnection">测试 MySQL 连接</el-button>
            <el-button :icon="Connection" :loading="mongoTesting" @click="testMongoConnection">
              测试 MongoDB 连接
            </el-button>
            <el-button
              :icon="Refresh"
              :loading="syncing"
              :disabled="!form.enabled || !form.syncEnabled"
              @click="syncMysqlNow"
            >
              导入代码走查数据
            </el-button>
            <el-button
              :icon="Refresh"
              :loading="mongoSyncing"
              :disabled="!form.enabled"
              @click="syncMongoReviewNow"
            >
              导入评审数据
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

.legacy-db-table-select {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}

.legacy-db-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-left: 148px;
}

.legacy-db-formal-import {
  display: grid;
  gap: 12px;
}

.legacy-db-formal-actions {
  display: flex;
  align-items: center;
  gap: 8px;
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

  .legacy-db-table-select {
    flex-direction: column;
  }

  .legacy-db-actions {
    padding-left: 0;
  }

  .legacy-db-formal-actions {
    padding-left: 0;
  }
}
</style>
