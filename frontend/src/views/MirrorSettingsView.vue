<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
// 镜像设置页集中管理同步配置、白名单、System Hook 和清理动作，是数据入口的运维面板。
// 每组操作拆到独立 controller，页面只负责把表单状态和反馈动作组合起来。
import { RefreshRight, Tools } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type { GitlabSourceHealthResponse, GitlabSyncConfig, SyncRunDiagnosticsResponse } from '../types/api';
import SmartSelect from '../components/base/SmartSelect.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import { buildPurgeSummaryHtml, syncStatusText, translateSyncMessage } from './mirror-settings-helpers';
import MirrorRunMonitorPanel from './MirrorRunMonitorPanel.vue';
import MirrorRunTableTaskDrawer from './MirrorRunTableTaskDrawer.vue';
import MirrorSyncLogTable from './MirrorSyncLogTable.vue';
import MirrorSyncStatusCard from './MirrorSyncStatusCard.vue';
import { useFactRebuildDialog } from './useFactRebuildDialog';
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
const tableTaskDrawerVisible = ref(false);
const retryingFailedRun = ref(false);
const selectedConfigId = ref<number | undefined>(undefined);
const savedFormSnapshot = ref('');
const ACTIVE_SYNC_STATUSES = ['PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'CANCELLING'];

const form = ref<GitlabSyncConfig>({
  name: 'GitLab 默认数据源',
  enabled: true,
  sourceEnabled: true,
  sourceInstance: 'default',
  webBaseUrl: '',
  apiToken: '',
  delayLabelWritebackEnabled: false,
  matchModeEnabled: true,
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
  compensationIntervalMinutes: 360,
  compensationScheduleMode: 'INTERVAL',
  compensationTime: '03:30',
  compensationWindowStart: null,
  compensationWindowEnd: null,
  compensationMissedWindowPolicy: 'SKIP',
  fullCompensationEnabled: true,
  fullCompensationTime: '02:00',
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
  startIdleRefresh,
  stopRunningRefresh,
  syncRunningRefresh,
} = useMirrorStatusController({
  form,
  loadStatusData: () => api.getStatus(selectedConfigId.value),
  loadSystemHookRegistration: () => {
    void loadSystemHookRegistration(false);
  },
  notifyError: (message) => ElMessage.error(message),
  onRemoteConfigApplied: (config) => {
    savedFormSnapshot.value = formSnapshot(config);
  },
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
  startFullCompensationSync,
  cancelSyncTask,
  showSubmissionFeedback,
} = useMirrorSyncActionsController({
  form,
  saveConfigData: async (config) => {
    const saved = await api.saveConfig(config);
    await loadConfigs();
    selectedConfigId.value = saved.id;
    savedFormSnapshot.value = formSnapshot(saved);
    return saved;
  },
  testConnectionData: () => api.testConnection(selectedConfigId.value),
  startFullSyncData: () => api.startFullSync(selectedConfigId.value),
  startIncrementalSyncData: () => api.startIncrementalSync(selectedConfigId.value),
  startFullCompensationSyncData: () => api.startFullCompensationSync(selectedConfigId.value),
  cancelSyncData: () => api.cancelSync(selectedConfigId.value),
  loadStatus: (showError, blocking, options) => loadStatus(showError, blocking, options),
  loadSystemHookRegistration: () => {
    void loadSystemHookRegistration(false);
  },
  notifySuccess: (message) => ElMessage.success(message),
  notifyWarning: (message) => ElMessage.warning(message),
  notifyInfo: (message) => ElMessage.info(message),
  notifyError: (message) => ElMessage.error(message),
  hasActiveSync: () => Boolean(currentTask.value?.status && ACTIVE_SYNC_STATUSES.includes(currentTask.value.status)),
  hasUnsavedChanges: () => isFormDirty.value,
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
const syncEnabled = computed(() => sourceEnabled.value);
const savedConfigActionDisabled = computed(() => selectedConfigId.value == null);
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
const isFormDirty = computed(() => savedFormSnapshot.value !== '' && formSnapshot(form.value) !== savedFormSnapshot.value);
const currentSourceText = computed(() => form.value.name || 'GitLab 数据镜像');
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
const currentSourceLatestSyncStatusText = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return '-';
  }
  const rawStatus = health.latestLogStatus || health.currentStatus;
  return rawStatus ? syncStatusText(rawStatus) : '-';
});
const currentSourceHealthMessageText = computed(() => {
  const health = currentSourceHealth.value;
  if (!health) {
    return '';
  }
  return translateSyncMessage(health.latestLogMessage || health.currentMessage) || '';
});
const missingRequiredMirrorTablesPreview = computed(() => {
  const tables = currentSourceHealth.value?.missingRequiredMirrorTables ?? [];
  return {
    visible: tables.slice(0, 5),
    hiddenCount: Math.max(tables.length - 5, 0),
  };
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
const hasActiveSyncTask = computed(() => {
  const currentStatus = currentTask.value?.status;
  return currentStatus != null && ACTIVE_SYNC_STATUSES.includes(currentStatus);
});
const {
  factRebuildDialogVisible,
  factRebuildConfirmText,
  factRebuildCountdownSeconds,
  factRebuildConfirmationPhrase,
  isFactRebuilding,
  factRebuildReady,
  openFactRebuildDialog: showFactRebuildDialog,
  closeFactRebuildDialog,
  rebuildFacts,
  handleFactRebuildDialogBeforeClose,
  disposeFactRebuildDialog,
} = useFactRebuildDialog({
  rebuildFacts: async () => {
    const configId = selectedConfigId.value;
    if (configId == null) {
      throw new Error('请先保存当前数据源配置后再重建事实层');
    }
    return api.rebuildFacts(configId);
  },
  refreshRunStatus: async () => {
    await refreshCurrentStatus();
  },
  notifyError: (message) => ElMessage.error(message),
  showResult: (result) =>
    ElMessageBox.alert(
      `当前数据源：${currentSourceText.value}\n运行编号：${result.runId || '-'}\n${result.message}\n可在当前任务和最近同步日志中查看进度与结果。`,
      '事实层重建已提交',
      {
        type: 'success',
        confirmButtonText: '知道了',
      },
    ),
});
const factRebuildActionDisabled = computed(
  () => savedConfigActionDisabled.value || loading.value || hasActiveSyncTask.value || isFactRebuilding.value,
);
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

watch(
  () => formSnapshot(form.value),
  (snapshot) => {
    if (savedFormSnapshot.value === '') {
      savedFormSnapshot.value = snapshot;
    }
  },
);

async function initializePage() {
  try {
    await loadConfigs();
    await loadMirrorSection('同步状态', () => loadStatus(false, false));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 GitLab 数据镜像设置失败');
  } finally {
    initialized.value = true;
  }
  void loadDeferredMirrorSections();
}

async function loadDeferredMirrorSections() {
  await Promise.all([
    loadMirrorSection('数据源健康状态', loadSourceHealth),
    loadMirrorSection('表级同步诊断', () => loadTableSyncDiagnostics(false)),
    loadMirrorSection('System Hook 状态', () => loadSystemHookRegistration(false)),
  ]);
}

async function loadMirrorSection(sectionName: string, loader: () => Promise<void>) {
  try {
    await loader();
  } catch (error) {
    console.warn(`${sectionName} 加载失败`, error);
  }
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
  if (selectedConfigId.value == null) {
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

function normalizeFingerprintPart(value: string | number | null | undefined) {
  return String(value ?? '').trim().toLowerCase();
}

function formSnapshot(config: GitlabSyncConfig) {
  return JSON.stringify({
    id: config.id ?? null,
    name: config.name ?? '',
    enabled: Boolean(config.sourceEnabled ?? config.enabled),
    sourceEnabled: Boolean(config.sourceEnabled ?? config.enabled),
    sourceInstance: normalizeFingerprintPart(config.sourceInstance) || 'default',
    autoSyncEnabled: Boolean(config.autoSyncEnabled),
    sourceMode: config.sourceMode ?? 'DOCKER',
    whitelistMode: config.whitelistMode ?? 'RECOMMENDED',
    whitelistTables: [...(config.whitelistTables ?? [])].sort(),
    dbHost: normalizeFingerprintPart(config.dbHost),
    dbPort: Number(config.dbPort ?? 5432),
    dbName: normalizeFingerprintPart(config.dbName),
    dbUsername: normalizeFingerprintPart(config.dbUsername),
    dbPassword: config.dbPassword ?? '',
    apiToken: config.apiToken ?? '',
    delayLabelWritebackEnabled: Boolean(config.delayLabelWritebackEnabled),
    matchModeEnabled: config.matchModeEnabled ?? true,
    dockerContainerName: normalizeFingerprintPart(config.dockerContainerName),
    systemHookSecret: config.systemHookSecret ?? '',
    systemHookEnabled: Boolean(config.systemHookEnabled),
    systemHookProjectId: config.systemHookProjectId ?? null,
    compensationIntervalMinutes: Number(config.compensationIntervalMinutes ?? 360),
    compensationScheduleMode: config.compensationScheduleMode ?? 'INTERVAL',
    compensationTime: config.compensationTime ?? '03:30',
    compensationWindowStart: config.compensationWindowStart ?? null,
    compensationWindowEnd: config.compensationWindowEnd ?? null,
    compensationMissedWindowPolicy: config.compensationMissedWindowPolicy ?? 'SKIP',
    fullCompensationEnabled: config.fullCompensationEnabled ?? true,
    fullCompensationTime: config.fullCompensationTime ?? '02:00',
    syncThreadMode: config.syncThreadMode ?? 'FIXED',
    syncThreadValue: Number(config.syncThreadValue ?? 2),
    maxSyncThreads: Number(config.maxSyncThreads ?? 16),
  });
}

async function confirmDiscardUnsavedChanges(message = '存在未保存的同步策略修改，确认离开将丢失这些修改。') {
  if (!isFormDirty.value) {
    return true;
  }
  try {
    await ElMessageBox.confirm(message, '未保存修改', {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
    });
    return true;
  } catch {
    return false;
  }
}

async function refreshCurrentStatus() {
  await refreshStatus();
  await Promise.all([
    loadMirrorSection('数据源健康状态', loadSourceHealth),
    loadMirrorSection('表级同步诊断', () => loadTableSyncDiagnostics(false)),
  ]);
}

function openTableTaskDrawer() {
  tableTaskDrawerVisible.value = true;
}

async function cancelSyncFromMonitor() {
  await cancelSyncTask();
  await loadTableSyncDiagnostics(false);
}

async function retryFailedRun() {
  if (savedConfigActionDisabled.value || retryingFailedRun.value) {
    return;
  }
  if (isFormDirty.value) {
    ElMessage.warning('当前设置尚未保存，请先保存配置后再重试同步任务。');
    return;
  }
  retryingFailedRun.value = true;
  try {
    const result = await api.retryFailedSync(selectedConfigId.value);
    showSubmissionFeedback(result);
    await loadStatus(false, false);
    await Promise.all([
      loadMirrorSection('数据源健康状态', loadSourceHealth),
      loadMirrorSection('表级同步诊断', () => loadTableSyncDiagnostics(false)),
    ]);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    retryingFailedRun.value = false;
  }
}

function openCurrentSourceFactRebuildDialog() {
  if (factRebuildActionDisabled.value) {
    if (hasActiveSyncTask.value) {
      ElMessage.warning('当前数据源正在同步或刷新事实层，请等待任务完成后再重建。');
    }
    return;
  }
  showFactRebuildDialog();
}

async function rebuildCurrentSourceFacts() {
  if (factRebuildActionDisabled.value) {
    if (hasActiveSyncTask.value) {
      ElMessage.warning('当前数据源正在同步或刷新事实层，请等待任务完成后再重建。');
    }
    return;
  }
  await rebuildFacts();
}

onMounted(async () => {
  await initializePage();
  if (!currentTask.value?.status || !ACTIVE_SYNC_STATUSES.includes(currentTask.value.status)) {
    startIdleRefresh();
  }
});

onBeforeUnmount(() => {
  stopRunningRefresh();
  disposeFactRebuildDialog();
});

onBeforeRouteLeave(async () => {
  return confirmDiscardUnsavedChanges();
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
        <el-form-item label="数据源名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="GitLab Web 地址">
          <el-input v-model="form.webBaseUrl" placeholder="例如 http://gitlab.company.local" />
        </el-form-item>
        <el-form-item label="Project Access Token">
          <el-input
            v-model="form.apiToken"
            type="password"
            show-password
            autocomplete="off"
            placeholder="用于读取校验和延期标签写回"
          />
          <div class="form-help-text">建议使用 CC_Product 项目的 Project Access Token，scope 使用 api。</div>
        </el-form-item>
        <el-form-item label="延期标签写回">
          <el-switch v-model="form.delayLabelWritebackEnabled" />
          <div class="form-help-text">关闭时仍会监控延期事实，不会调用 GitLab API 写标签。</div>
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
        <el-form-item label="自动补偿模式">
          <el-radio-group v-model="form.compensationScheduleMode">
            <el-radio-button value="INTERVAL">按间隔</el-radio-button>
            <el-radio-button value="DAILY_TIME">每日定时</el-radio-button>
            <el-radio-button value="WINDOWED_INTERVAL">窗口内间隔</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="补偿间隔(分钟)">
          <el-input-number
            v-model="form.compensationIntervalMinutes"
            :min="1"
            :max="720"
            :disabled="form.compensationScheduleMode === 'DAILY_TIME'"
          />
        </el-form-item>
        <el-form-item label="自动补偿执行时间">
          <el-time-picker
            v-model="form.compensationTime"
            format="HH:mm"
            value-format="HH:mm"
            :disabled="form.compensationScheduleMode !== 'DAILY_TIME'"
            placeholder="选择时间"
          />
        </el-form-item>
        <el-form-item label="自动补偿运行窗口">
          <div class="mirror-window-row">
            <el-time-picker
              v-model="form.compensationWindowStart"
              format="HH:mm"
              value-format="HH:mm"
              :disabled="form.compensationScheduleMode !== 'WINDOWED_INTERVAL'"
              placeholder="开始时间"
            />
            <span class="mirror-window-separator">至</span>
            <el-time-picker
              v-model="form.compensationWindowEnd"
              format="HH:mm"
              value-format="HH:mm"
              :disabled="form.compensationScheduleMode !== 'WINDOWED_INTERVAL'"
              placeholder="结束时间"
            />
          </div>
        </el-form-item>
        <el-form-item label="错过窗口策略">
          <el-select
            v-model="form.compensationMissedWindowPolicy"
            :disabled="form.compensationScheduleMode !== 'WINDOWED_INTERVAL'"
            fit-input-width
            popper-class="platform-select-dropdown"
          >
            <el-option label="跳过，等待下个窗口" value="SKIP" />
            <el-option label="下个窗口补跑" value="RUN_NEXT_WINDOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="全量补偿对账">
          <el-switch v-model="form.fullCompensationEnabled" />
        </el-form-item>
        <el-form-item label="每日执行时间">
          <el-time-picker
            v-model="form.fullCompensationTime"
            format="HH:mm"
            value-format="HH:mm"
            :disabled="!form.fullCompensationEnabled"
            placeholder="选择时间"
          />
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
          <el-alert
            v-if="form.whitelistMode === 'ALL'"
            title="全部表模式将同步源数据库中所有可发现的表。首次全量同步耗时较长，刷新最新数据会自动跳过无变更的表。"
            type="info"
            :closable="false"
            show-icon
            style="margin-top: 8px"
          />
          <div v-if="form.whitelistMode === 'ALL' && whitelistOptionsLoaded" class="form-help-text">
            将同步 {{ whitelistOptions.length }} 张表（其中 {{ recommendedCount }} 张为推荐业务表）。
          </div>
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

        <el-divider>System Hook 唤醒</el-divider>

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
        <div class="mirror-action-panel">
          <div class="mirror-action-groups">
            <div class="mirror-action-group">
              <div class="mirror-action-group__label">配置校验</div>
              <el-button-group class="mirror-button-group">
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
              </el-button-group>
            </div>

            <div class="mirror-action-group mirror-action-group--sync">
              <div class="mirror-action-group__label">数据同步</div>
              <el-button-group class="mirror-button-group">
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
                  刷新最新数据
                </el-button>
                <el-button
                  :loading="syncing"
                  :disabled="!syncEnabled || savedConfigActionDisabled"
                  title="按源库对镜像库做全量对账，纠正差异并清理源库不存在的镜像行"
                  @click="startFullCompensationSync"
                >
                  全量补偿对账
                </el-button>
              </el-button-group>
            </div>

            <div class="mirror-action-group mirror-action-group--fact">
              <div class="mirror-action-group__label">事实层维护</div>
              <el-button
                type="warning"
                plain
                :icon="RefreshRight"
                :loading="isFactRebuilding"
                :disabled="factRebuildActionDisabled"
                title="基于当前本地镜像表重新计算议题、代码走查和集成测试事实；不会重新拉取 GitLab 数据。"
                @click="openCurrentSourceFactRebuildDialog"
              >
                重建当前数据源事实层
              </el-button>
            </div>

            <div class="mirror-action-group mirror-action-group--danger">
              <div class="mirror-action-group__label">危险操作</div>
              <el-space wrap :size="8">
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
            </div>
          </div>
        </div>
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

      <MirrorRunMonitorPanel
        :status="status"
        :diagnostics="tableSyncDiagnostics"
        :refreshing="refreshing || tableSyncDiagnosticsLoading"
        :cancelling="cancelling"
        :retrying="retryingFailedRun"
        :disabled="savedConfigActionDisabled"
        @refresh="refreshCurrentStatus"
        @cancel="cancelSyncFromMonitor"
        @retry="retryFailedRun"
        @open-table-tasks="openTableTaskDrawer"
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
              <strong>{{ currentSourceLatestSyncStatusText }}</strong>
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

          <div v-if="currentSourceHealthMessageText" class="source-health-message">
            <span>近期信息</span>
            <strong>{{ currentSourceHealthMessageText }}</strong>
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
      </div>
    </div>
  </PageStateShell>

  <MirrorRunTableTaskDrawer v-model="tableTaskDrawerVisible" :diagnostics="tableSyncDiagnostics" />

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
        <div class="purge-warning-item">删除前请确认当前没有正在处理或等待处理的同步任务。</div>
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

  <el-dialog
    v-model="factRebuildDialogVisible"
    title="重建事实层"
    width="640px"
    class="fact-rebuild-dialog"
    :show-close="!isFactRebuilding"
    :close-on-click-modal="false"
    :close-on-press-escape="!isFactRebuilding"
    :before-close="handleFactRebuildDialogBeforeClose"
    @close="closeFactRebuildDialog"
  >
    <div class="fact-rebuild-dialog-body">
      <div class="fact-rebuild-summary">
        <div class="fact-rebuild-summary__badge">受保护操作</div>
        <div class="fact-rebuild-summary__title">重新计算当前数据源的全部事实层</div>
        <div class="fact-rebuild-summary__description">
          此操作只读取当前本地镜像表，不会重新拉取 GitLab 数据，也不会删除镜像或业务源数据。
        </div>
        <div class="fact-rebuild-summary__description">当前作用范围：{{ currentSourceText }}</div>
      </div>

      <div class="fact-rebuild-scope-list" aria-label="重建范围">
        <div>
          <strong>议题事实</strong>
          <span>系统测试、客户问题、统计看板和记录页</span>
        </div>
        <div>
          <strong>代码走查事实</strong>
          <span>代码走查页面、看板和导出</span>
        </div>
        <div>
          <strong>集成测试事实</strong>
          <span>集成测试数据</span>
        </div>
      </div>

      <el-alert
        v-if="hasActiveSyncTask"
        type="warning"
        :closable="false"
        show-icon
        title="当前数据源正在同步或刷新"
        description="请等待当前任务结束后再提交重建，避免读取同步过程中的镜像数据。"
      />

      <el-alert
        v-if="isFactRebuilding"
        type="warning"
        :closable="false"
        show-icon
        title="正在重建事实层"
        description="请勿关闭页面或重复提交；完成后会刷新统计和记录页快照。"
      />

      <div class="fact-rebuild-confirm-panel" :class="{ 'is-disabled': isFactRebuilding }">
        <div class="fact-rebuild-confirm-panel__label">请输入确认短语以继续</div>
        <div class="fact-rebuild-confirm-panel__phrase">{{ factRebuildConfirmationPhrase }}</div>
        <el-input
          v-model="factRebuildConfirmText"
          :placeholder="factRebuildConfirmationPhrase"
          :disabled="isFactRebuilding"
        />
        <div class="fact-rebuild-confirm-panel__countdown" :class="{ 'is-ready': factRebuildCountdownSeconds === 0 }">
          {{
            factRebuildCountdownSeconds > 0
              ? `安全等待中，还需 ${factRebuildCountdownSeconds} 秒`
              : '安全等待已结束，可确认提交'
          }}
        </div>
      </div>
    </div>
    <template #footer>
      <el-button :disabled="isFactRebuilding" @click="closeFactRebuildDialog">取消</el-button>
      <el-button
        class="fact-rebuild-submit-button"
        type="warning"
        :loading="isFactRebuilding"
        :disabled="!factRebuildReady || factRebuildActionDisabled"
        @click="rebuildCurrentSourceFacts"
      >
        {{ factRebuildCountdownSeconds > 0 ? `请等待 ${factRebuildCountdownSeconds} 秒` : '确认重建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.mirror-action-panel {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.mirror-action-groups {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}

.mirror-action-group {
  display: grid;
  gap: 8px;
  min-width: max-content;
}

.mirror-action-group--sync {
  flex: 1 1 360px;
}

.mirror-action-group--danger {
  margin-left: auto;
}

.mirror-action-group__label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.mirror-button-group {
  display: inline-flex;
  flex-wrap: wrap;
  row-gap: 8px;
}

.mirror-button-group :deep(.el-button) {
  margin-left: 0;
}

.mirror-window-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mirror-window-separator {
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}

@media (max-width: 1280px) {
  .mirror-action-group,
  .mirror-action-group--sync,
  .mirror-action-group--danger {
    flex: 1 1 100%;
    margin-left: 0;
  }
}
</style>
