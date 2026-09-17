<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { databaseBackupApi } from '../api-client/database-backup-api';
import { isApiBizError } from '../api-client/request';
import { authState } from '../composables/auth-state';
import { hasPermission } from '../feature-manifest';
import { formatBeijingDateTime } from '../utils/beijing-time';
import { getErrorMessage } from '../utils/user-message';
import type {
  BackupConnectionCheckResult,
  BackupRunResponse,
  BackupSettingsResponse,
  BackupStorageMode,
} from '../types/api';

/** 表单草稿：密码字段永远只在内存中，保存时留空 = 不修改已存密码。 */
interface SettingsDraft {
  enabled: boolean;
  scheduleTime: string;
  retentionCopies: number;
  storageMode: BackupStorageMode;
  localSubdirectory: string;
  remoteHost: string;
  remotePort: number;
  remoteUsername: string;
  remotePassword: string;
  remoteDirectory: string;
  remoteHostKeyFingerprint: string;
}

const loading = ref(true);
const refreshing = ref(false);
const saving = ref(false);
const testing = ref(false);
const triggering = ref(false);

const canManage = computed(() => hasPermission(authState.currentUser, 'system.backup.manage'));

const settingsMeta = ref<BackupSettingsResponse | null>(null);
const draft = ref<SettingsDraft>(emptyDraft());
const savedSignature = ref('');

const status = ref<{ running: boolean; currentRun: BackupRunResponse | null; lastCompleted: BackupRunResponse | null; enabled: boolean; scheduleTime: string; nextRunAt: string | null } | null>(null);
const nowTick = ref(Date.now());

const historyLoading = ref(false);
const historyRecords = ref<BackupRunResponse[]>([]);
const historyTotal = ref(0);
const historyPage = ref(1);
const historySize = ref(10);

const testChecks = ref<BackupConnectionCheckResult[] | null>(null);
const testOk = ref(false);
const testActualFingerprint = ref<string | null>(null);

let statusTimer: ReturnType<typeof setInterval> | undefined;
let tickTimer: ReturnType<typeof setInterval> | undefined;
// 组件是否仍挂载：用于阻断异步轮询在卸载返回后重建定时器导致的轮询泄漏。
let alive = false;

const isRemote = computed(() => draft.value.storageMode === 'REMOTE');
const running = computed(() => Boolean(status.value?.running));
const currentRun = computed(() => status.value?.currentRun ?? null);

const draftSignature = computed(() => JSON.stringify(signatureSource()));
const dirty = computed(() => draftSignature.value !== savedSignature.value);

const remoteRequiredMissing = computed(() => {
  if (!isRemote.value) {
    return false;
  }
  return !draft.value.remoteHost.trim()
    || !draft.value.remoteUsername.trim()
    || !draft.value.remoteDirectory.trim();
});

const canSave = computed(() => canManage.value && !saving.value && !running.value && !remoteRequiredMissing.value);
const canTestConnection = computed(() => canManage.value && isRemote.value && !remoteRequiredMissing.value && !testing.value);
const canTrigger = computed(() => canManage.value && !running.value && !triggering.value);

/** 密码输入占位与提示：有已存密码时留空即保留。 */
const passwordPlaceholder = computed(() =>
  settingsMeta.value?.hasRemotePassword ? '留空表示不修改已保存的密码' : '输入远程服务器密码',
);

onMounted(async () => {
  alive = true;
  await Promise.all([reloadSettings(), reloadStatus(), reloadHistory()]);
  // 首屏加载期间可能已被卸载（快速切换路由）：仅在仍挂载时启动轮询。
  if (alive) {
    startTimers();
  }
});

onBeforeUnmount(() => {
  alive = false;
  stopTimers();
});

function emptyDraft(): SettingsDraft {
  return {
    enabled: false,
    scheduleTime: '03:00',
    retentionCopies: 14,
    storageMode: 'LOCAL',
    localSubdirectory: '',
    remoteHost: '',
    remotePort: 22,
    remoteUsername: '',
    remotePassword: '',
    remoteDirectory: '',
    remoteHostKeyFingerprint: '',
  };
}

