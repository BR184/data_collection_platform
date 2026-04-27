<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh, Tools } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '../api';
import type {
  GitlabSyncConfig,
  GitlabSyncLog,
  GitlabSyncStatus,
  GitlabSyncTask,
  GitlabSyncType,
  MirrorPurgeResult,
  MirrorPurgeScope,
  MirrorStatusResponse,
  SyncProgress,
  SyncSubmissionResponse,
  TableWhitelistOption,
} from '../types/api';
import SmartSelect from '../components/base/SmartSelect.vue';
import PageStateShell from '../components/base/PageStateShell.vue';

const SYNC_TYPE_LABELS: Record<GitlabSyncType, string> = {
  FULL: '全量同步',
  INCREMENTAL: '增量同步',
  COMPENSATION: '补偿同步',
  WEBHOOK: 'Webhook同步',
  PURGE: '删除镜像数据',
};

const SYNC_TYPE_TAG_TYPES: Record<GitlabSyncType, '' | 'danger' | 'info' | 'success' | 'warning'> = {
  FULL: 'warning',
  INCREMENTAL: 'info',
  COMPENSATION: 'success',
  WEBHOOK: '',
  PURGE: 'danger',
};

const SYNC_STATUS_LABELS: Record<GitlabSyncStatus | 'IDLE', string> = {
  PENDING: '等待中',
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELLED: '已中止',
  TIMEOUT: '已超时',
  CANCELLING: '中止中',
  IDLE: '空闲',
};

const SYNC_STATUS_TAG_TYPES: Record<GitlabSyncStatus | 'IDLE', 'danger' | 'info' | 'success' | 'warning'> = {
  PENDING: 'warning',
  QUEUED: 'warning',
  RUNNING: 'warning',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELLED: 'info',
  TIMEOUT: 'danger',
  CANCELLING: 'warning',
  IDLE: 'info',
};

const initialized = ref(false);
const loading = ref(false);
const refreshing = ref(false);
const saving = ref(false);
const syncing = ref(false);
const cancelling = ref(false);
const registeringWebhook = ref(false);
const purging = ref<MirrorPurgeScope | null>(null);
const purgeDialogVisible = ref(false);
const purgeScope = ref<MirrorPurgeScope>('MIRROR_DATA_ONLY');
const purgeConfirmText = ref('');
const status = ref<MirrorStatusResponse | null>(null);
const webhookRegistrationState = ref<MirrorStatusResponse['webhookRegistration'] | null>(null);
const webhookRegistrationLoading = ref(false);
const whitelistOptions = ref<TableWhitelistOption[]>([]);
const whitelistOptionsLoading = ref(false);
const whitelistOptionsLoaded = ref(false);
const refreshTimer = ref<number | null>(null);

const form = ref<GitlabSyncConfig>({
  name: 'GitLab 默认数据源',
  enabled: true,
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
  webhookProjectId: null,
  compensationIntervalMinutes: 10,
});

const recommendedCount = computed(() => whitelistOptions.value.filter((item) => item.recommended).length);
const whitelistSelectOptions = computed(() =>
  whitelistOptions.value.map((option) => ({
    label: `${option.label} (${option.tableName})`,
    value: option.tableName,
  })),
);
const isDockerMode = computed(() => form.value.sourceMode === 'DOCKER');
const syncEnabled = computed(() => form.value.autoSyncEnabled);
const progress = computed<SyncProgress | null>(() => status.value?.progress ?? null);
const currentTask = computed<GitlabSyncTask | null>(() => status.value?.currentTask ?? null);
const recentLogs = computed(() => status.value?.logs ?? []);
const latestLog = computed(() => recentLogs.value[0] ?? null);
const webhookRegistration = computed(() => webhookRegistrationState.value ?? null);
const activePollingStatuses: GitlabSyncStatus[] = ['PENDING', 'QUEUED', 'RUNNING', 'CANCELLING'];
const canCancel = computed(() => currentTask.value != null && activePollingStatuses.includes(currentTask.value.status));
const isPurging = computed(() => purging.value != null);
const lastSyncDisplay = computed(() => {
  const lastFinishedAt = latestLog.value?.finishedAt || latestLog.value?.startedAt;
  if (!lastFinishedAt) {
    return '最近操作：暂无';
  }
  return `最近操作：${formatDateTime(lastFinishedAt)}（${syncStatusText(latestLog.value?.status ?? 'IDLE')}）`;
});

const progressPercent = computed(() => {
  const current = progress.value;
  if (!current) {
    return 0;
  }
  if (current.totalTables <= 0) {
    return currentTask.value && activePollingStatuses.includes(currentTask.value.status) ? 5 : 0;
  }
  return Math.min(100, Math.round((current.completedTables / current.totalTables) * 100));
});

