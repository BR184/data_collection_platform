<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Refresh, Tools } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { api } from '../api';
import type { GitlabSyncConfig } from '../types/api';
import SmartSelect from '../components/base/SmartSelect.vue';
import PageStateShell from '../components/base/PageStateShell.vue';
import {
  buildPurgeSummaryHtml,
  formatDuration,
  formatLogTime,
  logStatusText,
  logStatusType,
  syncLogMessage,
  syncTypeTagType,
  syncTypeText,
} from './mirror-settings-helpers';
import MirrorSyncStatusCard from './MirrorSyncStatusCard.vue';
import { useMirrorPurgeDialog } from './useMirrorPurgeDialog';
import { useMirrorStatusController } from './useMirrorStatusController';
import { useMirrorStatusPresentation } from './useMirrorStatusPresentation';
import { useMirrorSyncActionsController } from './useMirrorSyncActionsController';
import { useMirrorWebhookRegistrationController } from './useMirrorWebhookRegistrationController';
import { useMirrorWhitelistOptionsController } from './useMirrorWhitelistOptionsController';

const initialized = ref(false);

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
  loadStatusData: () => api.getStatus(),
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
  loadWhitelistOptions: () => api.getWhitelistOptions(),
  notifyError: (message) => ElMessage.error(message),
});

const {
  saving,
  syncing,
  cancelling,
  saveConfig,
  testConnection,
  startFullSync,
  startIncrementalSync,
  cancelSyncTask,
} = useMirrorSyncActionsController({
  form,
  saveConfigData: (config) => api.saveConfig(config),
  testConnectionData: () => api.testConnection(),
  startFullSyncData: () => api.startFullSync(),
  startIncrementalSyncData: () => api.startIncrementalSync(),
  cancelSyncData: () => api.cancelSync(),
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
  getRegistrationStatus: () => api.getWebhookRegistrationStatus(),
  saveConfig: () => saveConfig(false),
  registerWebhook: () => api.registerWebhook(),
  loadStatus: (showError, blocking) => loadStatus(showError, blocking),
  notifySuccess: (message) => ElMessage.success(message),
  notifyError: (message) => ElMessage.error(message),
});

const isDockerMode = computed(() => form.value.sourceMode === 'DOCKER');
const syncEnabled = computed(() => form.value.autoSyncEnabled);
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
  purgeMirrorData: (scope) => api.purgeMirrorData(scope),
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
  (nextStatus) => {
    syncRunningRefresh(nextStatus);
  },
);

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
