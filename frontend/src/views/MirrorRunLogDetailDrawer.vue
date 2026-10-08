<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { ElMessage } from '../element-plus-services';
import type {
  SyncRunDiagnosticItem,
  SyncRunDetailBlock,
  SyncRunEventTrailItem,
  SyncRunLog,
  SyncRunLogDetailSection,
} from '../types/api';
import { getErrorMessage } from '../utils/user-message';
import {
  diagnosticKindText,
  formatDateTime,
  logStatusText,
  logStatusType,
  manualDispositionText,
  syncLogTypeText,
  syncTriggerTypeText,
  tableTaskStatusText,
} from './mirror-settings-helpers';

const props = defineProps<{
  modelValue: boolean;
  configId?: number;
  run: SyncRunLog | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});

const DETAIL_PAGE_SIZE = 20;

const diagnosticsBlock = ref<SyncRunDetailBlock<SyncRunDiagnosticItem> | null>(null);
const eventsBlock = ref<SyncRunDetailBlock<SyncRunEventTrailItem> | null>(null);
const diagnosticsPage = ref(1);
const eventsPage = ref(1);
const diagnosticsLoading = ref(false);
const eventsLoading = ref(false);
const diagnosticsError = ref('');

const runId = computed(() => (props.run ? props.run.id : null));
const title = computed(() => (props.run?.runId ? `运行明细 ${props.run.runId}` : '运行明细'));

const diagnosticsItems = computed(() => diagnosticsBlock.value?.items ?? []);
const eventItems = computed(() => eventsBlock.value?.items ?? []);
const diagnosticsTotal = computed(() => diagnosticsBlock.value?.total ?? props.run?.diagnosticCount ?? 0);
const eventsTotal = computed(() => eventsBlock.value?.total ?? props.run?.eventCount ?? 0);
const manualAttentionCount = computed(() => props.run?.manualAttentionCount ?? 0);
const failureCount = computed(() => props.run?.failureCount ?? 0);

/** 运行已结束却仍有未收敛内容时，区分「运行结束」与「任务/数据是否追平」。 */
const runSettledNotice = computed(() => {
  if (!props.run || props.run.status === 'RUNNING' || props.run.status === 'CANCELLING') {
    return '';
  }
  if (manualAttentionCount.value <= 0) {
    return '';
  }
  return `该运行已结束，尚有 ${manualAttentionCount.value} 项人工待处理`;
});

async function loadSection(section: SyncRunLogDetailSection, page: number) {
  if (runId.value == null) {
    return;
  }
  const offset = Math.max(0, (page - 1) * DETAIL_PAGE_SIZE);
  if (section === 'DIAGNOSTICS') {
    diagnosticsLoading.value = true;
    diagnosticsError.value = '';
  } else {
    eventsLoading.value = true;
  }
  try {
    const response = await api.getRunLogDetails(props.configId, runId.value, section, offset, DETAIL_PAGE_SIZE);
    const block = response.details ?? null;
    if (section === 'DIAGNOSTICS') {
      diagnosticsBlock.value = (block as SyncRunDetailBlock<SyncRunDiagnosticItem> | null) ?? null;
    } else {
      eventsBlock.value = (block as SyncRunDetailBlock<SyncRunEventTrailItem> | null) ?? null;
    }
  } catch (error) {
    const message = getErrorMessage(error, '读取运行明细失败');
    if (section === 'DIAGNOSTICS') {
      diagnosticsError.value = message;
      diagnosticsBlock.value = null;
    } else {
      eventsBlock.value = null;
    }
    ElMessage.error(message);
  } finally {
    if (section === 'DIAGNOSTICS') {
      diagnosticsLoading.value = false;
    } else {
      eventsLoading.value = false;
    }
  }
}

async function reloadAll() {
  diagnosticsPage.value = 1;
  eventsPage.value = 1;
  diagnosticsBlock.value = null;
  eventsBlock.value = null;
  await Promise.all([loadSection('DIAGNOSTICS', 1), loadSection('EVENTS', 1)]);
}

watch(
  () => [props.modelValue, runId.value] as const,
  ([open], previous) => {
    const wasOpen = previous?.[0] ?? false;
    if (!open) {
      return;
    }
    if (!wasOpen || previous?.[1] !== runId.value) {
      void reloadAll();
    }
  },
  { immediate: true },
);

