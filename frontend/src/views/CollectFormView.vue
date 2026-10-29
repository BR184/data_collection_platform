<script setup lang="ts">
import { ElMessage, ElMessageBox } from '../element-plus-services';
// 统一表单填写页：三模板共用一个 field_spec 渲染器，草稿按账号/C/编辑会话/记录块隔离。
import {
  CircleCheck,
  DocumentCopy,
  InfoFilled,
  Loading,
  Plus,
  Refresh,
  WarningFilled,
} from '@element-plus/icons-vue';
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import type { LocationQuery, RouteLocationNormalized, RouteLocationRaw } from 'vue-router';
import { api } from '../api';
import FormFieldControl from '../components/collect-forms/FormFieldControl.vue';
import FormRecordCard from '../components/collect-forms/FormRecordCard.vue';
import { authState, currentIdentityGeneration } from '../composables/auth-state';
import {
  editingSessionId,
  formContextKey,
  readFormDrafts,
  subscribeFormStorageSignals,
  type FormFillContext,
  type FormStorageSignal,
} from '../features/collect-forms/draft-store';
import {
  formatFieldValue,
  isFieldCleared,
  isFieldRequired,
  type FieldValue,
} from '../features/collect-forms/field-logic';
import {
  FormFillingSession,
  type FormBlockState,
  type FormSessionDialogs,
} from '../features/collect-forms/form-filling-session';
import { createUuid } from '../utils/uuid';
import { getErrorMessage } from '../utils/user-message';
import type {
  FormDetailResponse,
  FormFieldSpecView,
  FormRecordView,
  FormReviewerCandidate,
} from '../types/api';

const TEMPLATE_TITLES: Record<string, string> = {
  code_review: '代码走查表',
  issue_response: '问题响应表',
  issue_reply: '问题回复表',
};
const ISSUE_TEMPLATES = [
  { templateKey: 'issue_response', title: '问题响应' },
  { templateKey: 'issue_reply', title: '问题回复' },
];
const RESOURCE_TYPE_LABELS: Record<string, string> = {
  merge_request: '合并请求（MR）',
  issue: '问题（Issue）',
};

const route = useRoute();
const router = useRouter();

function queryText(value: unknown): string {
  if (Array.isArray(value)) {
    return String(value[0] ?? '').trim();
  }
  return String(value ?? '').trim();
}