const displayStatus = computed(() => {
  const raw = status.value?.currentStatus ?? 'IDLE';
  if (raw !== 'IDLE') {
    return { text: syncStatusText(raw), type: syncStatusTagType(raw) };
  }
  const log = latestLog.value;
  if (log != null) {
    return { text: `最近操作${syncStatusText(log.status)}`, type: syncStatusTagType(log.status) };
  }
  return { text: syncStatusText('IDLE'), type: syncStatusTagType('IDLE') };
});

const phaseText = computed(() => {
  const phase = progress.value?.phase;
  switch (phase) {
    case 'FULL_SYNC':
      return '首次全量同步';
    case 'INCREMENTAL_SYNC':
      return '增量同步';
    case 'COMPENSATION_SYNC':
      return '补偿同步';
    default:
      return currentTask.value?.status === 'QUEUED' ? '排队中' : '空闲';
  }
});

const progressHint = computed(() => {
  const current = progress.value;
  if (!current) {
    if (currentTask.value?.status === 'QUEUED') {
      return '当前已有同步任务执行中，本次请求已进入队列等待。';
    }
    if (currentTask.value?.status === 'CANCELLING') {
      return '已收到中止请求，正在等待当前批次安全退出。';
    }
    return '当前没有正在执行的同步任务。';
  }
  if (current.currentTable) {
    return `正在处理表 ${current.currentTable}，已同步 ${current.syncedRecords} 条记录。`;
  }
  return '同步任务已启动，正在准备表扫描。';
});

const currentMessageText = computed(() => {
  const rawMessage = status.value?.currentMessage?.trim() ?? '';
  if (!rawMessage) {
    return '当前没有正在执行的同步任务。';
  }
  return translateSyncMessage(rawMessage, currentTask.value?.taskType);
});

async function loadStatus(showError = true, blocking = true) {
  loading.value = blocking;
  try {
    const data = await api.getStatus();
    status.value = data;
    form.value = {
      ...data.config,
      name: data.config.name || 'GitLab 默认数据源',
      enabled: data.config.autoSyncEnabled ?? data.config.enabled ?? true,
      autoSyncEnabled: data.config.autoSyncEnabled ?? data.config.enabled ?? true,
      sourceMode: data.config.sourceMode ?? 'DOCKER',
      whitelistTables: data.config.whitelistTables ?? [],
      dockerContainerName: data.config.dockerContainerName ?? 'gitlab-data-web-1',
      dbHost: data.config.dbHost ?? 'localhost',
      dbPort: data.config.dbPort ?? 5432,
      dbName: data.config.dbName ?? 'gitlabhq_production',
      dbUsername: data.config.dbUsername ?? 'gitlab',
      dbPassword: data.config.dbPassword ?? '',
      webhookProjectId: data.config.webhookProjectId ?? null,
    };
  } catch (error) {
    if (showError) {
      ElMessage.error((error as Error).message);
    }
  } finally {
    loading.value = false;
  }
}

async function refreshStatus() {
  refreshing.value = true;
  try {
    await loadStatus(false, false);
    void loadWebhookRegistration(false);
  } finally {
    refreshing.value = false;
  }
}

async function loadWebhookRegistration(showError = false) {
  webhookRegistrationLoading.value = true;
  try {
    webhookRegistrationState.value = await api.getWebhookRegistrationStatus();
  } catch (error) {
    webhookRegistrationState.value = null;
    if (showError) {
      ElMessage.error((error as Error).message);
    }
  } finally {
    webhookRegistrationLoading.value = false;
  }
}

async function ensureWhitelistOptions(force = false) {
  if (whitelistOptionsLoading.value) {
    return;
  }
  if (!force && whitelistOptionsLoaded.value) {
    return;
  }
  whitelistOptionsLoading.value = true;
  try {
    whitelistOptions.value = await api.getWhitelistOptions();
    whitelistOptionsLoaded.value = true;
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    whitelistOptionsLoading.value = false;
  }
}

function startRunningRefresh() {
  stopRunningRefresh();
  refreshTimer.value = window.setInterval(() => {
    void loadStatus(false, false);
  }, 4000);
}

function stopRunningRefresh() {
  if (refreshTimer.value != null) {
    window.clearInterval(refreshTimer.value);
    refreshTimer.value = null;
  }
}

watch(
  () => currentTask.value?.status,
  (nextStatus) => {
    if (nextStatus && activePollingStatuses.includes(nextStatus)) {
      startRunningRefresh();
    } else {
      stopRunningRefresh();
    }
  },
);