function handleDiagnosticsPageChange(page: number) {
  diagnosticsPage.value = page;
  void loadSection('DIAGNOSTICS', page);
}

function handleEventsPageChange(page: number) {
  eventsPage.value = page;
  void loadSection('EVENTS', page);
}

function textOrDash(value: unknown) {
  if (value == null) {
    return '-';
  }
  const text = String(value).trim();
  return text === '' ? '-' : text;
}

function detailsOf(item: SyncRunDiagnosticItem): Record<string, unknown> {
  const raw = item.details;
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    return raw as Record<string, unknown>;
  }
  return {};
}

function pushField(rows: Array<{ label: string; value: string }>, label: string, value: unknown) {
  const text = textOrDash(value);
  if (text !== '-') {
    rows.push({ label, value: text });
  }
}

/** 分类专属上下文里已知键的可读标签；键名以服务端实际产出为准。 */
const DETAIL_KEYS_WITH_LABELS: Array<[string, string]> = [
  ['sourceTable', '来源表'],
  ['taskStage', '任务阶段'],
  ['rowsScanned', '扫描行数'],
  ['rowsApplied', '应用行数'],
  ['childTable', '子表'],
  ['relationKey', '关系键'],
  ['scopeSignature', '范围签名'],
  ['lookupScope', '查找范围'],
  ['affectedRows', '影响行数'],
  ['rootCount', '实际根数'],
  ['scope', '范围'],
];

/** 按分类拼装可定位字段；未取到的值不展示，不用相关状态推断补齐。 */
function itemFields(item: SyncRunDiagnosticItem) {
  const rows: Array<{ label: string; value: string }> = [];
  const details = detailsOf(item);
  pushField(rows, '状态', item.status ? tableTaskStatusText(item.status) : null);
  pushField(rows, '人工处置', item.manualDisposition ? manualDispositionText(item.manualDisposition) : null);
  pushField(rows, '任务编号', item.taskId);
  if (item.kind === 'FACT_BUILD' || item.kind === 'PROJECTION') {
    pushField(rows, '来源实例', item.sourceInstance);
    pushField(rows, '事实类型', item.factType);
    pushField(rows, '构建方式', item.fullBuild == null ? null : item.fullBuild ? '全量' : '定向');
  }
  if (item.kind === 'PROJECTION') {
    pushField(rows, '范围类型', item.scopeType);
    pushField(rows, '范围键', item.scopeKey);
    pushField(rows, '目标版本', item.targetGeneration);
  }
  pushField(rows, '原运行', item.originalRunId);
  pushField(rows, '当前运行', item.currentRunId);
  pushField(rows, '重试', item.retryCount == null && item.maxRetryCount == null
    ? null
    : `${item.retryCount ?? 0} / ${item.maxRetryCount ?? '-'}`);
  pushField(rows, '开始', item.startedAt ? formatDateTime(item.startedAt) : null);
  pushField(rows, '结束', item.finishedAt ? formatDateTime(item.finishedAt) : null);
  pushField(rows, '观测到错误', item.errorObservedAt ? formatDateTime(item.errorObservedAt) : null);
  pushField(rows, '耗时', itemDurationText(item));
  pushField(rows, '心跳', item.heartbeatAt ? formatDateTime(item.heartbeatAt) : null);
  pushField(rows, '租约到期', item.leaseUntil ? formatDateTime(item.leaseUntil) : null);
  pushField(rows, '记录更新时间', item.recordUpdatedAt ? formatDateTime(item.recordUpdatedAt) : null);
  for (const [key, label] of DETAIL_KEYS_WITH_LABELS) {
    pushField(rows, label, details[key]);
  }
  return rows;
}

/** 分类专属上下文里未在上方具名展示的键，原样列出，避免线索被吞掉。 */
function extraDetails(item: SyncRunDiagnosticItem) {
  const known = new Set(DETAIL_KEYS_WITH_LABELS.map(([key]) => key));
  return Object.entries(detailsOf(item))
    .filter(([key, value]) => !known.has(key) && value != null && String(value).trim() !== '')
    .map(([key, value]) => ({ label: key, value: typeof value === 'object' ? JSON.stringify(value) : String(value) }));
}