function queryNumber(value: unknown): number | null {
  const raw = queryText(value);
  if (!raw) {
    return null;
  }
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

function contextFromQuery(path: string, query: LocationQuery): FormFillContext {
  if (path === '/external/code-review-form') {
    const mrIid = queryNumber(query.mrIid);
    return {
      gitlabBaseUrl: queryText(query.gitlabBaseUrl),
      projectId: queryNumber(query.projectId) ?? 0,
      resourceType: 'merge_request',
      resourceId: mrIid == null ? '' : String(mrIid),
      templateKey: 'code_review',
    };
  }
  const resourceType = queryText(query.resourceType);
  let templateKey = queryText(query.templateKey);
  if (!templateKey && resourceType === 'merge_request') {
    templateKey = 'code_review';
  }
  return {
    gitlabBaseUrl: queryText(query.gitlabBaseUrl),
    projectId: queryNumber(query.projectId) ?? 0,
    resourceType,
    resourceId: queryText(query.resourceId),
    templateKey,
  };
}

const routeContext = computed<FormFillContext>(() =>
  contextFromQuery(route.path, route.query),
);

function contextProblem(context: FormFillContext): string {
  if (!context.gitlabBaseUrl) {
    return '链接缺少来源地址（gitlabBaseUrl）';
  }
  if (!context.projectId) {
    return '链接缺少项目编号（projectId）';
  }
  if (!context.resourceType) {
    return '链接缺少资源类型（resourceType）';
  }
  if (!context.resourceId) {
    return '链接缺少资源编号（resourceId）';
  }
  if (context.resourceType !== 'merge_request' && context.resourceType !== 'issue') {
    return `不支持的表单资源类型：${context.resourceType}`;
  }
  if (context.templateKey) {
    const expected =
      context.resourceType === 'merge_request'
        ? ['code_review']
        : ['issue_response', 'issue_reply'];
    if (!expected.includes(context.templateKey)) {
      return '模板与资源类型不匹配，请检查链接中的 templateKey';
    }
  }
  return '';
}

const contextIssue = computed(() => contextProblem(routeContext.value));

const entriesMode = computed(
  () => !contextIssue.value && !routeContext.value.templateKey,
);

/** 页面模式标识：同路由的资源、模板或 query 切换同样会命中同一套离开校验。 */
function modeKeyFor(path: string, query: LocationQuery): string {
  const context = contextFromQuery(path, query);
  const issue = contextProblem(context);
  if (issue) {
    return `invalid:${issue}`;
  }
  if (!context.templateKey) {
    return [
      'entries',
      context.gitlabBaseUrl,
      String(context.projectId),
      context.resourceType,
      context.resourceId,
    ].join('|');
  }
  return `template:${formContextKey(context)}`;
}

const baseQuery = computed<Record<string, string>>(() => ({
  gitlabBaseUrl: routeContext.value.gitlabBaseUrl,
  projectId: String(routeContext.value.projectId),
  resourceType: routeContext.value.resourceType,
  resourceId: routeContext.value.resourceId,
}));

const activeContext = computed<FormFillContext | null>(() =>
  !contextIssue.value && routeContext.value.templateKey ? { ...routeContext.value } : null,
);

const activeContextKey = computed(() =>
  activeContext.value ? formContextKey(activeContext.value) : '',
);

const modeKey = computed(() => modeKeyFor(route.path, route.query));

const session = shallowRef<FormFillingSession | null>(null);
const reviewerCandidates = ref<FormReviewerCandidate[]>([]);
const reviewerLoading = ref(false);
let reviewerRequestSeq = 0;
let sessionSeq = 0;

const detail = computed<FormDetailResponse | null>(() => session.value?.state.detail ?? null);
const recordSpec = (record: FormRecordView) =>
  detail.value?.versionSpecs?.[record.storageVersionId] ?? spec.value;
const blockSpec = (block: FormBlockState) =>
  detail.value?.versionSpecs?.[block.storageVersionId] ?? spec.value;
const spec = computed(() => detail.value?.fieldSpec ?? null);
const blocks = computed<FormBlockState[]>(() => session.value?.state.blocks ?? []);
const templateTitle = computed(
  () => TEMPLATE_TITLES[activeContext.value?.templateKey ?? ''] ?? '统一表单',
);
const severityLevel = computed(() => detail.value?.resource.severityLevel ?? null);
const writable = computed(() => Boolean(detail.value?.writable));
const resourcePending = computed(
  () => Boolean(detail.value) && detail.value?.resource.rootId == null,
);

const sortedRecords = computed<FormRecordView[]>(() => {
  const list = [...(session.value?.state.records ?? [])];
  return list.sort((left, right) => {
    const leftTime = left.createdAt ? Date.parse(left.createdAt) : 0;
    const rightTime = right.createdAt ? Date.parse(right.createdAt) : 0;
    if (leftTime !== rightTime) {
      return leftTime - rightTime;
    }
    return compareIds(left.id, right.id);
  });
});

const latestRecordId = computed(() => {
  if (!detail.value || detail.value.templateKey === 'code_review') {
    return null;
  }
  const list = sortedRecords.value;
  return list.length ? list[list.length - 1].id : null;
});

interface FormDisplayRow {
  key: string;
  record: FormRecordView | null;
  blocks: FormBlockState[];
}

interface FormDisplaySection {
  kind: 'records' | 'drafts';
  title: string;
  count: number;
  emptyText: string;
  rows: FormDisplayRow[];
}

// 编辑按稳定记录ID原位展示；新建及暂时找不到原记录的草稿留在独立区，不改变会话归属。
const formSections = computed<FormDisplaySection[]>(() => {
  const recordIds = new Set(sortedRecords.value.map((record) => record.id));
  const edits = new Map<string, FormBlockState[]>();
  const standalone: FormBlockState[] = [];
  for (const block of blocks.value) {
    if (block.kind === 'edit' && block.targetRecordId && recordIds.has(block.targetRecordId)) {
      const bucket = edits.get(block.targetRecordId) ?? [];
      bucket.push(block);
      edits.set(block.targetRecordId, bucket);
    } else {
      standalone.push(block);
    }
  }
  return [
    {
      kind: 'records', title: '已保存记录', count: sortedRecords.value.length, emptyText: '暂无已保存记录',
      rows: sortedRecords.value.map((record) => ({ key: `record-${record.id}`, record, blocks: edits.get(record.id) ?? [] })),
    },
    {
      kind: 'drafts', title: standalone.some((block) => block.kind === 'edit') ? '本机填写草稿' : '新建草稿',
      count: standalone.length, emptyText: '暂无新建草稿',
      rows: standalone.map((block) => ({ key: block.key, record: null, blocks: [block] })),
    },
  ];
});

// 仅用于资源栏展示；来源身份和请求上下文保持原值。
const sourceHost = computed(() => {
  try { return new URL(routeContext.value.gitlabBaseUrl).host; }
  catch { return routeContext.value.gitlabBaseUrl; }
});

async function copyAddress(value: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success('链接已复制');
  } catch {
    ElMessage.warning('未能复制链接，请从地址提示中复制');
  }
}

const hasFailedDrafts = computed(() => session.value?.hasFailedDrafts() ?? false);

function compareIds(left: string, right: string): number {
  try {
    const leftId = BigInt(left);
    const rightId = BigInt(right);
    return leftId < rightId ? -1 : leftId > rightId ? 1 : 0;
  } catch {
    return left.localeCompare(right, 'en');
  }
}

function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' });
}

function blockTitle(block: FormBlockState): string {
  return block.kind === 'create' ? '新增填写' : `编辑记录 ${block.targetRecordId ?? ''}`;
}

function recordFieldValue(record: FormRecordView, field: FormFieldSpecView): string {
  if (!spec.value) {
    return '—';
  }
  const value = record.fields?.[field.apiKey];
  if (field.control === 'person-select' && typeof value === 'string' && value) {
    return record.fieldNames?.[field.apiKey] || value;
  }
  return formatFieldValue(spec.value, field, value as FieldValue);
}

type StatusTone = 'muted' | 'info' | 'success' | 'warning' | 'error';

interface BlockStatus {
  text: string;
  tone: StatusTone;
  key: 'idle' | 'saving' | 'saved' | 'warning' | 'unknown';
}

