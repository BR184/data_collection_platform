<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useFloatingHorizontalScrollbar } from '../composables/useFloatingHorizontalScrollbar';
import { ElMessage } from '../element-plus-services';
import type { SyncRunLog } from '../types/api';
import {
  deleteReconciliationStatusTagType,
  deleteReconciliationStatusText,
  diagnosticKindText,
  formatDuration,
  formatLogTime,
  freshnessStatusTagType,
  freshnessStatusText,
  logStatusText,
  logStatusType,
  syncLogTypeText,
  syncLogMessage,
  syncTriggerTypeText,
  syncTypeTagType,
} from './mirror-settings-helpers';

const props = defineProps<{
  logs: SyncRunLog[];
  refreshing: boolean;
}>();

defineEmits<{
  refresh: [];
  /** 打开该运行的分页明细：全部定位项与相关事件。 */
  openDetails: [run: SyncRunLog];
}>();

/** 展开区摘要最多展示的定位项数；完整清单走同运行明细。 */
const DIAGNOSTIC_PREVIEW_LIMIT = 5;
/** 展开区摘要最多展示的相关事件数。 */
const EVENT_PREVIEW_LIMIT = 5;

/** 桌面端表格最大高度：约可完整显示 12 条小尺寸日志行。 */
const DESKTOP_TABLE_MAX_HEIGHT_PX = 420;
/** 矮屏下仍保留的最小可滚动高度。 */
const MIN_TABLE_MAX_HEIGHT_PX = 220;
/** 视口高度取值比例，使矮屏下随可用高度缩小但整行仍可滚动。 */
const TABLE_MAX_HEIGHT_VIEWPORT_RATIO = 0.55;

const typeFilter = ref('');
const statusFilter = ref('');
const tableRef = ref<{ doLayout?: () => void }>();
const tableShellRef = ref<HTMLElement>();
const tableMaxHeight = ref(DESKTOP_TABLE_MAX_HEIGHT_PX);

function updateTableMaxHeight() {
  const viewportGuess = Math.round((window.innerHeight || 0) * TABLE_MAX_HEIGHT_VIEWPORT_RATIO);
  tableMaxHeight.value = Math.max(
    MIN_TABLE_MAX_HEIGHT_PX,
    Math.min(DESKTOP_TABLE_MAX_HEIGHT_PX, viewportGuess),
  );
}

onMounted(() => {
  updateTableMaxHeight();
  window.addEventListener('resize', updateTableMaxHeight, { passive: true });
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateTableMaxHeight);
});

const typeOptions = computed(() => {
  const optionMap = new Map<string, string>();
  for (const log of props.logs) {
    optionMap.set(typeFilterKey(log), syncLogTypeText(log));
  }
  return Array.from(optionMap, ([value, label]) => ({ value, label }));
});

const statusOptions = computed(() => {
  const optionMap = new Map<string, string>();
  for (const log of props.logs) {
    optionMap.set(statusFilterKey(log), logDisplayStatusText(log));
  }
  return Array.from(optionMap, ([value, label]) => ({ value, label }));
});

const filteredLogs = computed(() =>
  props.logs.filter((log) => {
    const typeMatched = !typeFilter.value || typeFilterKey(log) === typeFilter.value;
    const statusMatched = !statusFilter.value || statusFilterKey(log) === statusFilter.value;
    return typeMatched && statusMatched;
  }),
);

const {
  floatingScrollbarRef,
  floatingTrackRef,
  scrollbarAwake,
  hasHorizontalOverflow,
  isFloatingScrollbarVisible,
  floatingScrollbarStyle,
  floatingThumbStyle,
  wakeHorizontalScrollbar,
  handleHorizontalWheel,
  handleFloatingTrackPointerDown,
  handleFloatingThumbPointerDown,
  handleFloatingScrollbarPointerUp,
  scheduleHorizontalScrollbarUpdate,
} = useFloatingHorizontalScrollbar({
  tableShellRef,
  watchedSources: [filteredLogs, tableMaxHeight],
  positioning: 'container',
});

function typeFilterKey(log: SyncRunLog) {
  return log.runType?.trim() || log.syncType;
}

function isMergedLog(log: SyncRunLog) {
  return log.runStatus === 'MERGED';
}

function statusFilterKey(log: SyncRunLog) {
  return isMergedLog(log) ? 'MERGED' : log.status;
}

function logDisplayStatusText(log: SyncRunLog) {
  return isMergedLog(log) ? '已合并' : logStatusText(log.status);
}

function logDisplayStatusType(log: SyncRunLog) {
  return isMergedLog(log) ? 'info' : logStatusType(log.status);
}

function mergedTargetText(log: SyncRunLog) {
  const parentRun = log.parentRunRunId || log.parentRunId;
  return parentRun == null || String(parentRun).trim() === ''
    ? '-'
    : String(parentRun);
}

function sourcePageText(log: SyncRunLog) {
  return log.sourcePageKey || log.requestReason || '-';
}