function formatMillis(value: number) {
  const seconds = Math.max(0, Math.round(value / 1000));
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes} 分 ${seconds % 60} 秒`;
}

/** 已结束项用服务端算好的 elapsedMs；进行中项按响应时刻减开始时间，缺开始时间时保持空值。 */
function itemDurationText(item: SyncRunDiagnosticItem) {
  if (item.elapsedMs != null) {
    return formatMillis(item.elapsedMs);
  }
  if (!item.startedAt) {
    return '-';
  }
  const start = new Date(item.startedAt).getTime();
  if (Number.isNaN(start)) {
    return '-';
  }
  return formatMillis(Date.now() - start);
}

async function copyText(text: string | null | undefined, label: string) {
  const value = (text ?? '').trim();
  if (!value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success(`${label}已复制`);
  } catch {
    ElMessage.warning('当前浏览器不允许自动复制，请手动选择文本');
  }
}
</script>

<template>
  <el-drawer v-model="visible" :title="title" size="min(920px, 94vw)" class="mirror-run-log-detail-drawer">
    <div v-if="run" class="run-conclusion">
      <div class="run-conclusion-item">
        <span>运行编号</span>
        <strong>{{ textOrDash(run.runId || run.id) }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>运行结果</span>
        <strong>
          <el-tag size="small" :type="logStatusType(run.status)">{{ logStatusText(run.status) }}</el-tag>
        </strong>
      </div>
      <div class="run-conclusion-item">
        <span>同步内容</span>
        <strong>{{ syncLogTypeText(run) }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>触发来源</span>
        <strong>{{ syncTriggerTypeText(run.triggerType) }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>开始</span>
        <strong>{{ formatDateTime(run.startedAt) }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>结束</span>
        <strong>{{ run.finishedAt ? formatDateTime(run.finishedAt) : '进行中' }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>写入记录</span>
        <strong>{{ run.recordCount ?? 0 }}</strong>
      </div>
      <div class="run-conclusion-item">
        <span>失败 / 待处理</span>
        <strong>{{ failureCount }} / {{ manualAttentionCount }}</strong>
      </div>
    </div>

    <el-alert
      v-if="runSettledNotice"
      class="run-settled-notice"
      type="warning"
      :closable="false"
      show-icon
      :title="runSettledNotice"
    />

    <div v-if="run?.errorSummary" class="raw-error-block">
      <div class="raw-error-header">
        <span>运行级错误原文</span>
        <el-button link size="small" @click="copyText(run?.errorSummary, '错误原文')">复制全文</el-button>
      </div>
      <pre class="raw-error-text">{{ run.errorSummary }}</pre>
    </div>

    <section class="detail-section">
      <div class="detail-section-header">
        <h4>故障与待处理项</h4>
        <span class="detail-section-count">共 {{ diagnosticsTotal }} 项</span>
      </div>
      <div v-loading="diagnosticsLoading">
        <el-alert
          v-if="diagnosticsError"
          type="error"
          :closable="false"
          show-icon
          :title="diagnosticsError"
        />
        <div v-else-if="!diagnosticsLoading && diagnosticsTotal === 0" class="detail-empty">
          该运行没有保留可展开的故障或待处理项。
        </div>
        <div v-for="(item, index) in diagnosticsItems" :key="`${item.kind}-${item.taskId}-${index}`" class="diagnostic-item">
          <div class="diagnostic-item-header">
            <el-tag size="small" effect="plain" type="danger">{{ diagnosticKindText(item.kind) }}</el-tag>
            <span v-if="item.manualDisposition === 'REQUIRES_DECISION'" class="diagnostic-badge">需人工处置</span>
          </div>
          <div class="diagnostic-fields">
            <div v-for="field in itemFields(item)" :key="field.label" class="diagnostic-field">
              <span>{{ field.label }}</span>
              <strong>{{ field.value }}</strong>
            </div>
          </div>
          <div v-if="extraDetails(item).length" class="diagnostic-fields">
            <div v-for="field in extraDetails(item)" :key="`extra-${field.label}`" class="diagnostic-field">
              <span>{{ field.label }}</span>
              <strong>{{ field.value }}</strong>
            </div>
          </div>
          <div v-if="item.rawError" class="raw-error-block">
            <div class="raw-error-header">
              <span>任务错误原文</span>
              <el-button link size="small" @click="copyText(item.rawError, '任务错误原文')">复制全文</el-button>
            </div>
            <pre class="raw-error-text">{{ item.rawError }}</pre>
          </div>
          <div v-if="item.dispositionReason" class="diagnostic-reason">
            处置原因：{{ item.dispositionReason }}
          </div>
        </div>
        <el-pagination
          v-if="diagnosticsTotal > DETAIL_PAGE_SIZE"
          class="detail-pagination"
          layout="prev, pager, next, total"
          :total="diagnosticsTotal"
          :page-size="DETAIL_PAGE_SIZE"
          :current-page="diagnosticsPage"
          @current-change="handleDiagnosticsPageChange"
        />
      </div>
    </section>

    <section class="detail-section">
      <div class="detail-section-header">
        <h4>相关事件</h4>
        <span class="detail-section-count">共 {{ eventsTotal }} 条</span>
      </div>
      <div v-loading="eventsLoading">
        <div v-if="!eventsLoading && eventsTotal === 0" class="detail-empty">该运行没有保留相关事件。</div>
        <ul v-else class="event-list">
          <li v-for="(event, index) in eventItems" :key="`${event.eventId}-${index}`" class="event-item">
            <span class="event-time">{{ formatDateTime(event.createdAt) }}</span>
            <span class="event-type">{{ textOrDash(event.eventType) }}</span>
            <span class="event-message">{{ textOrDash(event.message) }}</span>
          </li>
        </ul>
        <el-pagination
          v-if="eventsTotal > DETAIL_PAGE_SIZE"
          class="detail-pagination"
          layout="prev, pager, next, total"
          :total="eventsTotal"
          :page-size="DETAIL_PAGE_SIZE"
          :current-page="eventsPage"
          @current-change="handleEventsPageChange"
        />
      </div>
    </section>

    <div v-if="run?.latestProgressMessage" class="progress-footnote">
      最新进度：{{ run.latestProgressMessage }}
      <span v-if="run.latestProgressAt">（{{ formatDateTime(run.latestProgressAt) }}）</span>
    </div>
  </el-drawer>
</template>

<style scoped>
.run-conclusion {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.run-conclusion-item {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.run-conclusion-item span {
  font-size: 12px;
  color: #64748b;
}

.run-conclusion-item strong {
  font-size: 14px;
  color: #111827;
  overflow-wrap: anywhere;
}

.run-settled-notice {
  margin-bottom: 14px;
}

.detail-section {
  margin-top: 18px;
}

.detail-section-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.detail-section-header h4 {
  margin: 0;
  font-size: 14px;
  color: #111827;
}

.detail-section-count {
  font-size: 12px;
  color: #64748b;
}

.detail-empty {
  padding: 16px;
  color: #64748b;
  font-size: 13px;
  background: #f8fafc;
  border-radius: 8px;
}

.diagnostic-item {
  padding: 12px;
  margin-bottom: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.diagnostic-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.diagnostic-badge {
  font-size: 12px;
  color: #b45309;
}

.diagnostic-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 8px 16px;
}

.diagnostic-field {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.diagnostic-field span {
  font-size: 12px;
  color: #64748b;
}

.diagnostic-field strong {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  overflow-wrap: anywhere;
}

.diagnostic-reason {
  margin-top: 8px;
  font-size: 12px;
  color: #b45309;
}

.raw-error-block {
  margin: 10px 0;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
}

.raw-error-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 12px;
  color: #991b1b;
  border-bottom: 1px solid #fecaca;
}

.raw-error-text {
  margin: 0;
  padding: 10px;
  max-height: 220px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  color: #7f1d1d;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.event-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.event-item {
  display: grid;
  grid-template-columns: 150px 150px minmax(0, 1fr);
  gap: 10px;
  padding: 8px 10px;
  font-size: 12px;
  border-bottom: 1px solid #f1f5f9;
}

.event-time {
  color: #64748b;
}

.event-type {
  color: #334155;
}

.event-message {
  color: #1f2937;
  overflow-wrap: anywhere;
}

.detail-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}

.progress-footnote {
  margin-top: 16px;
  font-size: 12px;
  color: #64748b;
}
</style>