watch(
  () => form.value.whitelistMode,
  (nextMode) => {
    if (nextMode === 'CUSTOM') {
      void ensureWhitelistOptions();
    }
  },
);

async function saveConfig(showSuccess = true) {
  saving.value = true;
  try {
    form.value.enabled = form.value.autoSyncEnabled;
    await api.saveConfig(form.value);
    if (showSuccess) {
      ElMessage.success('配置已保存');
    }
    await loadStatus(false, false);
    void loadWebhookRegistration(false);
  } catch (error) {
    ElMessage.error((error as Error).message);
    throw error;
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  try {
    await saveConfig(false);
    await api.testConnection();
    ElMessage.success('连接测试成功');
    await loadStatus(false, false);
  } catch (error) {
    ElMessage.error((error as Error).message);
  }
}

async function startFullSync() {
  syncing.value = true;
  try {
    await saveConfig(false);
    const result = await api.startFullSync();
    showSubmissionFeedback(result);
    await loadStatus(false, false);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    syncing.value = false;
  }
}

async function startIncrementalSync() {
  syncing.value = true;
  try {
    await saveConfig(false);
    const result = await api.startIncrementalSync();
    showSubmissionFeedback(result);
    await loadStatus(false, false);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    syncing.value = false;
  }
}

function showSubmissionFeedback(result: SyncSubmissionResponse) {
  if (result.action === 'CREATED') {
    ElMessage.success(result.message);
    return;
  }
  if (result.action === 'QUEUED') {
    ElMessage.warning(result.message);
    return;
  }
  ElMessage.info(result.message);
}

async function cancelSyncTask() {
  cancelling.value = true;
  try {
    const result = await api.cancelSync();
    if (result.accepted) {
      ElMessage.success('已提交中止请求');
    } else {
      ElMessage.info('当前没有可中止的任务');
    }
    await loadStatus(false, false);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    cancelling.value = false;
  }
}

function formatDateTime(value?: string | null) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', { hour12: false });
}

function formatDuration(log: GitlabSyncLog) {
  if (!log.finishedAt || !log.startedAt) {
    return activePollingStatuses.includes(log.status) ? '进行中' : '-';
  }
  const start = new Date(log.startedAt).getTime();
  const end = new Date(log.finishedAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return '-';
  }
  const seconds = Math.max(0, Math.round((end - start) / 1000));
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  const remain = seconds % 60;
  return `${minutes} 分 ${remain} 秒`;
}

function logStatusType(statusValue: GitlabSyncStatus) {
  return syncStatusTagType(statusValue);
}

function logStatusText(statusValue: GitlabSyncStatus) {
  return syncStatusText(statusValue);
}

function syncLogMessage(log: GitlabSyncLog) {
  const message = translateSyncMessage(log.message, log.syncType);
  if (message) {
    return message;
  }
  switch (log.syncType) {
    case 'WEBHOOK':
      return 'Webhook 触发的业务对象精确更新。';
    case 'INCREMENTAL':
      return '人工触发的恢复型时间窗口增量同步。';
    case 'COMPENSATION':
      return '定时补偿窗口兜底同步。';
    case 'FULL':
      return '全量重建或初始化同步。';
    case 'PURGE':
      return '删除镜像数据。';
    default:
      return '-';
  }
}

async function registerWebhook() {
  registeringWebhook.value = true;
  try {
    await saveConfig(false);
    await api.registerWebhook();
    ElMessage.success('GitLab Webhook 已注册');
    await loadStatus(false, false);
    await loadWebhookRegistration(false);
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    registeringWebhook.value = false;
  }
}

const purgeDialogCopy = computed(() => {
  if (purgeScope.value === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST') {
    return {
      title: '删除镜像数据（排除当前白名单）',
      confirmText: '删除白名单外镜像数据',
      detail:
        '将真实删除当前白名单之外的镜像表、镜像注册信息和旧镜像总表数据。当前白名单内的镜像数据会保留，GitLab 源端和本地非镜像数据不会受影响。',
    };
  }
  return {
    title: '删除镜像数据',
    confirmText: '删除镜像数据',
    detail:
      '将真实删除全部镜像表、镜像注册信息和旧镜像总表数据。GitLab 源端和本地非镜像数据不会受影响，此操作不可恢复。',
  };
});

const purgeConfirmMatched = computed(() => purgeConfirmText.value === purgeDialogCopy.value.confirmText);
const purgeProgressText = computed(() =>
  purging.value === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
    ? '正在删除白名单外的本地镜像数据，请勿关闭页面或重复操作。'
    : '正在删除本地镜像数据，请勿关闭页面或重复操作。',
);