function signatureSource() {
  const value = draft.value;
  return {
    enabled: value.enabled,
    scheduleTime: value.scheduleTime,
    retentionCopies: value.retentionCopies,
    storageMode: value.storageMode,
    localSubdirectory: value.localSubdirectory.trim(),
    remoteHost: value.remoteHost.trim(),
    remotePort: value.remotePort,
    remoteUsername: value.remoteUsername.trim(),
    remoteDirectory: value.remoteDirectory.trim(),
    remoteHostKeyFingerprint: value.remoteHostKeyFingerprint.trim(),
  };
}

function applySettings(loaded: BackupSettingsResponse) {
  settingsMeta.value = loaded;
  // 后端在未保存过配置时字符串字段可能为 null，统一归一化为空串供表单与 dirty 签名使用。
  draft.value = {
    enabled: loaded.enabled,
    scheduleTime: loaded.scheduleTime.slice(0, 5),
    retentionCopies: loaded.retentionCopies,
    storageMode: loaded.storageMode,
    localSubdirectory: loaded.localSubdirectory ?? '',
    remoteHost: loaded.remoteHost ?? '',
    remotePort: loaded.remotePort,
    remoteUsername: loaded.remoteUsername ?? '',
    remotePassword: '',
    remoteDirectory: loaded.remoteDirectory ?? '',
    remoteHostKeyFingerprint: loaded.remoteHostKeyFingerprint ?? '',
  };
  savedSignature.value = JSON.stringify(signatureSource());
}

async function reloadSettings() {
  try {
    applySettings(await databaseBackupApi.getSettings());
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载备份配置失败'));
  } finally {
    loading.value = false;
  }
}

async function reloadStatus() {
  refreshing.value = true;
  try {
    status.value = await databaseBackupApi.getStatus();
    nowTick.value = Date.now();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载备份状态失败'));
  } finally {
    refreshing.value = false;
  }
}

async function reloadHistory() {
  historyLoading.value = true;
  try {
    const result = await databaseBackupApi.listRuns(historyPage.value, historySize.value);
    historyRecords.value = result.records;
    historyTotal.value = result.total;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '加载备份历史失败'));
  } finally {
    historyLoading.value = false;
  }
}

function startTimers() {
  statusTimer = setInterval(() => void refreshStatusQuietly(), running.value ? 2000 : 15000);
  tickTimer = setInterval(() => {
    nowTick.value = Date.now();
  }, 1000);
}

function stopTimers() {
  if (statusTimer) {
    clearInterval(statusTimer);
    statusTimer = undefined;
  }
  if (tickTimer) {
    clearInterval(tickTimer);
    tickTimer = undefined;
  }
}

async function refreshStatusQuietly() {
  try {
    const previous = status.value;
    const loaded = await databaseBackupApi.getStatus();
    // 轮询响应返回时组件可能已卸载：丢弃结果并停止续期，避免卸载后重建 interval。
    if (!alive) {
      return;
    }
    status.value = loaded;
    nowTick.value = Date.now();
    statusTimer = restartInterval(statusTimer, running.value ? 2000 : 15000);
    // 运行结束瞬间补拉历史，避免历史表停留在过期的 RUNNING 行。
    if (previous?.running && !running.value) {
      void reloadHistory();
    }
  } catch {
    // 轮询失败静默：状态由下次轮询或手动刷新纠正，避免刷屏报错。
  }
}

function restartInterval(previous: ReturnType<typeof setInterval> | undefined, intervalMs: number) {
  if (previous) {
    clearInterval(previous);
  }
  return setInterval(() => void refreshStatusQuietly(), intervalMs);
}