function blockStatus(block: FormBlockState): BlockStatus {
  if (session.value?.state.stopped) {
    return { text: '已停止自动保存', tone: 'warning', key: 'warning' };
  }
  if (block.submitState === 'unknown') {
    return { text: '提交结果待确认', tone: 'warning', key: 'unknown' };
  }
  if (block.submitState === 'pending') {
    return { text: '正在提交…', tone: 'info', key: 'saving' };
  }
  if (block.submitState === 'conflict') {
    return { text: '记录已有更新，待核对', tone: 'warning', key: 'warning' };
  }
  if (block.readOnly) {
    if (block.savedElsewhere) {
      return { text: '原操作已在其它标签页保存，当前输入待处理', tone: 'warning', key: 'warning' };
    }
    if (block.structureStale) {
      return { text: '模板结构已变化，仅可查看', tone: 'warning', key: 'warning' };
    }
    return { text: '仅可查看', tone: 'muted', key: 'idle' };
  }
  if (block.saveState === 'saving') {
    return { text: '正在保存草稿…', tone: 'info', key: 'saving' };
  }
  if (block.saveState === 'failed') {
    return {
      text: '草稿自动保存失败，当前内容仅保留在本页',
      tone: 'error',
      key: 'warning',
    };
  }
  if (block.restored) {
    return { text: '已恢复本机草稿，尚未提交', tone: 'info', key: 'idle' };
  }
  if (!session.value?.blockIsDirty(block)) {
    return block.kind === 'create'
      ? { text: '尚未填写', tone: 'muted', key: 'idle' }
      : { text: '未修改', tone: 'muted', key: 'idle' };
  }
  if (block.saveState === 'saved') {
    return {
      text: `草稿已自动保存至本机（${formatTime(block.savedAt)}）`,
      tone: 'success',
      key: 'saved',
    };
  }
  return { text: '尚未提交', tone: 'warning', key: 'warning' };
}

function showsUnsubmitted(block: FormBlockState): boolean {
  if (block.submitState === 'unknown' || block.submitState === 'conflict') {
    return true;
  }
  return session.value?.blockIsDirty(block) ?? false;
}

function fieldRequired(block: FormBlockState, field: FormFieldSpecView): boolean {
  if (!spec.value) {
    return Boolean(field.required);
  }
  return isFieldRequired(spec.value, field, block.fields, severityLevel.value);
}

function fieldCleared(block: FormBlockState, field: FormFieldSpecView): boolean {
  if (!spec.value) {
    return false;
  }
  return isFieldCleared(spec.value, field, block.fields, severityLevel.value);
}

function fieldDisabled(block: FormBlockState): boolean {
  return (
    !writable.value
    || block.readOnly
    || block.structureStale
    || block.frozen
    || block.submitState === 'pending'
    || Boolean(session.value?.state.stopped)
  );
}

function fieldError(block: FormBlockState, field: FormFieldSpecView): string {
  return block.errors?.[field.apiKey] ?? '';
}

function editNotLatest(block: FormBlockState): boolean {
  return (
    block.kind === 'edit'
    && latestRecordId.value != null
    && block.targetRecordId !== latestRecordId.value
  );
}

function seedCandidates(items: FormReviewerCandidate[]) {
  const merged = [...reviewerCandidates.value];
  for (const item of items) {
    if (!item?.userId) {
      continue;
    }
    const index = merged.findIndex((candidate) => candidate.userId === item.userId);
    if (index >= 0) {
      merged.splice(index, 1, item);
    } else {
      merged.push(item);
    }
  }
  reviewerCandidates.value = merged;
}

function seedDetailCandidates(loaded: FormDetailResponse | null) {
  if (!loaded) {
    return;
  }
  const items: FormReviewerCandidate[] = [];
  if (loaded.account?.userId) {
    items.push({
      userId: loaded.account.userId,
      realName: loaded.account.displayName || loaded.account.userId,
      deptName: null,
    });
  }
  const personFields = (loaded.fieldSpec?.fields ?? []).filter(
    (field) => field.control === 'person-select',
  );
  for (const record of loaded.records ?? []) {
    for (const field of personFields) {
      const userId = record.fields?.[field.apiKey];
      if (typeof userId === 'string' && userId) {
        items.push({
          userId,
          realName: record.fieldNames?.[field.apiKey] || userId,
          deptName: null,
        });
      }
    }
  }
  seedCandidates(items);
}

async function searchReviewers(keyword: string) {
  const seq = ++reviewerRequestSeq;
  reviewerLoading.value = true;
  try {
    const page = await api.getFormReviewerCandidates({ keyword, limit: 20 });
    if (seq !== reviewerRequestSeq) {
      return;
    }
    const items = [...page.items];
    if (page.currentUserCandidate) {
      items.push(page.currentUserCandidate);
    }
    seedCandidates(items);
  } catch (error) {
    if (seq === reviewerRequestSeq) {
      ElMessage.error(getErrorMessage(error, '走查人加载失败'));
    }
  } finally {
    if (seq === reviewerRequestSeq) {
      reviewerLoading.value = false;
    }
  }
}

const dialogs: FormSessionDialogs = {
  async confirmClearFields(block, cleared) {
    const labels = cleared.map((item) => `“${item.label}”`).join('、');
    return askConfirm(
      `${blockTitle(block)}中已填写的${labels}将因当前选择被清空。确认清空并继续吗？`,
      '清空已填写内容',
      '确认清空',
      '取消',
    );
  },
  async confirmDiscardBlock(block) {
    return askConfirm(
      `将丢弃${blockTitle(block)}中尚未提交的内容。此操作只影响该填写块，确认丢弃吗？`,
      '丢弃草稿',
      '丢弃草稿',
      '继续编辑',
    );
  },
  async confirmVoidRecord(record) {
    const confirmed = await askConfirm(
      `作废后记录 ${record.id} 将从当前资源的有效记录中移除，页面不再显示，确认继续吗？`,
      '作废表单记录',
      '继续作废',
      '取消',
    );
    if (!confirmed) {
      return null;
    }
    try {
      const result = (await ElMessageBox.prompt(
        `请输入“作废”以确认作废记录 ${record.id}。`,
        '确认作废',
        {
          confirmButtonText: '确认作废',
          cancelButtonText: '取消',
          inputPlaceholder: '作废',
          customClass: 'form-filling-dialog',
          inputValidator: (value: string) => value.trim() === '作废' || '请输入“作废”',
        },
      )) as { value: string };
      return result.value.trim();
    } catch {
      return null;
    }
  },
  async confirmSameReviewer(reviewerUserId) {
    const candidate = reviewerCandidates.value.find((item) => item.userId === reviewerUserId);
    const reviewer = candidate ? `${candidate.realName}（${candidate.userId}）` : (reviewerUserId || '该走查人');
    return askConfirm(
      `${reviewer}在本资源下已有有效走查记录，确认仍然新增一条吗？`,
      '同一走查人确认',
      '确认新增',
      '取消',
    );
  },
  async confirmAcceptLatest(block, record) {
    return askConfirm(
      `采用记录 ${record.id} 的最新版本（版本 ${record.recordVersion}）会丢弃${blockTitle(block)}中的当前输入，并加载最新内容作为编辑基准。确认继续吗？`,
      '升级编辑基准',
      '采用最新版本',
      '取消',
    );
  },
  notify(message, level) {
    ElMessage[level](message);
  },
  focusField(block, apiKey) {
    void nextTick(() => {
      const container = document.querySelector<HTMLElement>(
        `[data-field-id="${block.key}:${apiKey}"]`,
      );
      container?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
      container?.querySelector<HTMLElement>('input, textarea, .el-select__wrapper')?.focus();
    });
  },
};

