<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
// 闀滃儚璁剧疆椤甸泦涓鐞嗗悓姝ラ厤缃€佺櫧鍚嶅崟銆乄ebhook 鍜屾竻鐞嗗姩浣滐紝鏄暟鎹叆鍙ｇ殑杩愮淮闈㈡澘銆?
// 姣忕粍鎿嶄綔鎷嗗埌鐙珛 controller锛岄〉闈㈠彧璐熻矗鎶婅〃鍗曠姸鎬佸拰鍙嶉鍔ㄤ綔缁勫悎璧锋潵銆?
import { Tools } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type { GitlabSourceHealthResponse, GitlabSyncConfig, GitlabTableSyncDiagnosticsResponse } from '../types/api';
import SmartSelect from '../components/base/SmartSelect.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import { buildPurgeSummaryHtml, formatDateTime, syncStatusTagType, syncStatusText } from './mirror-settings-helpers';
import MirrorSyncLogTable from './MirrorSyncLogTable.vue';
import MirrorSyncStatusCard from './MirrorSyncStatusCard.vue';
import { useMirrorPurgeDialog } from './useMirrorPurgeDialog';
import { useMirrorStatusController } from './useMirrorStatusController';
import { useMirrorStatusPresentation } from './useMirrorStatusPresentation';
import { useMirrorSyncActionsController } from './useMirrorSyncActionsController';
import { useMirrorWebhookRegistrationController } from './useMirrorWebhookRegistrationController';
import { useMirrorWhitelistOptionsController } from './useMirrorWhitelistOptionsController';

const initialized = ref(false);
const configs = ref<GitlabSyncConfig[]>([]);
const sourceHealth = ref<GitlabSourceHealthResponse[]>([]);
const tableSyncDiagnostics = ref<GitlabTableSyncDiagnosticsResponse | null>(null);
const tableSyncDiagnosticsLoading = ref(false);
const selectedConfigId = ref<number | undefined>(undefined);
const isCreatingNewConfig = ref(false);
const previousConfigIdBeforeCreate = ref<number | undefined>(undefined);
const newConfigSnapshot = ref('');
const ACTIVE_SYNC_STATUSES = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const form = ref<GitlabSyncConfig>({
  name: 'GitLab default source',
  enabled: true,
  sourceEnabled: true,
  sourceInstance: 'default',
  autoSyncEnabled: true,
  sourceMode: 'DOCKER',
  whitelistMode: 'RECOMMENDED',
  whitelistTables: [],
  dbHost: 'localhost',
  dbPort: 5432,
  dbName: 'gitlabhq_production',
  dbUsername: 'gitlab',
  dbPassword: '',
  dockerContainerName: 'gitlab-data-web-1',
  webhookSecret: '',
  webhookEnabled: false,
  webhookProjectId: null,
  compensationIntervalMinutes: 10,
});

const {
  loading,
  refreshing,
  status,
  loadStatus,
  refreshStatus,
  stopRunningRefresh,
  syncRunningRefresh,
} = useMirrorStatusController({
  form,
  loadStatusData: () => api.getStatus(selectedConfigId.value),
  loadWebhookRegistration: () => {
    void loadWebhookRegistration(false);
  },
  notifyError: (message) => ElMessage.error(message),
});

const {
  whitelistOptions,
  whitelistOptionsLoading,
  whitelistOptionsLoaded,
  recommendedCount,
  whitelistSelectOptions,
  ensureWhitelistOptions,
} = useMirrorWhitelistOptionsController({
  form,
  loadWhitelistOptions: () => api.getWhitelistOptions(selectedConfigId.value),
  notifyError: (message) => ElMessage.error(message),
});

const {
  saving,
  syncing,
  testing,
  cancelling,
  saveConfig,
  testConnection,
  startFullSync,
  startIncrementalSync,
  cancelSyncTask,
} = useMirrorSyncActionsController({
  form,
  saveConfigData: async (config) => {
    const saved = await api.saveConfig(config);
    await loadConfigs();
    selectedConfigId.value = saved.id;
    isCreatingNewConfig.value = false;
    previousConfigIdBeforeCreate.value = undefined;
    newConfigSnapshot.value = '';
    return saved;
  },
  testConnectionData: () => api.testConnection(selectedConfigId.value),
  startFullSyncData: () => api.startFullSync(selectedConfigId.value),
  startIncrementalSyncData: () => api.startIncrementalSync(selectedConfigId.value),
  cancelSyncData: () => api.cancelSync(selectedConfigId.value),
  loadStatus: (showError, blocking) => loadStatus(showError, blocking),
  loadWebhookRegistration: () => {
    void loadWebhookRegistration(false);
  },
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyInfo: (message) => ElMessage.info(message),
  notifyError: (message) => ElMessage.error(message),
});