async function saveSettings() {
  if (!draft.value.enabled && running.value) {
    ElMessage.warning('已有备份正在运行，无法修改配置');
    return;
  }
  saving.value = true;
  try {
    const saved = await databaseBackupApi.saveSettings({
      enabled: draft.value.enabled,
      scheduleTime: draft.value.scheduleTime,
      retentionCopies: draft.value.retentionCopies,
      storageMode: draft.value.storageMode,
      localSubdirectory: draft.value.localSubdirectory.trim() || null,
      remoteHost: draft.value.remoteHost.trim(),
      remotePort: draft.value.remotePort,
      remoteUsername: draft.value.remoteUsername.trim(),
      remotePassword: draft.value.remotePassword,
      remoteDirectory: draft.value.remoteDirectory.trim(),
      remoteHostKeyFingerprint: draft.value.remoteHostKeyFingerprint.trim(),
      version: settingsMeta.value?.version ?? 0,
    });
    applySettings(saved);
    testChecks.value = null;
    ElMessage.success('备份配置已保存');
    await reloadStatus();
  } catch (error) {
    if (isApiBizError(error) && error.code === 'A0409') {
      try {
        await ElMessageBox.confirm(error.message, '配置已被他人修改', {
          type: 'warning',
          confirmButtonText: '重新加载',
          cancelButtonText: '留在当前编辑',
        });
        await reloadSettings();
      } catch {
        // 用户选择留在当前编辑。
      }
    } else {
      ElMessage.error(getErrorMessage(error, '保存备份配置失败'));
    }
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  testChecks.value = null;
  try {
    const result = await databaseBackupApi.testConnection({
      remoteHost: draft.value.remoteHost.trim(),
      remotePort: draft.value.remotePort,
      remoteUsername: draft.value.remoteUsername.trim(),
      remotePassword: draft.value.remotePassword,
      remoteDirectory: draft.value.remoteDirectory.trim(),
      remoteHostKeyFingerprint: draft.value.remoteHostKeyFingerprint.trim(),
    });
    testOk.value = result.ok;
    testChecks.value = result.checks;
    testActualFingerprint.value = result.actualFingerprint ?? null;
    if (!result.ok) {
      ElMessage.warning('连接测试未全部通过，请查看检查结果');
    }
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '测试连接失败'));
  } finally {
    testing.value = false;
  }
}

function adoptFingerprint() {
  if (testActualFingerprint.value) {
    draft.value.remoteHostKeyFingerprint = testActualFingerprint.value;
    testActualFingerprint.value = null;
    ElMessage.success('已采纳实际指纹，请保存配置后生效');
  }
}

async function triggerRun() {
  triggering.value = true;
  try {
    const result = await databaseBackupApi.triggerRun();
    if (result.accepted) {
      ElMessage.success(result.message);
    } else {
      ElMessage.info(result.message);
    }
    await Promise.all([reloadStatus(), reloadHistory()]);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '触发备份失败'));
  } finally {
    triggering.value = false;
  }
}

function onHistoryPageChange(page: number) {
  historyPage.value = page;
  void reloadHistory();
}

function stageLabel(value: string | null) {
  return {
    PRECHECK: '磁盘预检',
    DUMP: '数据库导出',
    VERIFY: '可恢复性校验',
    STORE: '产物落位',
    RETENTION: '按保留份数清理',
  }[value ?? ''] ?? value ?? '-';
}

function runStatusLabel(value: string) {
  return { RUNNING: '运行中', SUCCESS: '成功', FAILED: '失败' }[value] ?? value;
}

function runStatusType(value: string) {
  if (value === 'SUCCESS') return 'success';
  if (value === 'FAILED') return 'danger';
  return 'warning';
}

function triggerLabel(value: string) {
  return value === 'SCHEDULE' ? '定时' : '手动';
}

function formatBytes(value: number | null) {
  if (value == null) {
    return '-';
  }
  if (value < 1024) {
    return `${value} B`;
  }
  const units = ['KiB', 'MiB', 'GiB'];
  let scaled = value / 1024;
  let unitIndex = 0;
  while (scaled >= 1024 && unitIndex < units.length - 1) {
    scaled /= 1024;
    unitIndex += 1;
  }
  return `${scaled.toFixed(scaled >= 100 ? 0 : 1)} ${units[unitIndex]}`;
}

function formatDuration(value: number | null) {
  if (value == null) {
    return '-';
  }
  const seconds = Math.floor(value / 1000);
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}