async function askConfirm(
  message: string,
  title: string,
  confirmButtonText: string,
  cancelButtonText: string,
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning',
      confirmButtonText,
      cancelButtonText,
      customClass: 'form-filling-dialog',
    });
    return true;
  } catch {
    return false;
  }
}

function disposeSession() {
  const current = session.value;
  if (!current) {
    return;
  }
  current.flushDrafts();
  current.dispose();
  session.value = null;
}

function activateSession() {
  const context = activeContext.value;
  disposeSession();
  if (!context) {
    return;
  }
  const seq = ++sessionSeq;
  reviewerCandidates.value = [];
  const instance = new FormFillingSession({
    context,
    accountUserId: authState.currentUser.username,
    editingSessionId: editingSessionId(),
    identityGeneration: currentIdentityGeneration,
    api,
    dialogs,
    createId: createUuid,
  });
  session.value = instance;
  void instance.initialize().then(() => {
    if (seq !== sessionSeq || session.value !== instance) {
      return;
    }
    seedDetailCandidates(instance.state.detail);
    if (instance.state.detail?.templateKey === 'code_review' && instance.state.detail.writable) {
      void searchReviewers('');
    }
  });
}

interface EntryItem {
  templateKey: string;
  title: string;
  loading: boolean;
  detail: FormDetailResponse | null;
  error: string;
  registered: boolean;
  draftCount: number;
}

const entries = ref<EntryItem[]>([]);
const entriesLoading = ref(false);
let entryRequestSeq = 0;

async function loadEntries() {
  if (!entriesMode.value) {
    return;
  }
  const context = routeContext.value;
  const seq = ++entryRequestSeq;
  entriesLoading.value = true;
  entries.value = ISSUE_TEMPLATES.map((item) => ({
    ...item,
    loading: true,
    detail: null,
    error: '',
    registered: true,
    draftCount: 0,
  }));
  const results = await Promise.allSettled(
    ISSUE_TEMPLATES.map((item) =>
      api.getFormDetail({
        gitlabBaseUrl: context.gitlabBaseUrl,
        projectId: context.projectId,
        resourceType: context.resourceType,
        resourceId: context.resourceId,
        templateKey: item.templateKey,
      }),
    ),
  );
  if (seq !== entryRequestSeq || !entriesMode.value) {
    return;
  }
  entries.value = ISSUE_TEMPLATES.map((item, index) => {
    const account = authState.currentUser.username;
    const contextKey = formContextKey({ ...context, templateKey: item.templateKey });
    const draftCount = readFormDrafts(account, contextKey).length;
    const result = results[index];
    if (result.status === 'fulfilled') {
      return {
        ...item,
        loading: false,
        detail: result.value,
        error: '',
        registered: true,
        draftCount,
      };
    }
    const message = getErrorMessage(result.reason, '模板入口加载失败');
    const registered = !message.includes('尚未登记') && !message.includes('未登记');
    return {
      ...item,
      loading: false,
      detail: null,
      error: message,
      registered,
      draftCount,
    };
  });
  entriesLoading.value = false;
  const available = entries.value.find((item) => item.detail);
  seedDetailCandidates(available?.detail ?? null);
  if (available?.detail?.templateKey === 'code_review') {
    void searchReviewers('');
  }
}

function openEntry(item: EntryItem) {
  if (!item.detail) {
    return;
  }
  void router.push({
    path: '/external/forms',
    query: { ...baseQuery.value, templateKey: item.templateKey },
  });
}

function backToEntries() {
  void router.push({ path: '/external/forms', query: baseQuery.value });
}

function applyMode() {
  if (contextIssue.value) {
    disposeSession();
    entries.value = [];
    entriesLoading.value = false;
    return;
  }
  if (entriesMode.value) {
    disposeSession();
    void loadEntries();
    return;
  }
  entryRequestSeq += 1;
  entries.value = [];
  entriesLoading.value = false;
  activateSession();
}

watch(modeKey, applyMode, { immediate: true });

watch(
  () => (session.value?.state.records ?? []).map((record) => record.id).join(','),
  () => {
    if (session.value?.state.detail) {
      seedDetailCandidates(session.value.state.detail);
    }
  },
);

function scrollToBlock(block: FormBlockState) {
  void nextTick(() => {
    document
      .querySelector<HTMLElement>(`[data-block-key="${block.key}"]`)
      ?.scrollIntoView?.({ block: 'center', behavior: 'smooth' });
  });
}

function addBlock() {
  const block = session.value?.addCreateBlock();
  if (!block) {
    ElMessage.warning('当前不可新增填写块');
    return;
  }
  scrollToBlock(block);
}

function editRecord(record: FormRecordView) {
  session.value?.startEdit(record);
}