const {
  registeringWebhook,
  webhookRegistrationLoading,
  webhookRegistration,
  loadWebhookRegistration,
  registerWebhook,
} = useMirrorWebhookRegistrationController({
  getRegistrationStatus: () => api.getWebhookRegistrationStatus(selectedConfigId.value),
  saveConfig: () => saveConfig(false),
  registerWebhook: () => api.registerWebhook(selectedConfigId.value),
  loadStatus: (showError, blocking) => loadStatus(showError, blocking),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const isDockerMode = computed(() => form.value.sourceMode === 'DOCKER');
const sourceEnabled = computed(() => form.value.sourceEnabled ?? form.value.enabled);
const syncEnabled = computed(() => sourceEnabled.value && form.value.autoSyncEnabled);
const sourceSelectPlaceholder = computed(() =>
  isCreatingNewConfig.value ? 'New source (unsaved)' : 'Select a configured source',
);
const savedConfigActionDisabled = computed(() => isCreatingNewConfig.value || selectedConfigId.value == null);
const systemHookAutoRegistrationDisabled = computed(() =>
  savedConfigActionDisabled.value || !isDockerMode.value || !form.value.webhookEnabled,
);
const currentSourceText = computed(() => `${form.value.name || 'Unnamed source'} (${form.value.sourceInstance || 'default'})`);
const currentSourceHealth = computed(() => {
  const healthItems = Array.isArray(sourceHealth.value) ? sourceHealth.value : [];
  return healthItems.find((item) => item.configId === selectedConfigId.value);
});
const currentFactLaggingDomains = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return [];
  }
  const domains: string[] = [];
  if (health.mergeRequestFactLagging) {
    domains.push('浠ｇ爜璧版煡浜嬪疄');
  }
  if (health.issueFactLagging) {
    domains.push('绯荤粺娴嬭瘯/瀹㈡埛闂浜嬪疄');
  }
  if (health.integrationTestFactLagging) {
    domains.push('闆嗘垚娴嬭瘯浜嬪疄');
  }
  return domains;
});
const currentSourceHealthTone = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return 'info';
  }
  if (
    health.missingRequiredMirrorTables.length > 0 ||
    health.latestLogStatus === 'FAILED' ||
    health.latestLogStatus === 'TIMEOUT'
  ) {
    return 'danger';
  }
  if (
    health.factLayerLagging ||
    health.latestLogStatus === 'PARTIAL_SUCCESS' ||
    ['RUNNING', 'QUEUED', 'RETRYING'].includes(health.currentStatus)
  ) {
    return 'warning';
  }
  if (!health.enabled) {
    return 'info';
  }
  return 'success';
});
const currentSourceHealthText = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return 'No diagnostics';
  }
  if (!health.enabled) {
    return 'Disabled';
  }
  if (health.missingRequiredMirrorTables.length > 0) {
    return 'Mirror incomplete';
  }
  if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
    return 'Sync error';
  }
  if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
    return 'Partial failure';
  }
  if (health.factLayerLagging) {
    return 'Fact layer lagging';
  }
  if (['RUNNING', 'QUEUED', 'RETRYING'].includes(health.currentStatus)) {
    return 'Sync running';
  }
  return 'Healthy';
});
const currentSourceHealthSummary = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return 'No health diagnostics are available for the current source yet.';
  }
  if (!health.enabled) {
    return 'This source is disabled and will not participate in automatic sync.';
  }
  if (health.missingRequiredMirrorTables.length > 0) {
    return 'Required mirror tables are missing, so some downstream views may be incomplete.';
  }
  if (health.factLayerLagging) {
    return 'Mirror data is newer than the current fact refresh used by some views.';
  }
  if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
    return health.latestLogMessage || 'The latest sync did not finish successfully.';
  }
  if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
    return health.latestLogMessage || 'The latest sync partially failed and table-level recovery may still be running.';
  }
  return 'No blocking issue is currently visible in mirror tables, fact refresh, or recent sync status.';
});
const missingRequiredMirrorTablesPreview = computed(() => {
  const tables = currentSourceHealth.value?.missingRequiredMirrorTables ?? [];
  return {
    visible: tables.slice(0, 5),
    hiddenCount: Math.max(tables.length - 5, 0),
  };
});
const tableSyncRows = computed(() => tableSyncDiagnostics.value?.tables ?? []);
const tableSyncProblemRows = computed(() =>
  tableSyncRows.value.filter(
    (row) =>
      row.dirty ||
      row.latestTaskStatus === 'FAILED' ||
      row.latestTaskStatus === 'TIMEOUT' ||
      row.latestTaskStatus === 'RETRYING',
  ),
);
const tableSyncDisplayRows = computed(() =>
  (tableSyncProblemRows.value.length > 0 ? tableSyncProblemRows.value : tableSyncRows.value).slice(0, 8),
);
const tableSyncQueueSummary = computed(() => {
  const diagnostics = tableSyncDiagnostics.value;
  if (!diagnostics) {
    return 'No table-level diagnostics';
  }
  return [
    `寰呮墽琛?${diagnostics.pendingTaskCount}`,
    `杩愯涓?${diagnostics.runningTaskCount}`,
    `閲嶈瘯涓?${diagnostics.retryingTaskCount}`,
    `澶辫触 ${diagnostics.failedTaskCount + diagnostics.timedOutTaskCount}`,
  ].join(' 路 ');
});
const {
  progress,
  currentTask,
  recentLogs,
  canCancel,
  lastSyncDisplay,
  progressPercent,
  displayStatus,
  statusMessageClass,
  phaseText,
  progressHint,
  currentMessageText,
} = useMirrorStatusPresentation(status);
const {
  purgeDialogVisible,
  purgeScope,
  purgeConfirmText,
  isPurging,
  purgeDialogCopy,
  purgeConfirmMatched,
  purgeProgressText,
  openPurgeDialog,
  closePurgeDialog,
  purgeMirrorData,
  handlePurgeDialogBeforeClose,
} = useMirrorPurgeDialog({
  purgeMirrorData: (scope) => api.purgeMirrorData(scope, selectedConfigId.value),
  loadStatus: () => loadStatus(false, false),
  notifyError: (message) => ElMessage.error(message),
  showPurgeSummary: (result) =>
    ElMessageBox.alert(buildPurgeSummaryHtml(result), 'Deletion complete', {
      type: 'success',
      confirmButtonText: 'OK',
      dangerouslyUseHTMLString: true,
    }),
});
watch(
  () => currentTask.value?.status,
  (nextStatus, previousStatus) => {
    syncRunningRefresh(nextStatus);
    if (
      previousStatus
      && ACTIVE_SYNC_STATUSES.includes(previousStatus)
      && (!nextStatus || !ACTIVE_SYNC_STATUSES.includes(nextStatus))
    ) {
      void loadSourceHealth();
    }
  },
);