function openPurgeDialog() {
  purgeScope.value = 'MIRROR_DATA_ONLY';
  purgeConfirmText.value = '';
  purgeDialogVisible.value = true;
}

function closePurgeDialog() {
  if (purging.value) {
    return;
  }
  purgeDialogVisible.value = false;
  purgeConfirmText.value = '';
}

async function purgeMirrorData() {
  if (!purgeConfirmMatched.value) {
    return;
  }

  purging.value = purgeScope.value;
  let result: MirrorPurgeResult | null = null;
  try {
    result = await api.purgeMirrorData(purgeScope.value);
    await loadStatus(false, false);
    purgeDialogVisible.value = false;
    purgeConfirmText.value = '';
  } catch (error) {
    ElMessage.error((error as Error).message);
  } finally {
    purging.value = null;
  }
  if (result != null) {
    await ElMessageBox.alert(buildPurgeSummaryHtml(result), '删除完成', {
      type: 'success',
      confirmButtonText: '知道了',
      dangerouslyUseHTMLString: true,
    });
  }
}

function buildPurgeSummaryHtml(result: MirrorPurgeResult) {
  const scopeText =
    result.scope === 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST'
      ? '删除白名单外镜像数据'
      : '删除全部镜像数据';
  const syncTimeResetText = result.syncTimestampsReset ? '已重置' : '未重置';
  return [
    `<strong>${scopeText}已完成</strong>`,
    `删除镜像表：${result.droppedMirrorTables} 张`,
    `清理数据表：${result.truncatedTables} 张`,
    `同步时间：${syncTimeResetText}`,
  ].join('<br />');
}

function formatLogTime(log: GitlabSyncLog) {
  return formatDateTime(log.finishedAt || log.startedAt);
}

function syncStatusText(statusValue: GitlabSyncStatus | 'IDLE') {
  return SYNC_STATUS_LABELS[statusValue] ?? statusValue;
}

function syncStatusTagType(statusValue: GitlabSyncStatus | 'IDLE') {
  return SYNC_STATUS_TAG_TYPES[statusValue] ?? 'info';
}

function syncTypeText(syncType: GitlabSyncType) {
  return SYNC_TYPE_LABELS[syncType] ?? syncType;
}

function syncTypeTagType(syncType: GitlabSyncType) {
  return SYNC_TYPE_TAG_TYPES[syncType] ?? 'info';
}

function translateSyncMessage(message?: string | null, syncType?: GitlabSyncType | null) {
  const normalized = message?.trim() ?? '';
  if (!normalized) {
    return '';
  }

  const skippedTablesMatch = normalized.match(
    /^Sync completed successfully, skipped (\d+) tables without time columns during compensation window scan$/i,
  );
  if (skippedTablesMatch) {
    return `同步已完成，补偿窗口扫描时跳过了 ${skippedTablesMatch[1]} 张缺少时间列的表`;
  }

  if (/^Sync completed successfully$/i.test(normalized)) {
    return '同步已完成';
  }
  if (/^Sync cancelled by user$/i.test(normalized)) {
    return '同步已被用户中止';
  }
  if (/^Task heartbeat timed out$/i.test(normalized)) {
    return '任务心跳超时';
  }
  if (/^Cancellation requested by user$/i.test(normalized)) {
    return '已收到用户中止请求';
  }
  if (/^已收到用户中止请求$/i.test(normalized)) {
    return '已收到用户中止请求';
  }
  if (/^同步已完成$/i.test(normalized)) {
    return '同步已完成';
  }

  if (syncType === 'FULL' && /^Manual full sync$/i.test(normalized)) {
    return '手工触发的全量同步';
  }
  if (syncType === 'INCREMENTAL' && /^Manual recovery incremental sync(?: requested)?$/i.test(normalized)) {
    return '手工恢复增量同步';
  }
  if (syncType === 'COMPENSATION' && /^Scheduled compensation sync$/i.test(normalized)) {
    return '定时补偿同步';
  }
  if (syncType === 'WEBHOOK') {
    const webhookMatch = normalized.match(/^Triggered by webhook:\s*(.+)$/i);
    if (webhookMatch) {
      return `Webhook 精确更新：${webhookMatch[1]}`;
    }
  }

  return normalized;
}

function handlePurgeDialogBeforeClose(done: () => void) {
  if (isPurging.value) {
    return;
  }
  closePurgeDialog();
  done();
}

