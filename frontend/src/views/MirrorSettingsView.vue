<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
// 镜像设置页集中管理同步配置、白名单、System Hook 和清理动作，是数据入口的运维面板。
// 每组操作拆到独立 controller，页面只负责把表单状态和反馈动作组合起来。
import { Tools } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type { GitlabSourceHealthResponse, GitlabSyncConfig, SyncRunDiagnosticsResponse } from '../types/api';
import SmartSelect from '../components/base/SmartSelect.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import { buildPurgeSummaryHtml, formatDateTime, syncStatusTagType, syncStatusText } from './mirror-settings-helpers';
import MirrorSyncLogTable from './MirrorSyncLogTable.vue';
import MirrorSyncStatusCard from './MirrorSyncStatusCard.vue';
import { useMirrorPurgeDialog } from './useMirrorPurgeDialog';
import { useMirrorStatusController } from './useMirrorStatusController';
import { useMirrorStatusPresentation } from './useMirrorStatusPresentation';
import { useMirrorSyncActionsController } from './useMirrorSyncActionsController';
import { useMirrorSystemHookRegistrationController } from './useMirrorSystemHookRegistrationController';
import { useMirrorWhitelistOptionsController } from './useMirrorWhitelistOptionsController';

const initialized = ref(false);
const configs = ref<GitlabSyncConfig[]>([]);
const sourceHealth = ref<GitlabSourceHealthResponse[]>([]);
const tableSyncDiagnostics = ref<SyncRunDiagnosticsResponse | null>(null);
const tableSyncDiagnosticsLoading = ref(false);
const selectedConfigId = ref<number | undefined>(undefined);
const isCreatingNewConfig = ref(false);
const previousConfigIdBeforeCreate = ref<number | undefined>(undefined);
const newConfigSnapshot = ref('');
const ACTIVE_SYNC_STATUSES = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const form = ref<GitlabSyncConfig>({
  name: 'GitLab 默认数据源',
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
  systemHookSecret: '',
  systemHookEnabled: false,
  systemHookProjectId: null,
  compensationIntervalMinutes: 10,
  syncThreadMode: 'FIXED',
  syncThreadValue: 2,
  maxSyncThreads: 16,
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
  loadSystemHookRegistration: () => {
    void loadSystemHookRegistration(false);
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
  loadSystemHookRegistration: () => {
    void loadSystemHookRegistration(false);
  },
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyInfo: (message) => ElMessage.info(message),
  notifyError: (message) => ElMessage.error(message),
});

const {
  registeringSystemHook,
  systemHookRegistrationLoading,
  systemHookRegistration,
  loadSystemHookRegistration,
  registerSystemHook,
} = useMirrorSystemHookRegistrationController({
  getRegistrationStatus: () => api.getSystemHookRegistrationStatus(selectedConfigId.value),
  saveConfig: () => saveConfig(false),
  registerSystemHook: () => api.registerSystemHook(selectedConfigId.value),
  loadStatus: (showError, blocking) => loadStatus(showError, blocking),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const isDockerMode = computed(() => form.value.sourceMode === 'DOCKER');
const sourceEnabled = computed(() => form.value.sourceEnabled ?? form.value.enabled);
const syncEnabled = computed(() => sourceEnabled.value && form.value.autoSyncEnabled);
const sourceSelectPlaceholder = computed(() =>
  isCreatingNewConfig.value ? '新增数据源（未保存）' : '选择已绑定的数据源',
);
const savedConfigActionDisabled = computed(() => isCreatingNewConfig.value || selectedConfigId.value == null);
const systemHookAutoRegistrationDisabled = computed(() =>
  savedConfigActionDisabled.value || !isDockerMode.value || !form.value.systemHookEnabled,
);
const threadBudgetPreview = computed(() => {
  const serverCpuThreads = status.value?.availableProcessors;
  if (!serverCpuThreads) {
    return '状态加载后显示服务器实际同步线程预算';
  }
  const maxThreads = Math.max(1, form.value.maxSyncThreads ?? 16);
  const rawValue = Number(form.value.syncThreadValue ?? (form.value.syncThreadMode === 'CPU_RATIO' ? 0.8 : 2));
  const requestedThreads =
    form.value.syncThreadMode === 'CPU_RATIO'
      ? Math.floor(Math.max(0, rawValue) * serverCpuThreads)
      : Math.floor(Math.max(0, rawValue));
  const resolvedThreads = Math.min(maxThreads, Math.max(1, requestedThreads));
  const sourceText =
    form.value.syncThreadMode === 'CPU_RATIO'
      ? `CPU ${Math.round(rawValue * 100)}%`
      : `${Math.floor(rawValue)} 固定线程`;
  return `预计本次配置会使用 ${resolvedThreads} 个同步线程（${sourceText}，上限 ${maxThreads}，服务器检测 ${serverCpuThreads} 线程）`;
});
function handleSyncThreadModeChange(mode: string | number | boolean | undefined) {
  form.value.syncThreadValue = mode === 'CPU_RATIO' ? 0.8 : 2;
}
const systemHookStatusTagType = computed(() => {
  if (!isDockerMode.value || systemHookRegistrationLoading.value) {
    return 'info';
  }
  if (systemHookRegistration.value?.registered) {
    return 'success';
  }
  return systemHookRegistration.value?.configured ? 'warning' : 'info';
});
const systemHookStatusLabel = computed(() => {
  if (systemHookRegistrationLoading.value) {
    return '检测中';
  }
  if (!isDockerMode.value) {
    return '需手动注册';
  }
  if (systemHookRegistration.value?.registered) {
    return '已注册';
  }
  return systemHookRegistration.value?.configured ? '未注册' : '未配置';
});
const systemHookStatusMessage = computed(() => {
  if (systemHookRegistrationLoading.value) {
    return '正在异步检测 GitLab System Hook 状态，不影响页面其他信息加载。';
  }
  if (!isDockerMode.value) {
    return '直连模式需在 GitLab 管理后台手动注册 System Hook，平台无法自动检测注册状态。';
  }
  return systemHookRegistration.value?.message || '尚未检测 GitLab System Hook 状态。';
});
const duplicatePhysicalSourceMatches = computed(() => {
  const currentFingerprint = physicalSourceFingerprint(form.value);
  if (!currentFingerprint) {
    return [];
  }
  return configs.value.filter((candidate) => {
    if (!isSourceEnabled(candidate)) {
      return false;
    }
    if (candidate.id != null && selectedConfigId.value != null && candidate.id === selectedConfigId.value) {
      return false;
    }
    return physicalSourceFingerprint(candidate) === currentFingerprint;
  });
});
const duplicatePhysicalSourceWarning = computed(() => {
  if (!isSourceEnabled(form.value) || duplicatePhysicalSourceMatches.value.length === 0) {
    return '';
  }
  const names = duplicatePhysicalSourceMatches.value
    .map((item) => `${item.name || '未命名数据源'}（${item.sourceInstance || 'default'}）`)
    .join('、');
  return `当前数据源和 ${names} 指向同一个 GitLab 源库。平台不会阻止保存，但如果这些源同时启用，同一批 Issue、MR 和评论可能进入事实层多次，业务页面会看到重复数据。`;
});
const currentSourceText = computed(() => `${form.value.name || '未命名数据源'}（${form.value.sourceInstance || 'default'}）`);
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
    domains.push('代码走查事实');
  }
  if (health.issueFactLagging) {
    domains.push('系统测试/客户问题事实');
  }
  if (health.integrationTestFactLagging) {
    domains.push('集成测试事实');
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
    return '暂无诊断';
  }
  if (!health.enabled) {
    return '已停用';
  }
  if (health.missingRequiredMirrorTables.length > 0) {
    return '镜像不完整';
  }
  if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
    return '同步异常';
  }
  if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
    return '部分表异常';
  }
  if (health.factLayerLagging) {
    return '事实层滞后';
  }
  if (['RUNNING', 'QUEUED', 'RETRYING'].includes(health.currentStatus)) {
    return '同步中';
  }
  return '健康';
});
const currentSourceHealthSummary = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return '当前数据源还没有健康诊断结果。';
  }
  if (!health.enabled) {
    return '该数据源已停用，不会参与自动同步。';
  }
  if (health.missingRequiredMirrorTables.length > 0) {
    return '关键镜像表缺失，代码走查相关数据可能无法完整展示。';
  }
  if (health.factLayerLagging) {
    return '镜像数据已经更新，但部分展示或统计使用的事实层还没有刷新到最新。';
  }
  if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
    return health.latestLogMessage || '最近一次同步未成功，请查看同步日志并重新触发。';
  }
  if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
    return health.latestLogMessage || '最近一次同步部分表未成功，系统会继续按表级任务恢复。';
  }
  return '镜像表、事实层和最近同步状态未发现阻断问题。';
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
    return '暂无表级任务诊断';
  }
  return [
    `待执行 ${diagnostics.pendingTaskCount}`,
    `运行中 ${diagnostics.runningTaskCount}`,
    `重试中 ${diagnostics.retryingTaskCount}`,
    `失败 ${diagnostics.failedTaskCount + diagnostics.timedOutTaskCount}`,
  ].join(' · ');
});
function tableDirtyText(row: { dirty?: boolean; dirtyReason?: string | null; blockingRunId?: string | null }) {
  if (row.blockingRunId) {
    return `运行中：${row.blockingRunId}`;
  }
  if (row.dirty) {
    return row.dirtyReason ? `脏表：${row.dirtyReason}` : '脏表';
  }
  return '正常';
}
function tableWatermarkText(row: {
  lastError?: string | null;
  latestTaskError?: string | null;
  driftSummary?: string | null;
  lastWatermarkAt?: string | null;
  lastAppliedAt?: string | null;
  lastSuccessAt?: string | null;
}) {
  if (row.lastError || row.latestTaskError) {
    return row.lastError || row.latestTaskError;
  }
  if (row.driftSummary) {
    return row.driftSummary;
  }
  return formatDateTime(row.lastWatermarkAt || row.lastAppliedAt || row.lastSuccessAt);
}
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
    ElMessageBox.alert(buildPurgeSummaryHtml(result), '删除完成', {
      type: 'success',
      confirmButtonText: '知道了',
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
  void loadSystemHookRegistration(false);
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
      ElMessage.error(error instanceof Error ? error.message : '加载表级同步诊断失败');
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
  void loadSystemHookRegistration(false);
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
    systemHookSecret: '',
    lastFullSyncAt: null,
    lastIncrementalSyncAt: null,
  };
  newConfigSnapshot.value = JSON.stringify(form.value);
}