async function initializePage() {
  try {
    await loadConfigs();
    await Promise.all([loadSourceHealth(), loadTableSyncDiagnostics(false)]);
    await loadStatus(false, false);
  } finally {
    initialized.value = true;
  }
  void loadWebhookRegistration(false);
}

async function loadConfigs() {
  configs.value = await api.getConfigs();
  if (selectedConfigId.value == null) {
    selectedConfigId.value = configs.value.find((item) => item.id != null)?.id;
  }
}

async function loadSourceHealth() {
  const healthItems = await api.getSourceHealth();
  sourceHealth.value = Array.isArray(healthItems) ? healthItems : [];
}

async function loadTableSyncDiagnostics(showError = false) {
  if (selectedConfigId.value == null || isCreatingNewConfig.value) {
    tableSyncDiagnostics.value = null;
    return;
  }
  tableSyncDiagnosticsLoading.value = true;
  try {
    tableSyncDiagnostics.value = await api.getTableSyncDiagnostics(selectedConfigId.value);
  } catch (error) {
    tableSyncDiagnostics.value = null;
    if (showError) {
      ElMessage.error(error instanceof Error ? error.message : 'Failed to load table sync diagnostics');
    }
  } finally {
    tableSyncDiagnosticsLoading.value = false;
  }
}