function elapsedSince(startedAt: string) {
  const started = new Date(startedAt).getTime();
  if (Number.isNaN(started)) {
    return '-';
  }
  return formatDuration(Math.max(0, nowTick.value - started));
}
</script>

<template>
  <section class="backup-panel" aria-labelledby="backup-title">
    <header class="backup-header">
      <div>
        <h2 id="backup-title">数据库备份管理</h2>
        <p>每日定时或手动触发全库备份；产物经可恢复性校验后落位，失败不自动重试。</p>
      </div>
      <div class="backup-header__actions">
        <el-tag :type="running ? 'warning' : settingsMeta?.enabled ? 'success' : 'info'">
          {{ running ? '备份进行中' : settingsMeta?.enabled ? `定时已启用（每日 ${status?.scheduleTime ?? draft.scheduleTime}）` : '定时未启用' }}
        </el-tag>
        <el-tooltip content="刷新状态" placement="top">
          <el-button circle :icon="Refresh" :loading="refreshing" aria-label="刷新备份状态" @click="reloadStatus" />
        </el-tooltip>
      </div>
    </header>

    <el-skeleton v-if="loading" animated :rows="7" />
    <template v-else>
      <div class="backup-grid">
        <el-form class="backup-form" label-position="top">
          <div class="backup-section-heading">
            <strong>备份配置</strong>
            <span>版本 {{ settingsMeta?.version ?? 0 }}；实例标识 {{ settingsMeta?.instanceLabel ?? '-' }}</span>
          </div>
          <div class="backup-form-grid">
            <el-form-item label="启用每日定时备份">
              <el-switch v-model="draft.enabled" :disabled="!canManage" />
            </el-form-item>
            <el-form-item label="每日备份时刻">
              <el-time-picker
                v-model="draft.scheduleTime"
                value-format="HH:mm"
                format="HH:mm"
                :clearable="false"
                :disabled="!canManage || !draft.enabled"
              />
            </el-form-item>
            <el-form-item label="保留份数">
              <el-input-number
                v-model="draft.retentionCopies"
                :min="1"
                :max="365"
                controls-position="right"
                :disabled="!canManage"
              />
            </el-form-item>
            <el-form-item label="备份位置">
              <el-radio-group v-model="draft.storageMode" :disabled="!canManage">
                <el-radio value="LOCAL">本机部署服务器</el-radio>
                <el-radio value="REMOTE">远程备份服务器</el-radio>
              </el-radio-group>
            </el-form-item>
            <template v-if="!isRemote">
              <el-form-item label="本地子目录（可选）" class="backup-form-item--wide">
                <el-input
                  v-model="draft.localSubdirectory"
                  :disabled="!canManage"
                  placeholder="默认按实例标识存放；支持多级相对目录如 backups/20001"
                />
              </el-form-item>
              <div class="backup-hint backup-form-item--wide">
                产物落在部署服务器目录 {{ settingsMeta?.localRootEffective ?? '-' }} 下，容器挂载保证宿主机可见、可直接恢复。
              </div>
            </template>
            <template v-else>
              <el-form-item label="远程服务器地址">
                <el-input v-model="draft.remoteHost" :disabled="!canManage" placeholder="例如 192.168.1.10" />
              </el-form-item>
              <el-form-item label="SSH 端口">
                <el-input-number v-model="draft.remotePort" :min="1" :max="65535" controls-position="right" :disabled="!canManage" />
              </el-form-item>
              <el-form-item label="SSH 用户名">
                <el-input v-model="draft.remoteUsername" :disabled="!canManage" autocomplete="off" />
              </el-form-item>
              <el-form-item label="SSH 密码">
                <el-input
                  v-model="draft.remotePassword"
                  type="password"
                  show-password
                  autocomplete="new-password"
                  :disabled="!canManage"
                  :placeholder="passwordPlaceholder"
                />
              </el-form-item>
              <el-form-item label="远程备份目录" class="backup-form-item--wide">
                <el-input v-model="draft.remoteDirectory" :disabled="!canManage" placeholder="例如 /data/backups（绝对路径）" />
              </el-form-item>
              <el-form-item label="主机密钥指纹" class="backup-form-item--wide">
                <el-input
                  v-model="draft.remoteHostKeyFingerprint"
                  :disabled="!canManage"
                  placeholder="留空则由测试连接采集；格式 SHA256:xxxx"
                />
              </el-form-item>
              <div class="backup-hint backup-form-item--wide">
                上传成功后远端保留成品、本机不重复留存；未配置远程时备份默认保存在本机部署服务器上。
                <template v-if="settingsMeta && !settingsMeta.secretKeyConfigured">
                  <br /><strong>服务端尚未配置备份主密钥（PLATFORM_BACKUP_SECRET_KEY），无法保存远程密码。</strong>
                </template>
              </div>
            </template>
          </div>
          <div class="backup-actions">
            <el-button
              type="primary"
              :loading="saving"
              :disabled="!canSave || !dirty"
              @click="saveSettings"
            >保存配置</el-button>
            <el-tag v-if="dirty" type="warning" effect="plain" size="small">有未保存修改</el-tag>
          </div>
        </el-form>

        <div class="backup-runtime">
          <div class="backup-section-heading">
            <strong>连接与执行</strong>
            <span>测试连接为纯只读检查，绝不会触发备份。</span>
          </div>
          <div v-if="isRemote" class="backup-actions">
            <el-button :loading="testing" :disabled="!canTestConnection" @click="testConnection">测试连接</el-button>
          </div>
          <div v-else class="backup-hint">本机存储模式无需远程连接测试。</div>

          <template v-if="testChecks">
            <div class="backup-test-results">
              <div v-for="check in testChecks" :key="check.name" class="backup-test-result">
                <el-tag :type="check.passed ? 'success' : 'danger'" size="small">{{ check.passed ? '通过' : '未通过' }}</el-tag>
                <span class="backup-test-name">{{ check.name }}</span>
                <span class="backup-test-message">{{ check.message }}</span>
              </div>
            </div>
            <el-alert
              v-if="testActualFingerprint"
              type="warning"
              :closable="false"
              show-icon
              title="远程服务器主机密钥指纹与已保存配置不一致"
            >
              实际指纹：{{ testActualFingerprint }}。若确认是服务器密钥变更（非中间人攻击），可采纳实际指纹并重新保存。
              <div class="backup-actions">
                <el-button size="small" type="warning" plain @click="adoptFingerprint">采纳实际指纹</el-button>
              </div>
            </el-alert>
            <el-alert
              v-else-if="testChecks.length && testOk"
              type="success"
              :closable="false"
              show-icon
              title="三项检查全部通过，可保存配置并执行备份"
            />
          </template>

          <el-divider />
          <div class="backup-actions">
            <el-button
              type="primary"
              :loading="triggering"
              :disabled="!canTrigger"
              @click="triggerRun"
            >{{ running ? '备份进行中' : '立即备份' }}</el-button>
            <span class="backup-hint backup-hint--inline">
              {{ settingsMeta?.enabled ? `下次定时备份：${formatBeijingDateTime(status?.nextRunAt, '-')}` : '定时未启用，可手动触发' }}
            </span>
          </div>
        </div>
      </div>

      <div class="backup-status">
        <div class="backup-section-heading">
          <strong>当前状态</strong>
          <span>运行中每 2 秒自动刷新；空闲每 15 秒刷新。</span>
        </div>
        <template v-if="running && currentRun">
          <div class="backup-running">
            <el-tag type="warning">运行中</el-tag>
            <span>当前阶段：{{ stageLabel(currentRun.stage) }}</span>
            <span>已耗时：{{ elapsedSince(currentRun.startedAt) }}</span>
            <span>触发：{{ triggerLabel(currentRun.triggerType) }}</span>
          </div>
        </template>
        <template v-else>
          <div v-if="status?.lastCompleted" class="backup-running">
            <el-tag :type="runStatusType(status.lastCompleted.status)">{{ runStatusLabel(status.lastCompleted.status) }}</el-tag>
            <span>最近一次：{{ formatBeijingDateTime(status.lastCompleted.startedAt) }}</span>
            <span>时长：{{ formatDuration(status.lastCompleted.durationMs) }}</span>
            <span>大小：{{ formatBytes(status.lastCompleted.fileBytes) }}</span>
            <el-tooltip
              v-if="status.lastCompleted.status === 'FAILED'"
              :content="status.lastCompleted.errorMessage ?? ''"
              placement="top"
            >
              <span class="backup-error-text">失败原因</span>
            </el-tooltip>
          </div>
          <div v-else class="backup-hint">尚未执行过备份。</div>
        </template>
      </div>

      <div class="backup-history">
        <div class="backup-section-heading backup-section-heading--row">
          <div>
            <strong>备份历史</strong>
            <span>仅清理本实例命名模式的旧产物，其他文件永不触碰。</span>
          </div>
          <el-button plain :icon="Refresh" :loading="historyLoading" @click="reloadHistory">刷新</el-button>
        </div>
        <el-table v-loading="historyLoading" :data="historyRecords" empty-text="暂无备份记录">
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="runStatusType(row.status)">{{ runStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发" width="80">
            <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
          </el-table-column>
          <el-table-column label="位置" width="90">
            <template #default="{ row }">{{ row.storageMode === 'REMOTE' ? '远程' : '本机' }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">{{ formatBeijingDateTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="时长" width="110">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ formatBytes(row.fileBytes) }}</template>
          </el-table-column>
          <el-table-column label="文件" min-width="240" show-overflow-tooltip prop="fileName">
            <template #default="{ row }">
              <el-tooltip v-if="row.sha256" :content="`SHA-256：${row.sha256}`" placement="top">
                <span>{{ row.fileName }}</span>
              </el-tooltip>
              <span v-else>{{ row.fileName ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="错误信息" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ row.errorMessage ?? '-' }}</template>
          </el-table-column>
        </el-table>
        <el-pagination
          class="backup-history-pagination"
          layout="total, prev, pager, next, sizes"
          :total="historyTotal"
          :current-page="historyPage"
          :page-size="historySize"
          :page-sizes="[10, 20, 50]"
          @current-change="onHistoryPageChange"
          @size-change="(size: number) => { historySize = size; historyPage = 1; void reloadHistory(); }"
        />
      </div>
    </template>
  </section>
</template>

<style scoped>
.backup-panel {
  min-width: 0;
  margin-top: 16px;
  padding: 18px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.backup-header,
.backup-section-heading--row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.backup-header h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
  line-height: 26px;
}

.backup-header p,
.backup-section-heading span {
  margin: 3px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 20px;
}

.backup-header__actions,
.backup-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.backup-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(320px, 1fr);
  gap: 24px;
  margin-top: 20px;
}

.backup-form,
.backup-runtime {
  min-width: 0;
}

.backup-runtime {
  padding-inline-start: 24px;
  border-inline-start: 1px solid var(--el-border-color-lighter);
}

.backup-section-heading {
  margin-bottom: 14px;
}

.backup-section-heading strong {
  display: block;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 22px;
}

.backup-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: 16px;
}

.backup-form-item--wide {
  grid-column: span 2;
}

.backup-form :deep(.el-input-number),
.backup-form :deep(.el-date-editor) {
  width: 100%;
}

.backup-hint {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.backup-hint--inline {
  margin-top: 0;
}

.backup-test-results {
  margin-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.backup-test-result {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.backup-test-name {
  color: var(--el-text-color-primary);
  font-size: 13px;
}

.backup-test-message {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
}

.backup-status,
.backup-history {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.backup-running {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  color: var(--el-text-color-primary);
  font-size: 13px;
}

.backup-error-text {
  color: var(--el-color-danger);
  cursor: help;
  text-decoration: underline dotted;
}

.backup-history-pagination {
  margin-top: 14px;
  justify-content: flex-end;
}

@media (max-width: 1366px) {
  .backup-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .backup-runtime {
    padding-inline-start: 0;
    padding-top: 20px;
    border-inline-start: 0;
    border-top: 1px solid var(--el-border-color-lighter);
  }

  .backup-form-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