function voidRecord(record: FormRecordView) {
  void session.value?.voidRecord(record);
}

async function changeField(block: FormBlockState, apiKey: string, value: unknown) {
  await session.value?.changeField(block, apiKey, value);
}

async function submitBlock(block: FormBlockState) {
  const current = session.value;
  if (!current) {
    return;
  }
  if (block.submitState === 'unknown') {
    await current.verify(block);
  } else {
    await current.submit(block);
  }
  await restoreRecordAfterEdit(current, block);
}

async function discardBlock(block: FormBlockState) {
  const current = session.value;
  if (current && await current.discardBlock(block)) {
    await restoreRecordAfterEdit(current, block);
  }
}

// 保存或取消确实关闭编辑块后，恢复卡片仅在视口外时滚入；失败与确认取消均保留当前编辑位置。
async function restoreRecordAfterEdit(current: FormFillingSession, block: FormBlockState) {
  if (block.kind !== 'edit') return;
  await nextTick();
  if (current !== session.value || current.state.blocks.includes(block)) return;
  const active = document.activeElement;
  if (active instanceof HTMLElement && active !== document.body && active.isConnected) return;
  document.querySelector<HTMLElement>(`[data-record-id="${block.targetRecordId}"]`)
    ?.scrollIntoView?.({ block: 'nearest' });
}

function copyBlock(block: FormBlockState) {
  const copied = session.value?.copyBlock(block);
  if (copied) {
    scrollToBlock(copied);
  }
}

async function acceptLatest(block: FormBlockState) {
  await session.value?.acceptLatest(block);
}

function retryDraft(block: FormBlockState) {
  session.value?.persistBlockNow(block);
}

interface InteractionSnapshot {
  active: HTMLElement | null;
  selectionStart: number | null;
  selectionEnd: number | null;
  scrollX: number;
  scrollY: number;
}

// 点击离开/刷新按钮会把焦点移到按钮上；单独记住最后编辑的表单字段，供取消时恢复。
let lastFocusedField: HTMLElement | null = null;

function trackFieldFocus(event: FocusEvent) {
  const target = event.target;
  if (!(target instanceof HTMLElement) || !target.closest('.form-block')) {
    return;
  }
  lastFocusedField = target;
}

function captureInteraction(): InteractionSnapshot {
  const fallback =
    document.activeElement instanceof HTMLElement ? document.activeElement : null;
  const tracked = lastFocusedField?.isConnected ? lastFocusedField : null;
  const active = tracked && tracked.closest('.form-block') ? tracked : fallback;
  let selectionStart: number | null = null;
  let selectionEnd: number | null = null;
  if (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement) {
    selectionStart = active.selectionStart;
    selectionEnd = active.selectionEnd;
  }
  return {
    active,
    selectionStart,
    selectionEnd,
    scrollX: window.scrollX,
    scrollY: window.scrollY,
  };
}

async function restoreInteraction(snapshot: InteractionSnapshot) {
  await nextTick();
  window.scrollTo(snapshot.scrollX, snapshot.scrollY);
  const active = snapshot.active;
  if (active?.isConnected) {
    active.focus({ preventScroll: true });
    if (
      (active instanceof HTMLInputElement || active instanceof HTMLTextAreaElement)
      && snapshot.selectionStart != null
      && snapshot.selectionEnd != null
    ) {
      try {
        active.setSelectionRange(snapshot.selectionStart, snapshot.selectionEnd);
      } catch {
        // 部分输入类型不支持 selection，焦点保留即可。
      }
    }
  }
}

async function refreshList() {
  const current = session.value;
  if (!current || current.state.refreshing) {
    return;
  }
  const snapshot = captureInteraction();
  await current.reload();
  await restoreInteraction(snapshot);
}

function retryInitialLoad() {
  void session.value?.initialize().then(() => {
    seedDetailCandidates(session.value?.state.detail ?? null);
    if (session.value?.state.detail?.templateKey === 'code_review') {
      void searchReviewers('');
    }
  });
}

function flushDrafts() {
  session.value?.flushDrafts();
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  flushDrafts();
  if (!session.value?.hasUnsubmitted()) {
    return;
  }
  event.preventDefault();
  event.returnValue = '';
}

function handleVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    flushDrafts();
  }
}

/**
 * 离开填写上下文的统一校验：刷新草稿后提示未提交内容。
 *
 * 用户取消时保持原 URL、模板、输入、焦点与提交归属；确认后由路由切换接管。
 */
async function confirmFormLeave(): Promise<boolean> {
  const current = session.value;
  if (!current) {
    return true;
  }
  const interaction = captureInteraction();
  current.flushDrafts();
  if (!current.hasUnsubmitted()) {
    return true;
  }
  const items = current.unsubmittedBlocks();
  const list = items
    .map((block) => `${blockTitle(block)}（${blockStatus(block).text}）`)
    .join('\n');
  const failed = current.hasFailedDrafts();
  const consequence = failed
    ? '部分内容未能自动保存到本机，离开后可能丢失。'
    : '草稿已保存到当前浏览器，离开后可再次恢复；本次内容仍未正式提交。';
  try {
    await ElMessageBox.confirm(
      `${list}\n\n${consequence}`,
      '离开填写页',
      {
        type: 'warning',
        confirmButtonText: failed ? '仍然离开' : '保留草稿并离开',
        cancelButtonText: '继续填写',
        customClass: 'form-leave-confirm form-filling-dialog',
        // hash 路由下浏览器后退会先改写地址并触发 hashchange，默认行为会关闭本框导致无法取消。
        closeOnHashChange: false,
      },
    );
    return true;
  } catch {
    await restoreInteraction(interaction);
    return false;
  }
}