function isSourceEnabled(config: GitlabSyncConfig) {
  return config.sourceEnabled ?? config.enabled ?? true;
}

function physicalSourceFingerprint(config: GitlabSyncConfig) {
  if (config.sourceMode === 'DOCKER') {
    const containerName = normalizeFingerprintPart(config.dockerContainerName);
    return containerName ? `docker:${containerName}` : '';
  }
  const host = normalizeFingerprintPart(config.dbHost);
  const port = String(config.dbPort ?? 5432);
  const database = normalizeFingerprintPart(config.dbName);
  const username = normalizeFingerprintPart(config.dbUsername);
  if (!host || !database) {
    return '';
  }
  return `direct:${host}:${port}:${database}:${username}`;
}

function normalizeFingerprintPart(value: string | number | null | undefined) {
  return String(value ?? '').trim().toLowerCase();
}

async function cancelNewConfig() {
  if (JSON.stringify(form.value) !== newConfigSnapshot.value) {
    try {
      await ElMessageBox.confirm('放弃未保存的数据源配置？', '取消新增数据源', {
        type: 'warning',
        confirmButtonText: '放弃',
        cancelButtonText: '继续填写',
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
  void loadSystemHookRegistration(false);
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
            <div class="panel-title">GitLab 数据镜像设置</div>
          </div>
          <div class="panel-header-meta">
            <span class="header-secondary-text">{{ lastSyncDisplay }}</span>
            <el-tag v-if="loading" size="small" type="info">加载中</el-tag>
          </div>
        </div>
      </template>

      <el-form label-width="150px">
        <el-form-item label="GitLab 数据源">
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
            <el-button v-if="!isCreatingNewConfig" @click="createNewConfig">新增数据源</el-button>
            <el-button v-else @click="cancelNewConfig">取消新增</el-button>
          </div>
        </el-form-item>
        <el-form-item label="来源标识">
          <el-input v-model="form.sourceInstance" placeholder="例如 cc / dgm" />
        </el-form-item>
        <el-form-item label="数据源名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="启用数据源">
          <el-switch v-model="form.sourceEnabled" />
        </el-form-item>

        <el-divider>源数据库模式</el-divider>

        <el-form-item label="读取方式">
          <el-radio-group v-model="form.sourceMode">
            <el-radio value="DOCKER">Docker 模式</el-radio>
            <el-radio value="DIRECT">直连 PostgreSQL</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="isDockerMode">
          <el-form-item label="GitLab 容器名">
            <el-input v-model="form.dockerContainerName" placeholder="例如 gitlab-data-web-1" />
          </el-form-item>
          <el-form-item label="数据库名称">
            <el-input v-model="form.dbName" />
          </el-form-item>
          <el-form-item label="数据库用户名">
            <el-input v-model="form.dbUsername" />
          </el-form-item>
          <el-alert
            title="Docker 模式通过 docker exec 进入 GitLab 容器内部读取 PostgreSQL，不需要额外数据库密码。"
            type="info"
            :closable="false"
            show-icon
          />
        </template>

        <template v-else>
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="数据库主机">
                <el-input v-model="form.dbHost" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="数据库端口">
                <el-input-number v-model="form.dbPort" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="数据库名称">
                <el-input v-model="form.dbName" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="数据库用户名">
                <el-input v-model="form.dbUsername" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="数据库密码">
            <el-input v-model="form.dbPassword" type="password" show-password />
          </el-form-item>
        </template>

        <el-divider>同步策略</el-divider>

        <el-form-item label="自动同步">
          <el-switch v-model="form.autoSyncEnabled" />
        </el-form-item>
        <el-form-item label="补偿间隔(分钟)">
          <el-input-number v-model="form.compensationIntervalMinutes" :min="1" :max="720" />
        </el-form-item>
        <el-form-item label="同步线程模式">
          <el-radio-group v-model="form.syncThreadMode" @change="handleSyncThreadModeChange">
            <el-radio-button value="FIXED">固定线程数</el-radio-button>
            <el-radio-button value="CPU_RATIO">动态 CPU 比例</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.syncThreadMode === 'FIXED'" label="固定线程数">
          <el-input-number v-model="form.syncThreadValue" :min="1" :max="form.maxSyncThreads || 256" :precision="0" />
        </el-form-item>
        <el-form-item v-else label="CPU 使用比例">
          <el-input-number v-model="form.syncThreadValue" :min="0.1" :max="1" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="同步线程上限">
          <el-input-number v-model="form.maxSyncThreads" :min="1" :max="256" :precision="0" />
          <div class="form-help-text">{{ threadBudgetPreview }}</div>
        </el-form-item>
        <el-form-item label="白名单模式">
          <el-radio-group v-model="form.whitelistMode">
            <el-radio value="RECOMMENDED">推荐业务表</el-radio>
            <el-radio value="ALL">全部表</el-radio>
            <el-radio value="CUSTOM">自定义白名单</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.whitelistMode === 'CUSTOM'" label="自定义白名单">
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
                ? `已加载 ${whitelistOptions.length} 张可选表，推荐表 ${recommendedCount} 张。`
                : '进入自定义白名单时按需加载表选项，避免刷新设置页时等待。'
            }}
          </div>
        </el-form-item>

        <el-divider>System Hook 增量同步</el-divider>

        <el-form-item label="接收 System Hook">
          <el-switch v-model="form.systemHookEnabled" />
        </el-form-item>
        <el-form-item label="System Hook URL">
          <el-input :model-value="status?.systemHookUrl || ''" readonly />
        </el-form-item>
        <el-form-item label="System Hook Secret">
          <el-input v-model="form.systemHookSecret" />
        </el-form-item>
        <el-form-item label="System Hook 状态">
          <div class="system-hook-status-line">
            <el-tag :type="systemHookStatusTagType" round>
              {{ systemHookStatusLabel }}
            </el-tag>
            <span class="system-hook-status-text">
              {{ systemHookStatusMessage }}
            </span>
          </div>
        </el-form-item>
        <el-alert
          v-if="!isDockerMode"
          title="直连模式不会自动注册 GitLab System Hook；保存配置后，请在 GitLab 管理区域的系统 Hook 中手动填写 URL 和 Secret。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="duplicatePhysicalSourceWarning"
          :title="duplicatePhysicalSourceWarning"
          type="warning"
          :closable="false"
          show-icon
        />

        <el-space wrap>
          <el-button type="primary" :loading="saving" @click="saveConfig()">保存配置</el-button>
          <el-button
            :icon="Tools"
            :loading="testing"
            :disabled="saving || testing || savedConfigActionDisabled"
            @click="testConnection"
          >
            测试连接
          </el-button>
          <el-button
            :loading="registeringSystemHook"
            :disabled="systemHookAutoRegistrationDisabled"
            @click="registerSystemHook"
          >
            注册 System Hook
          </el-button>
          <el-button
            type="success"
            :loading="syncing"
            :disabled="!syncEnabled || savedConfigActionDisabled"
            @click="startFullSync"
          >
            首次全量同步
          </el-button>
          <el-button
            :loading="syncing"
            :disabled="!syncEnabled || savedConfigActionDisabled"
            @click="startIncrementalSync"
          >
            立即增量同步
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="cancelling"
            :disabled="!canCancel || savedConfigActionDisabled"
            @click="cancelSyncTask"
          >
            中止导入
          </el-button>
          <el-button type="danger" plain :disabled="savedConfigActionDisabled" @click="openPurgeDialog">
            删除镜像数据
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
            <div class="panel-title">数据源健康状态</div>
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
              <span>镜像表</span>
              <strong>{{ currentSourceHealth.existingMirrorTables }} / {{ currentSourceHealth.registeredMirrorTables }}</strong>
            </div>
            <div>
              <span>代码走查事实</span>
              <strong>{{ currentSourceHealth.mergeRequestFactCount }}</strong>
            </div>
            <div>
              <span>最新同步</span>
              <strong>{{ currentSourceHealth.latestLogStatus || currentSourceHealth.currentStatus || '-' }}</strong>
            </div>
          </div>
          <div class="source-health-fact-grid">
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.mergeRequestFactLagging }">
              <span>代码走查事实</span>
              <strong>{{ currentSourceHealth.mergeRequestFactCount }}</strong>
            </div>
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.issueFactLagging }">
              <span>系统测试/客户问题事实</span>
              <strong>{{ currentSourceHealth.issueFactCount }}</strong>
            </div>
            <div class="source-health-fact-item" :class="{ 'is-warning': currentSourceHealth.integrationTestFactLagging }">
              <span>集成测试事实</span>
              <strong>{{ currentSourceHealth.integrationTestFactCount }}</strong>
            </div>
          </div>

          <div class="source-health-detail-list">
            <div class="source-health-detail-row">
              <span>最新同步时间</span>
              <strong>{{ currentSourceHealth.latestLogFinishedAt || currentSourceHealth.currentStartedAt || '-' }}</strong>
            </div>
            <div class="source-health-detail-row">
              <span>事实层更新</span>
              <strong>{{ currentSourceHealth.latestFactUpdatedAt || '-' }}</strong>
            </div>
          </div>

          <div v-if="currentSourceHealth.missingRequiredMirrorTables.length" class="source-health-missing-panel">
            <div class="source-health-section-title">
              缺少关键镜像表
              <el-tag size="small" type="warning" round>
                {{ currentSourceHealth.missingRequiredMirrorTables.length }} 张
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
            <span>近期信息</span>
            <strong>{{ currentSourceHealth.latestLogMessage || currentSourceHealth.currentMessage }}</strong>
          </div>
          <el-alert
            v-if="currentSourceHealth.missingRequiredMirrorTables.length"
            class="source-health-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="`缺少 ${currentSourceHealth.missingRequiredMirrorTables.length} 张代码走查关键镜像表`"
            :description="currentSourceHealth.missingRequiredMirrorTables.slice(0, 3).join('、')"
          />
          <el-alert
            v-if="currentSourceHealth.factLayerLagging"
            class="source-health-alert"
            type="warning"
            :closable="false"
            show-icon
            :title="`${currentFactLaggingDomains.join('、') || '事实层'}可能滞后`"
            :description="currentSourceHealth.factLayerMessage || '镜像已更新，但统计事实尚未刷新到最新同步时间。'"
          />
        </template>
        <el-empty v-else description="暂无当前数据源诊断信息" />
      </el-card>

      <el-card shadow="never" class="panel-card table-sync-diagnostics-card">
        <template #header>
          <div class="panel-header">
            <div>
              <div class="panel-title">表级同步诊断</div>
              <div class="panel-subtitle">{{ tableSyncQueueSummary }}</div>
            </div>
            <el-button
              size="small"
              text
              :loading="tableSyncDiagnosticsLoading"
              :disabled="savedConfigActionDisabled"
              @click="loadTableSyncDiagnostics(true)"
            >
              刷新
            </el-button>
          </div>
        </template>
        <template v-if="tableSyncDiagnostics">
          <div class="table-sync-summary-grid">
            <div>
              <span>同步表</span>
              <strong>{{ tableSyncDiagnostics.tableCount }}</strong>
            </div>
            <div>
              <span>脏表</span>
              <strong>{{ tableSyncDiagnostics.dirtyTableCount }}</strong>
            </div>
            <div>
              <span>重试中</span>
              <strong>{{ tableSyncDiagnostics.retryingTaskCount }}</strong>
            </div>
          </div>
          <el-alert
            class="source-health-alert"
            type="info"
            :closable="false"
            show-icon
            title="脏表 = 源表和镜像表可能不一致，需要增量修复或全量校验；运行中 = 已进入当前 run 的处理队列；事实层滞后 = 镜像已更新，但统计事实表还没有刷新。"
          />
          <el-table
            v-loading="tableSyncDiagnosticsLoading"
            class="table-sync-diagnostics-table"
            :data="tableSyncDisplayRows"
            size="small"
          >
            <el-table-column prop="sourceTable" label="源表" min-width="130" show-overflow-tooltip />
            <el-table-column label="状态" width="86">
              <template #default="{ row }">
                <el-tag v-if="row.latestTaskStatus" size="small" :type="syncStatusTagType(row.latestTaskStatus)">
                  {{ syncStatusText(row.latestTaskStatus) }}
                </el-tag>
                <el-tag v-else size="small" type="info">无任务</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="脏表" width="64">
              <template #default="{ row }">
                <el-tag size="small" :type="row.blockingRunId ? 'warning' : row.dirty ? 'warning' : 'success'" effect="plain">
                  {{ row.blockingRunId ? '运行中' : row.dirty ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="语义/水位/漂移" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <span>{{ tableDirtyText(row) }} · {{ tableWatermarkText(row) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="tableSyncRows.length > tableSyncDisplayRows.length" class="table-sync-more">
            优先展示异常表，另有 {{ tableSyncRows.length - tableSyncDisplayRows.length }} 张表未展开。
          </div>
        </template>
        <el-empty v-else description="暂无表级同步诊断" />
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
        <div class="purge-hero-badge">高风险操作</div>
        <div class="purge-hero-title">此操作会真实删除本地镜像数据，且不可恢复。</div>
        <div class="purge-hero-description">
          {{ purgeDialogCopy.detail }}
        </div>
        <div class="purge-hero-description">
          当前作用范围：{{ currentSourceText }}
        </div>
      </div>

      <el-alert
        v-if="isPurging"
        class="purge-progress-alert"
        type="warning"
        :closable="false"
        show-icon
        title="正在删除镜像数据"
        :description="purgeProgressText"
      />

      <div class="purge-scope-cards">
        <label class="purge-scope-card" :class="{ active: purgeScope === 'MIRROR_DATA_ONLY', disabled: isPurging }">
          <input v-model="purgeScope" type="radio" value="MIRROR_DATA_ONLY" :disabled="isPurging" />
          <div class="purge-scope-card-title">删除镜像数据</div>
          <div class="purge-scope-card-desc">
            删除所有镜像表、镜像注册信息和旧镜像总表数据，不影响 GitLab 源端和本地非镜像数据。
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
          <div class="purge-scope-card-title">删除镜像数据（排除当前设置的白名单）</div>
          <div class="purge-scope-card-desc">
            仅删除当前白名单之外的镜像数据，保留当前白名单内的镜像内容，不影响 GitLab 源端和本地非镜像数据。
          </div>
        </label>
      </div>

      <div class="purge-warning-list">
        <div class="purge-warning-item">删除前请确认当前没有运行中或排队中的同步任务。</div>
        <div class="purge-warning-item">本操作只作用于本地镜像数据，不会删除 GitLab 源端数据。</div>
        <div class="purge-warning-item">本地非镜像业务数据不会被删除。</div>
      </div>

      <div class="purge-confirm-panel" :class="{ 'is-disabled': isPurging }">
        <div class="purge-confirm-label">请输入确认短语以继续</div>
        <div class="purge-confirm-phrase">{{ purgeDialogCopy.confirmText }}</div>
        <el-input v-model="purgeConfirmText" :placeholder="purgeDialogCopy.confirmText" :disabled="isPurging" />
      </div>
    </div>
    <template #footer>
      <el-button :disabled="isPurging" @click="closePurgeDialog">取消</el-button>
      <el-button type="danger" :loading="isPurging" :disabled="!purgeConfirmMatched || isPurging" @click="purgeMirrorData()">
        确认删除
      </el-button>
    </template>
  </el-dialog>
</template>