async function initializePage() {
  try {
    await loadStatus(false, false);
  } finally {
    initialized.value = true;
  }
  void loadWebhookRegistration(false);
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
        <el-form-item label="数据源名称">
          <el-input v-model="form.name" />
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

        <el-divider>Webhook 增量同步</el-divider>

        <el-form-item label="Webhook URL">
          <el-input :model-value="status?.webhookUrl || ''" readonly />
        </el-form-item>
        <el-form-item label="Webhook Secret">
          <el-input v-model="form.webhookSecret" />
        </el-form-item>
        <el-form-item label="GitLab Project ID">
          <el-input-number v-model="form.webhookProjectId" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="Webhook 状态">
          <div class="webhook-status-line">
            <el-tag
              :type="webhookRegistrationLoading ? 'info' : webhookRegistration?.registered ? 'success' : webhookRegistration?.configured ? 'warning' : 'info'"
              round
            >
              {{
                webhookRegistrationLoading
                  ? '检测中'
                  : webhookRegistration?.registered
                    ? '已注册'
                    : webhookRegistration?.configured
                      ? '未注册'
                      : '未配置'
              }}
            </el-tag>
            <span class="webhook-status-text">
              {{
                webhookRegistrationLoading
                  ? '正在异步检测 GitLab Webhook 状态，不影响页面其他信息加载。'
                  : webhookRegistration?.message || '尚未检测 GitLab Webhook 状态'
              }}
            </span>
          </div>
        </el-form-item>

        <el-space wrap>
          <el-button type="primary" :loading="saving" @click="saveConfig()">保存配置</el-button>
          <el-button :icon="Tools" @click="testConnection">测试连接</el-button>
          <el-button :loading="registeringWebhook" @click="registerWebhook">注册 Webhook</el-button>
          <el-button type="success" :loading="syncing" :disabled="!syncEnabled" @click="startFullSync">首次全量同步</el-button>
          <el-button :loading="syncing" :disabled="!syncEnabled" @click="startIncrementalSync">立即增量同步</el-button>
          <el-button type="danger" plain :loading="cancelling" :disabled="!canCancel" @click="cancelSyncTask">
            中止导入
          </el-button>
          <el-button type="danger" plain @click="openPurgeDialog">删除镜像数据</el-button>
        </el-space>
      </el-form>
    </el-card>

    <div class="settings-side-panel">
      <el-card shadow="never" class="panel-card progress-panel">
        <template #header>
          <div class="panel-header">
            <div>
              <div class="panel-title">同步状态</div>
            </div>
            <el-tag :type="displayStatus.type">{{ displayStatus.text }}</el-tag>
          </div>
        </template>

        <div class="status-message">{{ currentMessageText }}</div>

        <div class="progress-shell">
          <div class="progress-head">
            <div>
              <div class="progress-title">同步进度</div>
              <div class="progress-subtitle">{{ phaseText }}</div>
            </div>
            <div class="progress-percentage">{{ progressPercent }}%</div>
          </div>
          <el-progress
            :percentage="progressPercent"
            :stroke-width="18"
            :status="currentTask?.status === 'RUNNING' || currentTask?.status === 'CANCELLING' ? undefined : 'success'"
          />
          <div class="progress-tip">{{ progressHint }}</div>
          <div class="progress-meta-grid">
            <div class="meta-item">
              <span class="meta-label">当前表</span>
              <span class="meta-value mono">{{ progress?.currentTable || '-' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">表进度</span>
              <span class="meta-value">{{ progress?.completedTables || 0 }}/{{ progress?.totalTables || 0 }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">已同步记录</span>
              <span class="meta-value">{{ progress?.syncedRecords || 0 }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">开始时间</span>
              <span class="meta-value">{{ formatDateTime(progress?.startedAt || status?.currentStartedAt) }}</span>
            </div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="panel-card">
        <template #header>
          <div class="panel-header">
            <div>
              <div class="panel-title">最近同步日志</div>
            </div>
            <el-button link :icon="Refresh" :loading="refreshing" @click="refreshStatus">刷新</el-button>
          </div>
        </template>

        <div class="sync-log-table-shell">
        <el-table :data="recentLogs" row-key="id" max-height="320" size="small" border class="sync-log-table">
          <el-table-column label="类型" width="126">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="syncTypeTagType(row.syncType)">
                {{ syncTypeText(row.syncType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="logStatusType(row.status)">{{ logStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ formatLogTime(row) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ formatDuration(row) }}</template>
          </el-table-column>
          <el-table-column prop="tableCount" label="影响表数" width="96" />
          <el-table-column prop="recordCount" label="记录数" width="88" />
          <el-table-column label="说明" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ syncLogMessage(row) }}</template>
          </el-table-column>
        </el-table>
        </div>
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