async function handleExpandChange() {
  tableRef.value?.doLayout?.();
  await scheduleHorizontalScrollbarUpdate();
  wakeHorizontalScrollbar();
}

async function copyRawError(text?: string | null) {
  const value = (text ?? '').trim();
  if (!value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success('错误原文已复制');
  } catch {
    ElMessage.warning('当前浏览器不允许自动复制，请手动选择文本');
  }
}
</script>

<template>
  <el-card shadow="never" class="panel-card">
    <template #header>
      <div class="panel-header">
        <div>
          <div class="panel-title">最近同步日志</div>
        </div>
        <div class="sync-log-actions">
          <el-select
            v-model="typeFilter"
            class="sync-log-filter"
            size="small"
            placeholder="全部类型"
            clearable
            fit-input-width
            popper-class="platform-select-dropdown"
          >
            <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select
            v-model="statusFilter"
            class="sync-log-filter"
            size="small"
            placeholder="全部结果"
            clearable
            fit-input-width
            popper-class="platform-select-dropdown"
          >
            <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-button link :icon="Refresh" :loading="refreshing" @click="$emit('refresh')">刷新</el-button>
        </div>
      </div>
    </template>

    <div
      ref="tableShellRef"
      class="sync-log-table-shell"
      :class="{ 'is-scrollbar-awake': scrollbarAwake, 'has-horizontal-overflow': hasHorizontalOverflow }"
      tabindex="0"
      @mouseenter="wakeHorizontalScrollbar"
      @mousemove="wakeHorizontalScrollbar"
      @focusin="wakeHorizontalScrollbar"
      @wheel="handleHorizontalWheel"
    >
      <el-table
        ref="tableRef"
        :data="filteredLogs"
        :max-height="tableMaxHeight"
        row-key="id"
        size="small"
        border
        class="sync-log-table"
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="sync-log-detail">
              <div class="sync-log-detail-item">
                <span>运行编号</span>
                <strong>{{ row.runId || row.id }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>触发来源</span>
                <strong>{{ syncTriggerTypeText(row.triggerType) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>同步内容</span>
                <strong>{{ syncLogTypeText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>当前结果</span>
                <strong>{{ logDisplayStatusText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>数据追平</span>
                <strong>{{ freshnessStatusText(row.freshnessStatus) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>删除对账</span>
                <strong>{{ deleteReconciliationStatusText(row.deleteReconciliationStatus) }}</strong>
              </div>
              <div v-if="isMergedLog(row)" class="sync-log-detail-item">
                <span>已并入</span>
                <strong>{{ mergedTargetText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>来源页面</span>
                <strong>{{ sourcePageText(row) }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>写入记录</span>
                <strong>{{ row.recordCount }}</strong>
              </div>
              <div class="sync-log-detail-item">
                <span>错误信息</span>
                <strong>{{ row.errorSummary || '-' }}</strong>
              </div>
              <div class="sync-log-detail-item sync-log-detail-item-wide">
                <span>完整消息</span>
                <strong>{{ syncLogMessage(row) }}</strong>
              </div>
            </div>
            <div class="sync-log-attention">
              <div class="sync-log-attention-counts">
                <span>失败项 {{ row.failureCount ?? 0 }}</span>
                <span>待人工处置 {{ row.manualAttentionCount ?? 0 }}</span>
                <span>可查明细 {{ row.diagnosticCount ?? 0 }}</span>
                <span>相关事件 {{ row.eventCount ?? 0 }}</span>
              </div>
              <el-button size="small" text type="primary" @click="$emit('openDetails', row)">
                查看全部 {{ row.diagnosticCount ?? 0 }} 项明细
              </el-button>
            </div>
            <ul v-if="(row.diagnostics ?? []).length" class="sync-log-diagnostic-preview">
              <li v-for="(item, index) in (row.diagnostics ?? []).slice(0, DIAGNOSTIC_PREVIEW_LIMIT)" :key="`${item.kind}-${index}`">
                <el-tag size="small" effect="plain" type="danger">{{ diagnosticKindText(item.kind) }}</el-tag>
                <strong>{{ item.rawError || item.dispositionReason || item.status || '-' }}</strong>
              </li>
            </ul>
            <ul v-if="(row.eventTrail ?? []).length" class="sync-log-event-preview">
              <li v-for="(event, index) in (row.eventTrail ?? []).slice(0, EVENT_PREVIEW_LIMIT)" :key="`${event.eventId}-${index}`">
                <span class="sync-log-event-time">{{ formatLogTime({ finishedAt: event.createdAt } as SyncRunLog) }}</span>
                <span class="sync-log-event-type">{{ event.eventType || '-' }}</span>
                <span class="sync-log-event-message">{{ event.message || '-' }}</span>
              </li>
            </ul>
            <div v-if="row.latestProgressMessage" class="sync-log-progress-note">
              最新进度：{{ row.latestProgressMessage }}
            </div>
            <div v-if="row.errorSummary" class="sync-log-raw-error">
              <div class="sync-log-raw-error-header">
                <span>运行级错误原文</span>
                <el-button size="small" link @click="copyRawError(row.errorSummary)">复制全文</el-button>
              </div>
              <pre class="sync-log-raw-error-text">{{ row.errorSummary }}</pre>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="126">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="syncTypeTagType(row.syncType)">
              {{ syncLogTypeText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行结果" width="104">
          <template #default="{ row }">
            <el-tag size="small" :type="logDisplayStatusType(row)">{{ logDisplayStatusText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="数据追平" width="112">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="freshnessStatusTagType(row.freshnessStatus)">
              {{ freshnessStatusText(row.freshnessStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="删除对账" width="120">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="deleteReconciliationStatusTagType(row.deleteReconciliationStatus)"
            >
              {{ deleteReconciliationStatusText(row.deleteReconciliationStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ formatLogTime(row) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }">{{ formatDuration(row) }}</template>
        </el-table-column>
        <el-table-column prop="tableCount" label="计划表项" width="96" />
        <el-table-column prop="completedTableCount" label="完成表项" width="96" />
        <el-table-column prop="recordCount" label="写入记录" width="96" />
        <el-table-column label="消息" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ syncLogMessage(row) }}</template>
        </el-table-column>
      </el-table>
      <div
        v-show="isFloatingScrollbarVisible"
        ref="floatingScrollbarRef"
        class="sync-log-floating-horizontal"
        :style="floatingScrollbarStyle"
        aria-hidden="true"
        @mouseenter="wakeHorizontalScrollbar"
        @pointerup="handleFloatingScrollbarPointerUp"
      >
        <div
          ref="floatingTrackRef"
          class="platform-floating-horizontal-track"
          @pointerdown="handleFloatingTrackPointerDown"
        >
          <div
            class="platform-floating-horizontal-thumb"
            :style="floatingThumbStyle"
            @pointerdown="handleFloatingThumbPointerDown"
          />
        </div>
      </div>
    </div>
  </el-card>
</template>

<style scoped>
.sync-log-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.sync-log-filter {
  width: 140px;
}

.sync-log-table-shell {
  position: relative;
  /* 让停靠横条的高 z-index 只在本模块内生效，不参与页面级层叠。 */
  isolation: isolate;
  overflow-x: hidden;
  /* 底部为模块内停靠的横条预留空间，避免盖住最后一行。纵向滚动交给表格自带滚动条。 */
  padding-bottom: 18px;
  outline: none;
  scrollbar-gutter: auto;
}

.sync-log-table {
  width: 100%;
}

.sync-log-table :deep(.el-table__inner-wrapper),
.sync-log-table :deep(.el-table__header-wrapper),
.sync-log-table :deep(.el-table__body-wrapper),
.sync-log-table :deep(.el-table__header),
.sync-log-table :deep(.el-table__body) {
  min-width: 100%;
}

.sync-log-table-shell :deep(.el-table__body-wrapper .el-scrollbar__bar.is-horizontal) {
  display: none !important;
}

.sync-log-floating-horizontal {
  position: absolute;
  height: 16px;
  padding: 5px 0;
  overflow: visible;
  pointer-events: auto;
  opacity: 1;
  background: transparent;
  box-shadow: none;
}

.sync-log-detail {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 10px 16px;
  padding: 12px 16px;
  color: #334155;
  background: #f8fafc;
}

.sync-log-detail-item {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.sync-log-detail-item span {
  color: #64748b;
  font-size: 12px;
}

.sync-log-detail-item strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 500;
}

.sync-log-detail-item-wide {
  grid-column: 1 / -1;
}

.sync-log-attention {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px 6px;
  background: #f8fafc;
}

.sync-log-attention-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: #475569;
}

.sync-log-diagnostic-preview,
.sync-log-event-preview {
  display: grid;
  gap: 6px;
  margin: 0;
  padding: 6px 16px 10px;
  list-style: none;
  background: #f8fafc;
}

.sync-log-diagnostic-preview li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  color: #1f2937;
}

.sync-log-diagnostic-preview strong,
.sync-log-event-message {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 500;
}

.sync-log-event-preview li {
  display: grid;
  grid-template-columns: 150px 150px minmax(0, 1fr);
  gap: 10px;
  font-size: 12px;
}

.sync-log-event-time {
  color: #64748b;
}

.sync-log-event-type {
  color: #334155;
}

.sync-log-progress-note {
  padding: 4px 16px 10px;
  font-size: 12px;
  color: #64748b;
  background: #f8fafc;
}

.sync-log-raw-error {
  margin: 0 16px 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
}

.sync-log-raw-error-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 10px;
  font-size: 12px;
  color: #991b1b;
  border-bottom: 1px solid #fecaca;
}

.sync-log-raw-error-text {
  margin: 0;
  padding: 8px 10px;
  max-height: 180px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  color: #7f1d1d;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