/** 取消离开时回到原地址：浏览器后退会先改写 URL，必须由路由 replace 恢复。 */
function stayOnForm(from: RouteLocationNormalized): RouteLocationRaw {
  return {
    path: from.path,
    query: from.query,
    hash: from.hash,
    replace: true,
  };
}

onBeforeRouteLeave(async (_to, from) => {
  return (await confirmFormLeave()) ? true : stayOnForm(from);
});

// 同路由内切换资源/模板/query 不会触发离开守卫，共用同一校验避免绕开提示。
onBeforeRouteUpdate(async (to, from) => {
  if (modeKeyFor(to.path, to.query) === modeKey.value) {
    return true;
  }
  return (await confirmFormLeave()) ? true : stayOnForm(from);
});

let unsubscribeStorage = () => undefined;

function handleStorageSignal(signal: FormStorageSignal) {
  const current = session.value;
  if (!current) {
    return;
  }
  if (
    signal.type === 'logout'
    && signal.accountUserId === authState.currentUser.username
  ) {
    current.stopForLogout();
    reviewerCandidates.value = [];
    return;
  }
  if (
    signal.type === 'draft-save-outcome'
    && signal.accountUserId === authState.currentUser.username
    && signal.contextKey === activeContextKey.value
  ) {
    current.applyRemoteSaveOutcome({
      clearedDraftIds: signal.draftIds,
      submittedFields: signal.submittedFields ?? null,
      record:
        signal.recordId && signal.recordVersion != null
          ? { recordId: signal.recordId, recordVersion: signal.recordVersion }
          : null,
      savedElsewhere: signal.savedElsewhere ?? [],
    });
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  window.addEventListener('blur', flushDrafts);
  document.addEventListener('visibilitychange', handleVisibilityChange);
  document.addEventListener('focusin', trackFieldFocus);
  unsubscribeStorage = subscribeFormStorageSignals(handleStorageSignal);
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload);
  window.removeEventListener('blur', flushDrafts);
  document.removeEventListener('visibilitychange', handleVisibilityChange);
  document.removeEventListener('focusin', trackFieldFocus);
  unsubscribeStorage();
  disposeSession();
});
</script>