async function handleConfigSelection(configId: number) {
  if (isCreatingNewConfig.value) {
    return;
  }
  selectedConfigId.value = configId;
  whitelistOptionsLoaded.value = false;
  await loadStatus(true, true);
  void loadSourceHealth();
  void loadTableSyncDiagnostics(true);
  void loadWebhookRegistration(false);
}

function createNewConfig() {
  previousConfigIdBeforeCreate.value = selectedConfigId.value;
  isCreatingNewConfig.value = true;
  stopRunningRefresh();
  selectedConfigId.value = undefined;
  tableSyncDiagnostics.value = null;
  form.value = {
    ...form.value,
    id: undefined,
    name: 'GitLab new source',
    sourceInstance: '',
    dbPassword: '',
    webhookSecret: '',
    lastFullSyncAt: null,
    lastIncrementalSyncAt: null,
  };
  newConfigSnapshot.value = JSON.stringify(form.value);
}

async function cancelNewConfig() {
  if (JSON.stringify(form.value) !== newConfigSnapshot.value) {
    try {
      await ElMessageBox.confirm('Discard the unsaved source configuration?', 'Cancel new source', {
        type: 'warning',
        confirmButtonText: 'Discard',
        cancelButtonText: 'Continue editing',
      });
    } catch {
      return;
    }
  }
  isCreatingNewConfig.value = false;
  selectedConfigId.value = previousConfigIdBeforeCreate.value ?? configs.value.find((item) => item.id != null)?.id;
  previousConfigIdBeforeCreate.value = undefined;
  newConfigSnapshot.value = '';
  whitelistOptionsLoaded.value = false;
  await loadStatus(true, true);
  void loadSourceHealth();
  void loadTableSyncDiagnostics(true);
  void loadWebhookRegistration(false);
}

async function refreshCurrentStatus() {
  if (isCreatingNewConfig.value) {
    return;
  }
  await refreshStatus();
  await Promise.all([loadSourceHealth(), loadTableSyncDiagnostics(false)]);
}

onMounted(async () => {
  await initializePage();
});

onBeforeUnmount(() => {
  stopRunningRefresh();
});
</script>