<template>
  <div class="external-form-shell form-filling-shell">
    <div class="external-form-page form-fill-page">
      <template v-if="contextIssue">
        <el-card class="external-form-card" shadow="never">
          <el-result icon="warning" title="链接无法打开填写页" :sub-title="contextIssue">
            <template #extra>
              <div class="form-muted">请向链接发送方确认完整的来源、项目、资源类型与资源编号。</div>
            </template>
          </el-result>
        </el-card>
      </template>

      <template v-else-if="entriesMode">
        <header class="form-page-header">
          <h1 class="external-form-title">选择填写模板</h1>
          <div class="form-resource-bar">
            <span class="form-resource-segment">{{ RESOURCE_TYPE_LABELS[routeContext.resourceType] || routeContext.resourceType }}</span>
            <span class="form-resource-segment">项目 {{ routeContext.projectId }} / #{{ routeContext.resourceId }}</span>
            <span class="form-resource-segment form-source-address">
              <el-tooltip :content="routeContext.gitlabBaseUrl" placement="bottom"><span class="form-source-host" :title="routeContext.gitlabBaseUrl">{{ sourceHost }}</span></el-tooltip>
              <button type="button" class="form-copy-address" aria-label="复制GitLab来源地址" title="复制GitLab来源地址" @click="copyAddress(routeContext.gitlabBaseUrl)"><el-icon><DocumentCopy /></el-icon></button>
            </span>
          </div>
        </header>

        <section v-loading="entriesLoading" class="form-entry-section">
          <header class="form-section-header">
            <div class="external-form-card-header">
              <div class="external-form-card-title">已登记模板</div>
              <el-button :icon="Refresh" link type="primary" @mousedown.stop.prevent @click.stop="loadEntries">重新加载</el-button>
            </div>
          </header>
          <div class="form-entry-list">
            <div
              v-for="item in entries"
              :key="item.templateKey"
              class="form-entry-card"
            >
              <div class="form-entry-main">
                <div class="form-entry-title">
                  {{ item.title }}
                  <el-tag v-if="item.detail" size="small" type="success" effect="plain">
                    {{ item.detail.versionLabel || '已发布' }}
                  </el-tag>
                  <el-tag v-if="item.detail && !item.detail.writable" size="small" type="warning" effect="plain">
                    仅历史查看
                  </el-tag>
                  <el-tag v-if="item.detail?.resource.rootId == null && item.detail" size="small" type="info" effect="plain">
                    资源待同步
                  </el-tag>
                  <el-tag v-if="item.error && !item.registered" size="small" type="info" effect="plain">
                    未登记模板
                  </el-tag>
                </div>
                <div class="form-entry-meta">
                  <span v-if="item.detail">已保存记录 {{ item.detail.records.length }} 条</span>
                  <span v-if="item.draftCount > 0">本机草稿 {{ item.draftCount }} 份</span>
                  <span v-if="item.error && item.registered" class="form-error-text">{{ item.error }}</span>
                  <span v-else-if="item.error" class="form-muted">{{ item.error }}</span>
                  <span v-else-if="item.loading">正在读取模板…</span>
                </div>
              </div>
              <el-button
                v-if="item.detail"
                type="primary"
                plain
                @click="openEntry(item)"
              >
                进入填写
              </el-button>
            </div>
          </div>
          <el-alert
            v-if="!entriesLoading && entries.length > 0 && !entries.some((item) => item.detail)"
            type="info"
            :closable="false"
            title="该资源尚未登记可填写的问题响应或问题回复模板"
            description="请联系管理员完成模板范围登记后再打开此入口。"
          />
        </section>
      </template>

      <template v-else>
        <header class="form-page-header">
          <div class="form-page-heading">
            <div class="form-page-title-group">
              <h1 class="external-form-title">{{ templateTitle }}</h1>
              <span v-if="detail?.versionLabel" class="form-current-version">{{ detail.versionLabel }}</span>
              <el-tag v-if="detail && !detail.writable" type="warning" effect="plain" size="small">仅历史查看</el-tag>
              <el-tag v-if="resourcePending" type="info" effect="plain" size="small">资源待同步</el-tag>
            </div>
            <el-button v-if="detail?.writable" class="form-add-button" type="primary" :icon="Plus" title="新增填写块" data-action="add-block" @click="addBlock">新增填写</el-button>
          </div>
          <div class="form-resource-bar">
            <span class="form-resource-segment">{{ RESOURCE_TYPE_LABELS[routeContext.resourceType] || routeContext.resourceType }}</span>
            <span class="form-resource-segment">项目 {{ routeContext.projectId }} / #{{ routeContext.resourceId }}</span>
            <span class="form-resource-segment">表单账号 <strong>{{ detail?.account.displayName || '—' }}</strong></span>
            <span class="form-resource-segment form-source-address">
              <el-tooltip :content="routeContext.gitlabBaseUrl" placement="bottom">
                <span class="form-source-host" :title="routeContext.gitlabBaseUrl">{{ sourceHost }}</span>
              </el-tooltip>
              <button type="button" class="form-copy-address" aria-label="复制GitLab来源地址" title="复制GitLab来源地址" @click="copyAddress(routeContext.gitlabBaseUrl)"><el-icon><DocumentCopy /></el-icon></button>
            </span>
            <span class="form-resource-segment form-resource-link">
              <template v-if="detail?.resource.webUrl">
                <el-tooltip :content="detail.resource.webUrl" placement="bottom">
                  <a :href="detail.resource.webUrl" :title="detail.resource.webUrl" target="_blank" rel="noreferrer">在 GitLab 打开 ↗</a>
                </el-tooltip>
                <button type="button" class="form-copy-address" aria-label="复制资源链接" title="复制资源链接" @click="copyAddress(detail.resource.webUrl)"><el-icon><DocumentCopy /></el-icon></button>
              </template>
              <span v-else>资源链接 —</span>
            </span>
            <span v-if="severityLevel" class="form-resource-segment">{{ severityLevel }}</span>
            <code class="form-template-key">{{ activeContext?.templateKey }}</code>
            <el-button v-if="activeContext?.resourceType === 'issue'" link @click="backToEntries">返回模板入口</el-button>
          </div>
        </header>

        <el-alert
          v-if="session?.state.stopped"
          type="warning"
          :closable="false"
          title="已退出登录"
          description="本平台该账号的全部表单草稿已清除，当前页面不再接受输入。"
        />
        <el-alert
          v-else-if="session?.state.notice"
          type="success"
          :closable="true"
          :title="session.state.notice"
          @close="session.state.notice = ''"
        />
        <el-alert
          v-if="session?.state.refreshError"
          type="warning"
          :closable="true"
          :title="session.state.refreshError"
          @close="session.state.refreshError = ''"
        >
          <template #default>
            <el-button link type="primary" @click="refreshList">重新加载列表</el-button>
          </template>
        </el-alert>
        <el-alert
          v-if="hasFailedDrafts"
          type="error"
          :closable="false"
          title="部分草稿未能自动保存到本机"
          description="当前内容仅保留在本页。请在各填写块中重试保存，或保持页面打开。"
        />
        <el-alert
          v-if="detail && !detail.writable"
          type="info"
          :closable="false"
          title="当前模板范围已停用，仅保留历史查看"
          description="仍可查看已保存记录和本机草稿，但不能新增、编辑或作废记录。"
        />

        <el-card v-if="!detail && session?.state.loading" v-loading="true" class="external-form-card" shadow="never">
          <div class="form-loading-placeholder">正在读取表单模板与已保存记录…</div>
        </el-card>
        <el-card v-else-if="!detail && session?.state.error" class="external-form-card" shadow="never">
          <el-result icon="error" title="表单加载失败" :sub-title="session.state.error">
            <template #extra>
              <el-button type="primary" @click="retryInitialLoad">重新加载</el-button>
            </template>
          </el-result>
        </el-card>

        <template v-if="detail && spec">
          <section
            v-for="section in formSections"
            :key="section.kind"
            v-loading="section.kind === 'records' && session?.state.loading"
            :class="section.kind === 'records' ? 'form-record-section' : 'form-draft-section'"
            :aria-label="section.title"
          >
            <header class="form-section-header">
              <div class="external-form-card-header">
                <div class="external-form-card-title">{{ section.title }}（{{ section.count }}）</div>
                <el-button
                  v-if="section.kind === 'records'"
                  :icon="Refresh"
                  :loading="session?.state.refreshing"
                  data-action="refresh-list" aria-label="刷新列表" title="刷新列表" text
                  @mousedown.stop.prevent
                  @click.stop="refreshList"
                >刷新</el-button>
              </div>
            </header>
            <div v-if="section.rows.length === 0" class="form-empty-records">{{ section.emptyText }}</div>
            <div v-else :class="section.kind === 'records' ? 'form-record-list' : 'form-block-list'">
              <template v-for="row in section.rows" :key="row.key">
                <FormRecordCard
                  v-if="row.record && row.blocks.length === 0"
                  :spec="recordSpec(row.record)"
                  :record="row.record"
                  :template-title="templateTitle"
                  :latest="latestRecordId === row.record.id"
                  :writable="detail.writable"
                  :editing="false"
                  @edit="editRecord(row.record)"
                  @void="voidRecord(row.record)"
                />
              <article
                v-for="block in row.blocks"
                :key="block.key"
                class="form-block"
                :data-block-key="block.key"
                :data-record-id="block.kind === 'edit' ? block.targetRecordId : undefined"
              >
                <header class="form-block-header">
                  <div class="form-block-heading">
                    <span class="form-block-title">{{ blockTitle(block) }}</span>
                    <el-tag v-if="showsUnsubmitted(block)" size="small" type="warning" effect="plain">
                      尚未提交
                    </el-tag>
                  </div>
                  <div class="external-form-actions">
                    <span class="form-block-status" :class="`tone-${blockStatus(block).tone}`">
                      <el-icon v-if="blockStatus(block).key === 'saving'" class="is-loading">
                        <Loading />
                      </el-icon>
                      <el-icon v-else-if="blockStatus(block).key === 'saved'">
                        <CircleCheck />
                      </el-icon>
                      <el-icon v-else-if="blockStatus(block).key === 'warning'">
                        <WarningFilled />
                      </el-icon>
                      <el-icon v-else>
                        <InfoFilled />
                      </el-icon>
                      <span>{{ blockStatus(block).text }}</span>
                    </span>
                    <el-button
                      v-if="block.readOnly && detail.writable"
                      size="small"
                      :icon="DocumentCopy"
                      :disabled="!session?.canCopyBlock(block)"
                      :title="session?.canCopyBlock(block)
                        ? ''
                        : (block.submitState === 'pending' ? '请等待提交结果' : '请先核验本次提交结果')"
                      @click="copyBlock(block)"
                    >
                      复制内容到新填写块
                    </el-button>
                    <el-button
                      v-if="block.saveState === 'failed'"
                      size="small"
                      type="warning"
                      plain
                      @click="retryDraft(block)"
                    >
                      重试保存
                    </el-button>
                  </div>
                </header>

                <div v-if="editNotLatest(block)" class="form-block-tip">
                  修改此记录不会替换最新记录；如需提交新一次计划，请新增一条记录。
                </div>
                <el-alert
                  v-if="block.readOnly && block.readOnlyReason"
                  type="info"
                  :closable="false"
                  :title="block.readOnlyReason"
                />
                <el-alert
                  v-if="block.notice"
                  class="form-block-notice"
                  type="warning"
                  :closable="false"
                  :title="block.notice"
                />

                <div v-if="block.conflictRecord" class="form-conflict-panel">
                  <div class="form-conflict-title">
                    最新记录版本 {{ block.conflictRecord.recordVersion }}（当前草稿基于版本
                    {{ block.baseRecordVersion ?? '—' }}）
                  </div>
                  <div class="form-record-fields">
                    <div
                      v-for="field in blockSpec(block).fields"
                      :key="field.apiKey"
                      class="form-record-field"
                    >
                      <span class="form-record-label">{{ field.label || field.name }}</span>
                      <span class="form-record-value">
                        {{ recordFieldValue(block.conflictRecord, field) }}
                      </span>
                    </div>
                  </div>
                  <el-button size="small" type="warning" plain @click="acceptLatest(block)">
                    采用最新版本重新编辑
                  </el-button>
                </div>

                <p v-if="block.key === blocks[0]?.key" class="external-form-footnote">草稿仅保存在当前浏览器，点击“保存”后才正式提交。</p>
                <el-form
                  class="external-form-layout form-single-column"
                  label-position="top"
                  @submit.prevent="submitBlock(block)"
                >
                  <el-form-item
                    v-for="field in blockSpec(block).fields"
                    :key="field.apiKey"
                    :label="field.label || field.name"
                    :required="fieldRequired(block, field)"
                    :error="fieldError(block, field)"
                    :class="{ 'is-field-cleared': fieldCleared(block, field) }"
                    :data-field-id="`${block.key}:${field.apiKey}`"
                  >
                    <div class="form-field-control">
                      <FormFieldControl
                        :spec="blockSpec(block)"
                        :field="field"
                        :model-value="block.fields[field.apiKey]"
                        :disabled="fieldDisabled(block) || fieldCleared(block, field)"
                        :reviewer-candidates="reviewerCandidates"
                        :reviewer-loading="reviewerLoading"
                        :search-reviewers="searchReviewers"
                        @update:model-value="(value) => changeField(block, field.apiKey, value)"
                      />
                      <div v-if="fieldCleared(block, field)" class="form-field-hint">
                        当前条件下无需填写
                      </div>
                      <div v-else-if="field.format" class="form-field-hint">{{ field.format }}</div>
                    </div>
                  </el-form-item>

                  <div class="form-block-footer">
                    <div class="form-block-footer-status">
                      <span>{{ blockStatus(block).text }}</span>
                      <span v-if="showsUnsubmitted(block)" class="form-muted">尚未正式提交</span>
                    </div>
                    <div class="external-form-actions">
                    <el-button
                      size="default" aria-label="丢弃草稿"
                      :disabled="!session?.canDiscardBlock(block)"
                      :title="session?.canDiscardBlock(block) ? '' : '请先核验本次提交结果'"
                      @click="discardBlock(block)"
                    >
                      取消
                    </el-button>
                      <el-button
                        v-if="block.submitState === 'pending'"
                        type="primary" plain
                        disabled
                        loading
                      >
                        正在提交…
                      </el-button>
                      <el-button
                        v-else-if="block.submitState === 'unknown'"
                        type="primary" plain
                        @click="submitBlock(block)"
                      >
                        核验提交结果
                      </el-button>
                      <el-button
                        v-else
                        type="primary" plain
                        :disabled="!session?.canSubmitBlock(block)"
                        @click="submitBlock(block)"
                      >
                        保存
                      </el-button>
                    </div>
                  </div>
                </el-form>
              </article>
              </template>
            </div>
          </section>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped src="../components/collect-forms/form-filling.css"></style>