<template>
  <PageStateShell :ready="initialized">
    <template #skeleton>
      <div class="settings-grid">
        <el-card shadow="never" class="panel-card page-skeleton-card">
          <el-skeleton animated>
            <template #template>
              <div class="page-skeleton-stack">
                <el-skeleton-item variant="h3" style="width: 36%" />
                <el-skeleton-item variant="text" style="width: 72%" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 52px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 52px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 52px" />
                <el-skeleton-item variant="rect" style="width: 100%; height: 220px" />
              </div>
            </template>
          </el-skeleton>
        </el-card>
        <div class="settings-side-panel">
          <el-card shadow="never" class="panel-card page-skeleton-card">
            <el-skeleton animated>
              <template #template>
                <div class="page-skeleton-stack">
                  <el-skeleton-item variant="h3" style="width: 44%" />
                  <el-skeleton-item variant="text" style="width: 78%" />
                  <el-skeleton-item variant="rect" style="width: 100%; height: 180px" />
                </div>
              </template>
            </el-skeleton>
          </el-card>
          <el-card shadow="never" class="panel-card page-skeleton-card">
            <el-skeleton animated>
              <template #template>
                <div class="page-skeleton-stack">
                  <el-skeleton-item variant="h3" style="width: 42%" />
                  <el-skeleton-item variant="rect" style="width: 100%; height: 200px" />
                </div>
              </template>
            </el-skeleton>
          </el-card>
        </div>
      </div>
    </template>

    <div class="settings-grid">
      <el-card shadow="never" class="panel-card">
      <template #header>
        <div class="panel-header">
          <div>
            <div class="panel-title">GitLab 鏁版嵁闀滃儚璁剧疆</div>
          </div>
          <div class="panel-header-meta">
            <span class="header-secondary-text">{{ lastSyncDisplay }}</span>
            <el-tag v-if="loading" size="small" type="info">Loading</el-tag>
          </div>
        </div>
      </template>

      <el-form label-width="150px">
        <el-form-item label="GitLab source">
          <div style="display: flex; width: 100%; gap: 8px">
            <el-select
              v-model="selectedConfigId"
              :placeholder="sourceSelectPlaceholder"
              :disabled="isCreatingNewConfig"
              style="width: 100%"
              @change="handleConfigSelection"
            >
              <el-option
                v-for="item in configs"
                :key="item.id ?? item.sourceInstance"
                :label="`${item.name} (${item.sourceInstance})`"
                :value="item.id"
              />
            </el-select>
            <el-button v-if="!isCreatingNewConfig" @click="createNewConfig">New source</el-button>
            <el-button v-else @click="cancelNewConfig">鍙栨秷鏂板</el-button>
          </div>
        </el-form-item>
        <el-form-item label="鏉ユ簮鏍囪瘑">
          <el-input v-model="form.sourceInstance" placeholder="渚嬪 cc / dgm" />
        </el-form-item>
        <el-form-item label="Source name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="Enable source">
          <el-switch v-model="form.sourceEnabled" />
        </el-form-item>

        <el-divider>婧愭暟鎹簱妯″紡</el-divider>

        <el-form-item label="璇诲彇鏂瑰紡">
          <el-radio-group v-model="form.sourceMode">
            <el-radio value="DOCKER">Docker 妯″紡</el-radio>
            <el-radio value="DIRECT">鐩磋繛 PostgreSQL</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="isDockerMode">
          <el-form-item label="GitLab container">
            <el-input v-model="form.dockerContainerName" placeholder="渚嬪 gitlab-data-web-1" />
          </el-form-item>
          <el-form-item label="Database name">
            <el-input v-model="form.dbName" />
          </el-form-item>
          <el-form-item label="鏁版嵁搴撶敤鎴峰悕">
            <el-input v-model="form.dbUsername" />
          </el-form-item>
          <el-alert
            title="Docker mode reads PostgreSQL from inside the GitLab container through docker exec, so no extra database password is needed."
            type="info"
            :closable="false"
            show-icon
          />
        </template>

        <template v-else>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="Database host">
                <el-input v-model="form.dbHost" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="Database port">
                <el-input-number v-model="form.dbPort" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="Database name">
                <el-input v-model="form.dbName" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="鏁版嵁搴撶敤鎴峰悕">
                <el-input v-model="form.dbUsername" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="Database password">
            <el-input v-model="form.dbPassword" type="password" show-password />
          </el-form-item>
        </template>

        <el-divider>鍚屾绛栫暐</el-divider>

        <el-form-item label="鑷姩鍚屾">
          <el-switch v-model="form.autoSyncEnabled" />
        </el-form-item>
        <el-form-item label="琛ュ伩闂撮殧(鍒嗛挓)">
          <el-input-number v-model="form.compensationIntervalMinutes" :min="1" :max="720" />
        </el-form-item>
        <el-form-item label="Whitelist mode">
          <el-radio-group v-model="form.whitelistMode">
            <el-radio value="RECOMMENDED">Recommended tables</el-radio>
            <el-radio value="ALL">All tables</el-radio>
            <el-radio value="CUSTOM">鑷畾涔夌櫧鍚嶅崟</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.whitelistMode === 'CUSTOM'" label="鑷畾涔夌櫧鍚嶅崟">
          <SmartSelect
            v-model="form.whitelistTables"
            multiple
            style="width: 100%"
            :loading="whitelistOptionsLoading"
            :options="whitelistSelectOptions"
            @visible-change="(visible:boolean) => visible && ensureWhitelistOptions()"
          />
          <div class="form-help-text">
            {{
              whitelistOptionsLoaded
                ? `Loaded ${whitelistOptions.length} table options, including ${recommendedCount} recommended tables.`
                : 'Load table options on demand when entering custom whitelist mode.'
            }}
          </div>
        </el-form-item>

        <el-divider>System Hook wakeup</el-divider>

        <el-form-item label="Receive System Hook">
          <el-switch v-model="form.webhookEnabled" />
        </el-form-item>
        <el-form-item label="System Hook URL">
          <el-input :model-value="status?.systemHookUrl || status?.webhookUrl || ''" readonly />
        </el-form-item>
        <el-form-item label="System Hook Secret">
          <el-input v-model="form.webhookSecret" />
        </el-form-item>
        <el-form-item label="System Hook status">
          <div class="webhook-status-line">
            <el-tag
              :type="webhookRegistrationLoading ? 'info' : webhookRegistration?.registered ? 'success' : webhookRegistration?.configured ? 'warning' : 'info'"
              round
            >
              {{
                webhookRegistrationLoading
                  ? 'Checking'
                  : webhookRegistration?.registered
                    ? 'Registered'
                    : webhookRegistration?.configured
                      ? 'Not registered'
                      : 'Not configured'
              }}
            </el-tag>
            <span class="webhook-status-text">
              {{
                webhookRegistrationLoading
                  ? 'Checking GitLab System Hook status without blocking the page.'
                  : webhookRegistration?.message || 'GitLab System Hook status has not been checked yet.'
              }}
            </span>
          </div>
        </el-form-item>
        <el-alert
          v-if="!isDockerMode"
          title="Direct mode does not auto-register GitLab System Hook; save the config, then register the URL and secret in GitLab Admin Area > System Hooks."
          type="info"
          :closable="false"
          show-icon
        />

        <el-space wrap>
          <el-button type="primary" :loading="saving" @click="saveConfig()">淇濆瓨閰嶇疆</el-button>
          <el-button
            :icon="Tools"
            :loading="testing"
            :disabled="saving || testing || savedConfigActionDisabled"
            @click="testConnection"
          >
            娴嬭瘯杩炴帴
          </el-button>
          <el-button
            :loading="registeringWebhook"
            :disabled="systemHookAutoRegistrationDisabled"
            @click="registerWebhook"
          >
            Register System Hook
          </el-button>
          <el-button
            type="success"
            :loading="syncing"
            :disabled="!syncEnabled || savedConfigActionDisabled"
            @click="startFullSync"
          >
            棣栨鍏ㄩ噺鍚屾
          </el-button>
          <el-button
            :loading="syncing"
            :disabled="!syncEnabled || savedConfigActionDisabled"
            @click="startIncrementalSync"
          >
            绔嬪嵆澧為噺鍚屾
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="cancelling"
            :disabled="!canCancel || savedConfigActionDisabled"
            @click="cancelSyncTask"
          >
            涓瀵煎叆
          </el-button>
          <el-button type="danger" plain :disabled="savedConfigActionDisabled" @click="openPurgeDialog">
            Delete mirror data
          </el-button>
        </el-space>
      </el-form>
    </el-card>

    <div class="settings-side-panel">
      <MirrorSyncStatusCard
        :display-status="displayStatus"
        :status-message-class="statusMessageClass"
        :current-message-text="currentMessageText"
        :phase-text="phaseText"
        :progress-percent="progressPercent"
        :progress-hint="progressHint"
        :progress="progress"
        :current-task="currentTask"
        :current-started-at="status?.currentStartedAt"
      />

      <MirrorSyncLogTable :logs="recentLogs" :refreshing="refreshing" @refresh="refreshCurrentStatus" />

      <el-card shadow="never" class="panel-card source-health-card">
        <template #header>
          <div class="panel-header">
            <div class="panel-title">Source health</div>
          </div>
        </template>
        <template v-if="currentSourceHealth">
          <div class="source-health-overview" :class="`is-${currentSourceHealthTone}`">
            <div class="source-health-status-dot" />
            <div class="source-health-overview-copy">
              <div class="source-health-overview-title">
                <span>{{ currentSourceHealthText }}</span>
                <el-tag :type="currentSourceHealthTone" round>{{ currentSourceHealth.sourceInstance }}</el-tag>
              </div>
              <div class="source-health-overview-desc">{{ currentSourceHealthSummary }}</div>
            </div>
          </div>
          <div class="source-health-grid">
            <div>
              <span>Mirror tables</span>
              <strong>{{ currentSourceHealth.existingMirrorTables }} / {{ currentSourceHealth.registeredMirrorTables }}</strong>
            </div>
            <div>
              <span>浠ｇ爜璧版煡浜嬪疄</span>
              <strong>{{ currentSourceHealth.mergeRequestFactCount }}</strong>
            </div>
            <div>
              <span>Latest sync</span>
              <strong>{{ currentSourceHealth.latestLogStatus || currentSourceHealth.currentStatus || '-' }}</strong>
            </div>
          </div>
          <div class="source-health-fact-grid">
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.mergeRequestFactLagging }">
              <span>浠ｇ爜璧版煡浜嬪疄</span>
              <strong>{{ currentSourceHealth.mergeRequestFactCount }}</strong>
            </div>
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.issueFactLagging }">
              <span>绯荤粺娴嬭瘯/瀹㈡埛闂浜嬪疄</span>
              <strong>{{ currentSourceHealth.issueFactCount }}</strong>
            </div>
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.integrationTestFactLagging }">
              <span>闆嗘垚娴嬭瘯浜嬪疄</span>
              <strong>{{ currentSourceHealth.integrationTestFactCount }}</strong>
            </div>
          </div>

          <div class="source-health-detail-list">
            <div class="source-health-detail-row">
              <span>Latest sync time</span>
              <strong>{{ currentSourceHealth.latestLogFinishedAt || currentSourceHealth.currentStartedAt || '-' }}</strong>
            </div>
            <div class="source-health-detail-row">
              <span>Fact refresh</span>
              <strong>{{ currentSourceHealth.latestFactUpdatedAt || '-' }}</strong>
            </div>
          </div>

          <div v-if="currentSourceHealth.missingRequiredMirrorTables.length" class="source-health-missing-panel">
            <div class="source-health-section-title">
              缂哄皯鍏抽敭闀滃儚琛?
              <el-tag size="small" type="warning" round>
                {{ currentSourceHealth.missingRequiredMirrorTables.length }} 寮?
              </el-tag>
            </div>
            <div class="source-health-table-tags">
              <el-tag
                v-for="table in missingRequiredMirrorTablesPreview.visible"
                :key="table"
                type="warning"
                size="small"
                effect="plain"
              >
                {{ table }}
              </el-tag>
              <el-tag v-if="missingRequiredMirrorTablesPreview.hiddenCount" size="small" type="info" effect="plain">
                +{{ missingRequiredMirrorTablesPreview.hiddenCount }}
              </el-tag>
            </div>
          </div>

          <div v-if="currentSourceHealth.latestLogMessage || currentSourceHealth.currentMessage" class="source-health-message">
            <span>杩戞湡淇℃伅</span>
            <strong>{{ currentSourceHealth.latestLogMessage || currentSourceHealth.currentMessage }}</strong>
          </div>
          <el-alert
            v-if="currentSourceHealth.missingRequiredMirrorTables.length"
            class="source-health-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="`Missing ${currentSourceHealth.missingRequiredMirrorTables.length} required mirror tables`"
            :description="currentSourceHealth.missingRequiredMirrorTables.slice(0, 3).join(', ')"
          />
          <el-alert
            v-if="currentSourceHealth.factLayerLagging"
            class="source-health-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="`${currentFactLaggingDomains.join(', ') || 'Fact layer'} may be lagging`"
            :description="currentSourceHealth.factLayerMessage || 'Mirror data is newer than the current fact refresh.'"
          />
        </template>
        <el-empty v-else description="No source health diagnostics yet." />
      </el-card>

      <el-card shadow="never" class="panel-card table-sync-diagnostics-card">
        <template #header>
          <div class="panel-header">
            <div>
              <div class="panel-title">琛ㄧ骇鍚屾璇婃柇</div>
              <div class="panel-subtitle">{{ tableSyncQueueSummary }}</div>
            </div>
            <el-button
              size="small"
              text
              :loading="tableSyncDiagnosticsLoading"
              :disabled="savedConfigActionDisabled"
              @click="loadTableSyncDiagnostics(true)"
            >
              鍒锋柊
            </el-button>
          </div>
        </template>
        <template v-if="tableSyncDiagnostics">
          <div class="table-sync-summary-grid">
            <div>
              <span>Synced tables</span>
              <strong>{{ tableSyncDiagnostics.tableCount }}</strong>
            </div>
            <div>
              <span>鑴忚〃</span>
              <strong>{{ tableSyncDiagnostics.dirtyTableCount }}</strong>
            </div>
            <div>
              <span>Retrying</span>
              <strong>{{ tableSyncDiagnostics.retryingTaskCount }}</strong>
            </div>
          </div>
          <el-table
            v-loading="tableSyncDiagnosticsLoading"
            class="table-sync-diagnostics-table"
            :data="tableSyncDisplayRows"
            size="small"
          >
            <el-table-column prop="sourceTable" label="婧愯〃" min-width="130" show-overflow-tooltip />
            <el-table-column label="Status" width="86">
              <template #default="{ row }">
                <el-tag v-if="row.latestTaskStatus" size="small" :type="syncStatusTagType(row.latestTaskStatus)">
                  {{ syncStatusText(row.latestTaskStatus) }}
                </el-tag>
                <el-tag v-else size="small" type="info">No task</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="鑴忚〃" width="64">
              <template #default="{ row }">
                  <el-tag size="small" :type="row.dirty ? 'warning' : 'success'" effect="plain">
                    {{ row.dirty ? 'Yes' : 'No' }}
                  </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="姘翠綅/閿欒" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.lastError || row.latestTaskError">{{ row.lastError || row.latestTaskError }}</span>
                <span v-else>{{ formatDateTime(row.lastWatermarkAt || row.lastSuccessAt) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="tableSyncRows.length > tableSyncDisplayRows.length" class="table-sync-more">
            浼樺厛灞曠ず寮傚父琛紝鍙︽湁 {{ tableSyncRows.length - tableSyncDisplayRows.length }} 寮犺〃鏈睍寮€銆?
          </div>
        </template>
        <el-empty v-else description="鏆傛棤琛ㄧ骇鍚屾璇婃柇" />
      </el-card>
      </div>
    </div>
  </PageStateShell>

  <el-dialog
    v-model="purgeDialogVisible"
    :title="purgeDialogCopy.title"
    width="680px"
    class="mirror-purge-dialog"
    :show-close="!isPurging"
    :close-on-click-modal="false"
    :close-on-press-escape="!isPurging"
    :before-close="handlePurgeDialogBeforeClose"
    @close="closePurgeDialog"
  >
    <div class="purge-dialog-body">
      <div class="purge-hero">
        <div class="purge-hero-badge">High risk</div>
        <div class="purge-hero-title">This action permanently deletes local mirror data.</div>
        <div class="purge-hero-description">
          {{ purgeDialogCopy.detail }}
        </div>
        <div class="purge-hero-description">
          Current scope: {{ currentSourceText }}
        </div>
      </div>

      <el-alert
        v-if="isPurging"
        class="purge-progress-alert"
        type="warning"
        :closable="false"
        show-icon
        title="Deleting mirror data"
        :description="purgeProgressText"
      />

      <div class="purge-scope-cards">
        <label class="purge-scope-card" :class="{ active: purgeScope === 'MIRROR_DATA_ONLY', disabled: isPurging }">
          <input v-model="purgeScope" type="radio" value="MIRROR_DATA_ONLY" :disabled="isPurging" />
          <div class="purge-scope-card-title">Delete mirror data</div>
          <div class="purge-scope-card-desc">
            Delete all local mirror tables, registry entries, and summary data without affecting GitLab source data or local non-mirror data.
          </div>
        </label>

        <label
          class="purge-scope-card"
          :class="{ active: purgeScope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST', disabled: isPurging }"
        >
          <input
            v-model="purgeScope"
            type="radio"
            value="MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST"
            :disabled="isPurging"
          />
          <div class="purge-scope-card-title">Delete mirror data outside the current whitelist</div>
          <div class="purge-scope-card-desc">
            Delete only mirror data outside the current whitelist and keep the mirror data for the tables that remain selected.
          </div>
        </label>
      </div>

      <div class="purge-warning-list">
        <div class="purge-warning-item">Make sure no sync task is running or queued before deleting.</div>
        <div class="purge-warning-item">This only affects local mirror data and will not delete GitLab source data.</div>
        <div class="purge-warning-item">Local non-mirror business data will be preserved.</div>
      </div>

      <div class="purge-confirm-panel" :class="{ 'is-disabled': isPurging }">
        <div class="purge-confirm-label">Type the confirmation phrase to continue</div>
        <div class="purge-confirm-phrase">{{ purgeDialogCopy.confirmText }}</div>
        <el-input v-model="purgeConfirmText" :placeholder="purgeDialogCopy.confirmText" :disabled="isPurging" />
      </div>
    </div>
    <template #footer>
      <el-button :disabled="isPurging" @click="closePurgeDialog">Cancel</el-button>
      <el-button type="danger" :loading="isPurging" :disabled="!purgeConfirmMatched || isPurging" @click="purgeMirrorData()">
        Confirm deletion
      </el-button>
    </template>
  </el-dialog>
</template>

