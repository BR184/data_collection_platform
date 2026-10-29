<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ArrowDown, Delete, Plus, MoreFilled, Rank, Close, Clock } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from '../element-plus-services';
import { collectFormsApi } from '../api-client/collect-forms-api';
import { HttpRequestError, isApiBizError } from '../api-client/request';
import { getErrorMessage } from '../utils/user-message';
import type {
  FormAdminBindingOption,
  FormAdminDraftView,
  FormAdminMilestoneAvailability,
  FormAdminPreviewView,
  FormAdminPublicationView,
  FormAdminScopeDetail,
  FormAdminScopeSummary,
  FormAdminTemplateSummary,
  FormTemplateSpecView,
} from '../types/api';
import {
  CONTROL_OPTIONS,
  addOption,
  applyConditionRows,
  applyFieldRows,
  canClearWhen,
  changeRowControl,
  conditionFieldOptions,
  conditionOperators,
  conditionRowsFromSpec,
  conditionValueOptions,
  controlLabel,
  controlsForType,
  createConditionRow,
  createFieldRow,
  describeConditionRow,
  fieldRowsFromSpec,
  moveFieldRow,
  removeOption,
  updateOption,
  validateFieldRows,
  type ConditionRow,
  type ConditionRowKind,
  type FieldRow,
} from './form-template-admin/form-template-admin';

import { businessError, comparisonSpec, fieldChanges, formTitle, publicationFailure } from './form-template-admin/form-admin-presentation';

const loading = ref(false);
const saving = ref(false);
const previewing = ref(false);
const publishing = ref(false);
const templates = ref<FormAdminTemplateSummary[]>([]);
const bindingOptions = ref<FormAdminBindingOption[]>([]);
const milestones = ref<FormAdminMilestoneAvailability | null>(null);
const selectedScopeId = ref<number | null>(null);
const detail = ref<FormAdminScopeDetail | null>(null);
const preview = ref<FormAdminPreviewView | null>(null);
/** 当前预览所属范围；跨范围切换或迟到响应不得复用。 */
const previewScopeId = ref<number | null>(null);
const publication = ref<FormAdminPublicationView | null>(null);
const fieldRows = ref<FieldRow[]>([]);
const conditionRows = ref<ConditionRow[]>([]);
const specBase = ref<FormTemplateSpecView | null>(null);
const createVisible = ref(false);
const createTemplateKey = ref('code_review');
const createScopeKey = ref('');
const bindingSourceId = ref<number | null>(null);
const bindingProjects = ref('');
const bindingVersionProject = ref<number | null>(null);
const versionLabel = ref('');
const evidenceDraft = ref<Record<string, unknown>>({});
const evidenceNote = ref('');
const selectedMilestoneId = ref<number | null>(null);
const savedSnapshot = ref<string | null>(null);
/** 范围加载/刷新代次；迟到的详情响应不得覆盖更晚的选择或输入。 */
let scopeLoadEpoch = 0;
const conflictScopeId = ref<number | null>(null);
const conflictDraft = ref<FormAdminDraftView | null>(null);
const conflictVisible = ref(false);
const optionsRowIndex = ref<number | null>(null);
const pendingRequest = ref<{
  draftRevision: number;
  previewId: number;
  expectedCurrentVersionId: number | null;
  key: string;
} | null>(null);

const scope = computed<FormAdminScopeSummary | null>(() => detail.value?.scope ?? null);
const allScopes = computed(() => templates.value.flatMap((template) => template.scopes));
const bindingBound = computed(() => Boolean(detail.value?.draft?.binding?.bound));
const canRetire = computed(() => scope.value?.status === 'ACTIVE');
const canDelete = computed(
  () => scope.value?.status === 'DRAFT' && !scope.value.currentVersionId,
);
const publicationActive = computed(() =>
  Boolean(
    publication.value
      && ['PREPARED', 'COPYING', 'VERIFIED', 'VALIDATING', 'BUILDING', 'SWITCHING'].includes(
        publication.value.stage,
      ),
  ),
);
const publicationStageText = computed(() => {
  switch (publication.value?.stage) {
    case 'VALIDATING':
      return '正在校验发布输入…';
    case 'BUILDING':
      return '正在构建空结构…';
    case 'SWITCHING':
      return '正在切换版本…';
    default:
      return '正在发布…';
  }
});
const dirty = computed(
  () => savedSnapshot.value !== null && localSnapshot() !== savedSnapshot.value,
);

const conditionFieldChoices = computed(() => conditionFieldOptions(fieldRows.value));
const scopeStatusLabel = computed(() => {
  switch (scope.value?.status) {
    case 'ACTIVE':
      return '已启用';
    case 'RETIRED':
      return '已退役';
    default:
      return '草稿';
  }
});


onMounted(async () => {
  await Promise.all([loadTemplates(), loadBindingOptions()]);
});
onBeforeUnmount(() => { if (autosaveTimer) clearTimeout(autosaveTimer); });
onBeforeRouteLeave(async () => { await flushDraft(); return !dirty.value; });

async function loadTemplates(preferredScopeId?: number) {
  loading.value = true;
  try {
    templates.value = await collectFormsApi.getFormTemplates();
    const target = preferredScopeId ?? allScopes.value[0]?.id ?? null;
    if (target) {
      await selectScope(target);
    } else {
      detail.value = null;
      selectedScopeId.value = null;
    }
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '表单模板加载失败'), '表单模板加载失败'));
  } finally {
    loading.value = false;
  }
}

async function refreshTemplatesList() {
  try {
    templates.value = await collectFormsApi.getFormTemplates();
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '表单模板加载失败'), '表单模板加载失败'));
  }
}

/** 只刷新范围元数据与版本信息，不覆盖本地草稿输入。 */
function applyScopeMeta(fresh: FormAdminScopeDetail) {
  if (!detail.value) {
    return;
  }
  detail.value = {
    ...detail.value,
    scope: fresh.scope,
    versions: fresh.versions,
    recordCount: fresh.recordCount,
    instanceCount: fresh.instanceCount,
    latestPublication: fresh.latestPublication,
  };
}

/** 用服务器详情重建表单；调用前必须已确认没有更晚的输入或范围切换。 */
function applyScopeDetail(fresh: FormAdminScopeDetail) {
  detail.value = fresh;
  const name = fresh.draft?.versionEvidence?.scopeDisplayName;
  if (typeof name === 'string' && name.trim()) rangeNames.value[fresh.scope.id] = name.trim();
  preview.value = fresh.preview?.targetVersionId === fresh.scope.currentVersionId ? null : fresh.preview;
  previewScopeId.value = fresh.preview ? fresh.scope.id : null;
  publication.value = fresh.latestPublication;
  hydrateDraft(fresh.draft);
  initialDraft.value = fresh.draft ? clonePlain(fresh.draft) : null;
  initialSpec.value = fresh.draft ? comparisonSpec(clonePlain(fresh.draft.fieldSpec), preview.value) : null;
  removedFields.value = [];
  saveError.value = '';
  markSaved();
}

/** 异步结果归属：发起请求时的范围与加载代次，回写前必须复核。 */
interface AsyncContext {
  scopeId: number;
  epoch: number;
}

function contextFor(scopeId: number): AsyncContext {
  return { scopeId, epoch: scopeLoadEpoch };
}

function isCurrentContext(context: AsyncContext): boolean {
  return (
    context.epoch === scopeLoadEpoch
    && selectedScopeId.value === context.scopeId
    && scope.value?.id === context.scopeId
    && detail.value != null
  );
}

type RefreshOutcome = 'applied' | 'kept' | 'stale';

/** 刷新范围详情；输入或范围在请求期间变化时只更新元数据，绝不覆盖本地编辑。 */
async function refreshScopePreservingInput(
  scopeId: number,
  baselineSnapshot: string,
  epoch: number,
  keepLocal: boolean,
): Promise<RefreshOutcome> {
  const [list, fresh] = await Promise.all([
    collectFormsApi.getFormTemplates(),
    collectFormsApi.getFormScope(scopeId),
  ]);
  templates.value = list;
  if (epoch !== scopeLoadEpoch || selectedScopeId.value !== scopeId || !detail.value) {
    return 'stale';
  }
  if (keepLocal || localSnapshot() !== baselineSnapshot) {
    applyScopeMeta(fresh);
    return 'kept';
  }
  applyScopeDetail(fresh);
  return 'applied';
}

async function loadBindingOptions() {
  try {
    bindingOptions.value = await collectFormsApi.getFormBindingOptions();
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '来源配置加载失败'), '来源配置加载失败'));
  }
}

async function selectScope(scopeId: number) {
  const epoch = ++scopeLoadEpoch;
  selectedScopeId.value = scopeId;
  publishConfirmVisible.value = false;
  publishTarget.value = null;
  previewScopeId.value = null;
  previewing.value = false;
  try {
    const fresh = await collectFormsApi.getFormScope(scopeId);
    if (epoch !== scopeLoadEpoch || selectedScopeId.value !== scopeId) {
      return;
    }
    applyScopeDetail(fresh);
    conflictScopeId.value = null;
    conflictDraft.value = null;
    conflictVisible.value = false;
    await loadMilestones();
  } catch (error) {
    if (epoch === scopeLoadEpoch) {
      ElMessage.error(readableError(getErrorMessage(error, '表单范围加载失败'), '表单范围加载失败'));
    }
  }
}

async function switchScope(scopeId: number) {
  if (scopeId === selectedScopeId.value) {
    return;
  }
  await flushDraft();
  if (dirty.value) return;
  await selectScope(scopeId);
}

function hydrateDraft(draft: FormAdminDraftView | null) {
  specBase.value = draft?.fieldSpec ?? null;
  fieldRows.value = specBase.value ? fieldRowsFromSpec(specBase.value) : [];
  bindingSourceId.value = draft?.binding?.sourceConfigId ?? null;
  bindingProjects.value = (draft?.binding?.projectIds ?? []).join(', ');
  bindingVersionProject.value = draft?.binding?.versionProjectId ?? null;
  versionLabel.value = draft?.versionLabel ?? '';
  conditionRows.value = specBase.value
    ? conditionRowsFromSpec(specBase.value, fieldRows.value)
    : [];
  hydrateEvidence(draft?.versionEvidence);
}

function hydrateEvidence(evidence: Record<string, unknown> | null | undefined) {
  evidenceDraft.value = { ...(evidence ?? {}) };
  evidenceNote.value = String(evidence?.note ?? '');
  selectedMilestoneId.value =
    typeof evidence?.milestoneId === 'number' ? evidence.milestoneId : null;
}

async function loadMilestones() {
  if (!bindingSourceId.value || !bindingVersionProject.value) {
    milestones.value = null;
    return;
  }
  try {
    milestones.value = await collectFormsApi.getFormMilestones(
      bindingSourceId.value,
      bindingVersionProject.value,
    );
  } catch (error) {
    milestones.value = { available: false, items: [] };
    ElMessage.warning(readableError(getErrorMessage(error, '版本里程碑加载失败'), '版本里程碑加载失败'));
  }
}

function updateRow(index: number, patch: Partial<FieldRow>) {
  const next = [...fieldRows.value];
  next[index] = { ...next[index], ...patch };
  fieldRows.value = next;
}

function changeControl(index: number, control: string) {
  const next = [...fieldRows.value];
  next[index] = changeRowControl(next[index], control);
  fieldRows.value = next;
}

function controlOptionsFor(row: FieldRow) {
  if (row.isNew) {
    return CONTROL_OPTIONS;
  }
  const allowed = controlsForType(row.type);
  const options = CONTROL_OPTIONS.filter((option) => allowed.includes(option.value));
  if (row.control && !options.some((option) => option.value === row.control)) {
    return [
      ...options,
      {
        value: row.control,
        label: controlLabel(row.control),
        type: row.type,
      },
    ] as typeof CONTROL_OPTIONS[number][];
  }
  return options;
}

function addField() {
  fieldRows.value = [...fieldRows.value, createFieldRow(fieldRows.value)];
}

async function removeField(index: number) {
  const row = fieldRows.value[index];
  const existing = Boolean(scope.value?.currentVersionId)
    && !changes.value.some(change => change.name === row.name && change.kind === 'added');
  if (existing && !(await confirmAction(`删除「${row.label}」后，发布的表单将不再显示此字段。`, '删除字段'))) return;
  if (existing) removedFields.value.push({ row: clonePlain(row), index, conditions: conditionRows.value.filter(condition => condition.target === row.name || condition.triggerField === row.name) });
  conditionRows.value = conditionRows.value.filter(condition => condition.target !== row.name && condition.triggerField !== row.name);
  fieldRows.value = fieldRows.value.filter((_, rowIndex) => rowIndex !== index);
}

function moveField(index: number, delta: number) {
  fieldRows.value = moveFieldRow(fieldRows.value, index, delta);
}


function addOptionRow() {
  if (optionsRowIndex.value === null) {
    return;
  }
  fieldRows.value = addOption(fieldRows.value, optionsRowIndex.value);
}

function removeOptionRow(optionIndex: number) {
  if (optionsRowIndex.value === null) return;
  fieldRows.value = removeOption(fieldRows.value, optionsRowIndex.value, optionIndex);
}

function updateOptionLabel(optionIndex: number, label: string) {
  if (optionsRowIndex.value === null) {
    return;
  }
  fieldRows.value = updateOption(fieldRows.value, optionsRowIndex.value, optionIndex, label);
}

function parseProjectIds(): number[] {
  return bindingProjects.value
    .split(/[,\s]+/)
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isInteger(value) && value > 0);
}


function conditionChoices(row: ConditionRow) {
  return specBase.value ? conditionValueOptions(row, buildSpec(), fieldRows.value) : [];
}

function conditionOperatorChoices(row: ConditionRow) {
  return specBase.value ? conditionOperators(row, buildSpec()) : [];
}

function conditionDescription(row: ConditionRow): string {
  return specBase.value ? describeConditionRow(row, buildSpec(), fieldRows.value) : '';
}

function updateCondition(index: number, patch: Partial<ConditionRow>) {
  const next = [...conditionRows.value];
  next[index] = { ...next[index], ...patch };
  conditionRows.value = next;
}

function changeConditionTrigger(index: number, field: string) {
  const row = conditionRows.value[index];
  if (!row) {
    return;
  }
  const next = { ...row, triggerField: field, values: [], conflictValues: [] };
  const operators = specBase.value ? conditionOperators(next, buildSpec()) : [];
  next.operator = operators[0]?.value ?? 'equals';
  const choices = specBase.value ? conditionValueOptions(next, buildSpec(), fieldRows.value) : [];
  if (choices.length) {
    next.values = [choices[0].value];
  }
  if (row.kind === 'mutual' && choices.length > 1) {
    next.conflictValues = [choices[1].value];
  }
  updateCondition(index, next);
}

function changeConditionOperator(index: number, operator: ConditionRow['operator']) {
  const row = conditionRows.value[index];
  if (!row) {
    return;
  }
  const next = { ...row, operator, values: [] };
  const choices = specBase.value ? conditionValueOptions(next, buildSpec(), fieldRows.value) : [];
  if (choices.length) {
    next.values = [choices[0].value];
  }
  updateCondition(index, next);
}


function removeCondition(index: number) {
  const row = conditionRows.value[index];
  if (!row) {
    return;
  }
  conditionRows.value = conditionRows.value.filter((_, item) => item !== index);
}



function buildSpec(): FormTemplateSpecView {
  if (!specBase.value) {
    throw new Error('缺少字段规格');
  }
  return applyConditionRows(
    applyFieldRows(specBase.value, fieldRows.value),
    conditionRows.value,
  );
}

function buildEvidence(): Record<string, unknown> | null {
  const evidence: Record<string, unknown> = { ...evidenceDraft.value };
  if (evidenceNote.value.trim()) {
    evidence.note = evidenceNote.value.trim();
  }
  const projectId = bindingVersionProject.value;
  if (selectedMilestoneId.value !== null) {
    const milestone = milestones.value?.items.find(
      (item) => item.id === selectedMilestoneId.value,
    );
    evidence.milestoneId = selectedMilestoneId.value;
    evidence.milestoneTitle = milestone?.title ?? evidence.milestoneTitle;
    evidence.projectId = projectId;
  }
  return Object.keys(evidence).length ? evidence : null;
}

function localSnapshot(): string {
  let spec: string;
  try {
    spec = JSON.stringify(buildSpec());
  } catch {
    spec = 'invalid';
  }
  return JSON.stringify({
    spec,
    sourceConfigId: bindingSourceId.value,
    projects: bindingProjects.value,
    versionProjectId: bindingVersionProject.value,
    versionLabel: versionLabel.value,
    evidence: buildEvidence(),
  });
}

function markSaved() {
  savedSnapshot.value = localSnapshot();
}

function isConflictError(error: unknown): boolean {
  return (isApiBizError(error) || error instanceof HttpRequestError) && error.code === 'A0409';
}

async function performSaveDraft() {
  if (!detail.value?.draft || !scope.value) {
    return;
  }
  const errors = validateFieldRows(fieldRows.value);
  if (errors.length) {
    saveError.value = readableError(errors[0], '请检查字段名称和选项');
    return;
  }
  const hasBinding =
    bindingSourceId.value !== null
    || bindingProjects.value.trim() !== ''
    || bindingVersionProject.value !== null;
  const projectIds = parseProjectIds();
  if (hasBinding && (!bindingSourceId.value || !bindingVersionProject.value || !projectIds.length)) {
    saveError.value = '请完成适用范围的项目和版本选择';
    return;
  }
  saving.value = true;
  const scopeId = scope.value.id;
  const requestEpoch = scopeLoadEpoch;
  const submittedSnapshot = localSnapshot();
  try {
    const draft = await collectFormsApi.saveFormDraft(scopeId, {
      expectedDraftRevision: detail.value.draft.revision,
      fieldSpec: buildSpec(),
      binding: hasBinding
        ? {
            sourceConfigId: bindingSourceId.value as number,
            projectIds,
            versionProjectId: bindingVersionProject.value as number,
          }
        : undefined,
      versionLabel: versionLabel.value.trim() || null,
      versionEvidence: buildEvidence(),
    });
    if (scope.value?.id !== scopeId || scopeLoadEpoch !== requestEpoch || !detail.value) {
      await refreshTemplatesList();
      return;
    }
    specBase.value = draft?.fieldSpec ?? buildSpec();
    detail.value = {
      ...detail.value,
      draft,
      scope: { ...detail.value.scope, draftRevision: draft.revision },
    };
    preview.value = null;
    pendingRequest.value = null;
    if (localSnapshot() === submittedSnapshot) {
      fieldRows.value = fieldRowsFromSpec(specBase.value);
      markSaved();
      saveError.value = '';
    } else {
      // 服务端已接受提交时的内容；等待期间的新输入保留为未保存修改，不能覆盖。
      savedSnapshot.value = submittedSnapshot;
      saveError.value = '';
    }
    await refreshTemplatesList();
  } catch (error) {
    if (scope.value?.id !== scopeId || scopeLoadEpoch !== requestEpoch || !detail.value) return;
    if (isConflictError(error)) {
      if (scope.value?.id !== scopeId || scopeLoadEpoch !== requestEpoch || !detail.value) {
        await refreshTemplatesList();
        return;
      }
      const fresh = await collectFormsApi.getFormScope(scopeId);
      // 冲突稿二次查询也可能迟到；查询期间切换或刷新过范围时丢弃，避免串范围。
      if (scope.value?.id !== scopeId || scopeLoadEpoch !== requestEpoch || !detail.value) {
        await refreshTemplatesList();
        return;
      }
      conflictScopeId.value = scopeId;
      conflictDraft.value = fresh.draft;
      conflictVisible.value = true;
      ElMessage.warning('此表单已被其他管理员修改');
    } else {
      saveError.value = `修改暂未保存：${readableError(getErrorMessage(error, '连接暂时中断，请重试'), '请检查填写内容或稍后重试')}`;
    }
  } finally {
    saving.value = false;
  }
}

function applyServerDraft() {
  if (
    !conflictDraft.value
    || !detail.value
    || conflictScopeId.value !== scope.value?.id
  ) {
    conflictVisible.value = false;
    conflictDraft.value = null;
    conflictScopeId.value = null;
    return;
  }
  hydrateDraft(conflictDraft.value);
  detail.value = {
    ...detail.value,
    draft: conflictDraft.value,
    scope: { ...detail.value.scope, draftRevision: conflictDraft.value.revision },
  };
  preview.value = null;
  pendingRequest.value = null;
  markSaved();
  conflictVisible.value = false;
  conflictDraft.value = null;
  conflictScopeId.value = null;
  ElMessage.success('已使用最新内容');
}

function keepLocalWithLatestRevision() {
  if (
    !conflictDraft.value
    || !detail.value
    || conflictScopeId.value !== scope.value?.id
  ) {
    conflictVisible.value = false;
    conflictDraft.value = null;
    conflictScopeId.value = null;
    return;
  }
  detail.value = {
    ...detail.value,
    draft: { ...detail.value.draft!, revision: conflictDraft.value.revision },
    scope: { ...detail.value.scope, draftRevision: conflictDraft.value.revision },
  };
  preview.value = null;
  pendingRequest.value = null;
  conflictVisible.value = false;
  conflictDraft.value = null;
  conflictScopeId.value = null;
  ElMessage.warning('已保留你的修改');
}

async function previewScope(force = false) {
  if (!scope.value || !detail.value?.draft) {
    return null;
  }
  if (!(await ensureSaved())) {
    return null;
  }
  if (
    !force
    && preview.value
    && preview.value.draftRevision === detail.value.draft.revision
    && previewScopeId.value === scope.value.id
  ) {
    return preview.value;
  }
  const context = contextFor(scope.value.id);
  const draftRevision = detail.value.draft.revision;
  const submittedSnapshot = localSnapshot();
  previewing.value = true;
  try {
    const fresh = await collectFormsApi.previewFormScope(context.scopeId, draftRevision);
    // 迟到预览只能属于发起时的范围与会话；范围切换、草稿修订或输入变化均丢弃。
    if (
      !isCurrentContext(context)
      || detail.value?.draft?.revision !== draftRevision
      || localSnapshot() !== submittedSnapshot
    ) {
      return null;
    }
    preview.value = fresh;
    previewScopeId.value = context.scopeId;
    return fresh;
  } catch (error) {
    if (isCurrentContext(context)) {
      ElMessage.error(readableError(getErrorMessage(error, '预览失败'), '预览失败'));
    }
    return null;
  } finally {
    previewing.value = false;
  }
}

async function ensureSaved(): Promise<boolean> {
  if (dirty.value) {
    ElMessage.warning('修改正在保存，请稍后发布');
    return false;
  }
  if (!detail.value?.draft) {
    ElMessage.warning('表单正在加载');
    return false;
  }
  return true;
}

async function createPublication() {
  await flushDraft();
  if (dirty.value || !scope.value || !detail.value?.draft) return;
  if (!bindingBound.value || !versionLabel.value) { rangeVisible.value = true; return; }
  const context = contextFor(scope.value.id);
  const target = await previewScope();
  if (!target || !isCurrentContext(context)) return;
  if (target.blockingErrors.length) { saveError.value = readableError(target.blockingErrors[0], '请检查字段或适用范围后再发布'); return; }
  publishTarget.value = { context, target, snapshot: localSnapshot() };
  publishConfirmVisible.value = true;
}

async function confirmPublication() {
  const intent = publishTarget.value;
  if (!intent || !isCurrentContext(intent.context) || intent.snapshot !== localSnapshot()) {
    publishConfirmVisible.value = false;
    return;
  }
  publishConfirmVisible.value = false;
  const expectedCurrentVersionId = scope.value!.currentVersionId;
  if (!pendingRequest.value || pendingRequest.value.draftRevision !== intent.target.draftRevision
    || pendingRequest.value.previewId !== intent.target.id
    || pendingRequest.value.expectedCurrentVersionId !== expectedCurrentVersionId) {
    pendingRequest.value = { draftRevision: intent.target.draftRevision, previewId: intent.target.id, expectedCurrentVersionId, key: crypto.randomUUID() };
  }
  publishing.value = true;
  try {
    const created = await collectFormsApi.createFormPublication(intent.context.scopeId, {
      draftRevision: pendingRequest.value.draftRevision, previewId: pendingRequest.value.previewId,
      expectedCurrentVersionId, requestKey: pendingRequest.value.key,
    });
    if (!isCurrentContext(intent.context) || created.scopeId !== intent.context.scopeId) return;
    publication.value = created;
    await pollPublication(created.id);
  } catch (error) {
    if (isCurrentContext(intent.context)) saveError.value = readableError(getErrorMessage(error, '发布未完成'), '暂时未能完成发布，请重试');
  } finally { publishing.value = false; }
}

const PENDING_PUBLICATION_STAGES = [
  'PREPARED',
  'COPYING',
  'VERIFIED',
  'VALIDATING',
  'BUILDING',
  'SWITCHING',
];

async function pollPublication(publicationId: number) {
  const scopeId = scope.value?.id;
  if (!scopeId) {
    return;
  }
  const context = contextFor(scopeId);
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const fresh = await collectFormsApi.getFormPublication(publicationId);
    // 轮询结果只属于发起时的范围与会话；切换后停止轮询且不展示旧任务。
    if (!isCurrentContext(context) || fresh.scopeId !== context.scopeId) {
      return;
    }
    publication.value = fresh;
    if (!PENDING_PUBLICATION_STAGES.includes(fresh.stage)) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  if (!isCurrentContext(context)) {
    return;
  }
  if (publication.value?.stage === 'PUBLISHED') {
    pendingRequest.value = null;
    saveError.value = '';
    ElMessage.success('已发布');
    const epoch = ++scopeLoadEpoch;
    const baselineSnapshot = localSnapshot();
    try {
      const outcome = await refreshScopePreservingInput(
        scopeId,
        baselineSnapshot,
        epoch,
        dirty.value,
      );
      if (outcome === 'kept') {
        // 发布期间或最终刷新期间继续输入时，迟到详情不得覆盖本地编辑。
        ElMessage.warning('版本已发布；本地未保存修改仍保留，请再次保存');
      }
    } catch (error) {
      if (epoch === scopeLoadEpoch) {
        ElMessage.error(readableError(getErrorMessage(error, '表单范围刷新失败'), '表单范围刷新失败'));
      }
    }
    return;
  }
  if (publication.value?.stage === 'FAILED') {
    saveError.value = '';
  }
}

async function recoverPublication() {
  if (!publication.value || !scope.value) return;
  const context = contextFor(scope.value.id);
  const taskId = publication.value.id;
  if (!isCurrentContext(context) || publication.value.scopeId !== context.scopeId) return;
  publishing.value = true;
  try {
    const fresh = await collectFormsApi.recoverFormPublication(taskId);
    if (!isCurrentContext(context) || fresh.scopeId !== context.scopeId) return;
    publication.value = fresh;
    await pollPublication(fresh.id);
  } catch (error) {
    if (isCurrentContext(context)) saveError.value = readableError(getErrorMessage(error, '重试未完成'), '暂时无法重试，请稍后再试');
  } finally { publishing.value = false; }
}


async function retireScope() {
  if (!scope.value?.currentVersionId) {
    return;
  }
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
  const confirmed = await confirmAction(
    '退役后将停止填写，已填写的记录仍可查看。',
    '退役确认',
  );
  if (!confirmed) {
    return;
  }
  try {
    detail.value = await collectFormsApi.retireFormScope(
      scope.value.id,
      scope.value.currentVersionId,
    );
    ElMessage.success('表单已退役');
    await loadTemplates(scope.value.id);
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '范围退役失败'), '范围退役失败'));
  }
}

async function deleteScope() {
  if (!scope.value) {
    return;
  }
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
  try {
    await collectFormsApi.deleteFormScope(scope.value.id);
    ElMessage.success('范围已删除');
    selectedScopeId.value = null;
    await loadTemplates();
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '范围删除失败'), '范围删除失败'));
  }
}

async function createScope() {
  if (!createScopeKey.value.trim()) {
    ElMessage.warning('请填写适用范围名称');
    return;
  }
  try {
    const created = await collectFormsApi.createFormScope({
      templateKey: createTemplateKey.value,
      scopeKey: `form-${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`,
    });
    createVisible.value = false;
    const displayName = createScopeKey.value.trim();
    createScopeKey.value = '';
    ElMessage.success('适用范围已创建');
    await loadTemplates(created.scope.id);
    evidenceDraft.value = { ...evidenceDraft.value, scopeDisplayName: displayName };
    rangeNames.value[created.scope.id] = displayName;
    rangeVisible.value = true;
  } catch (error) {
    ElMessage.error(readableError(getErrorMessage(error, '范围创建失败'), '范围创建失败'));
  }
}

function applyMilestone(milestoneId: number) {
  const milestone = milestones.value?.items.find((item) => item.id === milestoneId);
  if (!milestone) {
    return;
  }
  selectedMilestoneId.value = milestone.id;
  if (!versionLabel.value.trim()) {
    versionLabel.value = milestone.title;
  }
  evidenceDraft.value = {
    ...evidenceDraft.value,
    milestoneId: milestone.id,
    milestoneTitle: milestone.title,
    projectId: bindingVersionProject.value,
    verifiedAt: new Date().toISOString(),
  };
}



async function confirmAction(message: string, title: string): Promise<boolean> {
  const result = await ElMessageBox.confirm(message, title, {
    type: 'warning',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  }).catch(() => false);
  return Boolean(result);
}

async function confirmDiscardIfDirty(): Promise<boolean> { await flushDraft(); return !dirty.value; }


function clonePlain<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T; }

const initialDraft = ref<FormAdminDraftView | null>(null);
const initialSpec = ref<FormTemplateSpecView | null>(null);
const removedFields = ref<Array<{ row: FieldRow; index: number; conditions: ConditionRow[] }>>([]);
const expanded = ref<string | null>(null);
const rangeVisible = ref(false);
const historyVisible = ref(false);
const saveError = ref('');
const rangeNames = ref<Record<number, string>>({});
const publishConfirmVisible = ref(false);
const publishTarget = ref<{ context: AsyncContext; target: FormAdminPreviewView; snapshot: string } | null>(null);
const dragIndex = ref<number | null>(null);
let autosaveTimer: ReturnType<typeof setTimeout> | undefined;
let saveInFlight: Promise<void> | null = null;
const changes = computed(() => { try { return fieldChanges(initialSpec.value, buildSpec()); } catch { return []; } });
const pendingCount = computed(() => new Set(changes.value.map(change => change.name || change.key)).size);
const headerFailure = computed(() => publication.value?.stage === 'FAILED' ? publicationFailure(publication.value.lastError) : '');
const displayTitle = computed(() => formTitle(scope.value?.templateKey ?? ''));
const scopeDisplay = computed(() => scope.value ? rangeName(scope.value) : '');
const editStatus = computed(() => saving.value || dirty.value ? '正在保存' : '已保存');

function readableError(message: string, fallback: string): string { return businessError(message, specBase.value, fallback); }
function rangeName(item: FormAdminScopeSummary): string {
  if (item.id === scope.value?.id && typeof evidenceDraft.value.scopeDisplayName === 'string') return evidenceDraft.value.scopeDisplayName;
  return rangeNames.value[item.id] || (item.currentVersionLabel ? `${item.currentVersionLabel} 版本` : '尚未设置适用范围');
}
function fieldState(name: string): string {
  const rows = changes.value.filter(change => change.name === name);
  return rows.some(row => row.kind === 'added') ? '新增' : rows.length ? '已改' : '';
}
function rowsForField(name: string) {
  return conditionRows.value.map((row, index) => ({ row, index })).filter(({ row }) => row.kind === 'mutual' ? row.triggerField === name : row.target === name);
}
function addFieldCondition(name: string, kind: ConditionRowKind) {
  const row = createConditionRow(fieldRows.value, buildSpec(), kind);
  if (kind === 'required') row.target = name;
  else { row.triggerField = name; row.operator = 'contains'; const options = conditionChoices(row); row.values = options[0] ? [options[0].value] : []; row.conflictValues = options[1] ? [options[1].value] : []; }
  conditionRows.value = [...conditionRows.value, row];
}
function startDrag(event: DragEvent, index: number) {
  dragIndex.value = index;
  event.dataTransfer?.setData('text/plain', String(index));
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
}
function dropField(index: number) {
  if (dragIndex.value === null) return;
  const next = [...fieldRows.value]; const [row] = next.splice(dragIndex.value, 1); next.splice(index, 0, row); fieldRows.value = next; dragIndex.value = null;
}
function undoRemoval(name: string) {
  const entry = removedFields.value.find(item => item.row.name === name); if (!entry) return;
  const next = [...fieldRows.value]; next.splice(Math.min(entry.index, next.length), 0, entry.row); fieldRows.value = next;
  conditionRows.value = [...conditionRows.value, ...entry.conditions]; removedFields.value = removedFields.value.filter(item => item !== entry);
}
function saveDraft(): Promise<void> {
  if (saveInFlight) return saveInFlight;
  saveInFlight = performSaveDraft().finally(() => { saveInFlight = null; }); return saveInFlight;
}
async function flushDraft() {
  if (autosaveTimer) clearTimeout(autosaveTimer);
  if (saveInFlight) await saveInFlight;
  if (dirty.value && !conflictVisible.value) await saveDraft();
}
function discardChanges() {
  if (!initialDraft.value) return;
  hydrateDraft(clonePlain(initialDraft.value)); removedFields.value = []; preview.value = null; pendingRequest.value = null; saveError.value = '';
}
function openCreate(key: string) { createTemplateKey.value = key; createVisible.value = true; }
async function finishRange() { await flushDraft(); if (!dirty.value) { rangeVisible.value = false; if (scope.value && typeof evidenceDraft.value.scopeDisplayName === 'string') rangeNames.value[scope.value.id] = evidenceDraft.value.scopeDisplayName; } }
function formattedTime(value: string | null): string { return value ? new Date(value).toLocaleDateString('zh-CN') : '尚未发布'; }

function scheduleAutosave() {
  if (autosaveTimer) clearTimeout(autosaveTimer);
  if (!dirty.value || loading.value || saving.value || conflictVisible.value || publishing.value || saveError.value || scope.value?.status === 'RETIRED') return;
  autosaveTimer = setTimeout(async () => { await saveDraft(); if (!dirty.value && bindingBound.value && versionLabel.value) await previewScope(true); }, 650);
}
watch(localSnapshot, () => { saveError.value = ''; scheduleAutosave(); });
watch(saving, active => { if (!active) scheduleAutosave(); });

</script>
<template>
  <section class="form-admin" aria-label="表单管理">
    <h1 class="page-title">表单管理</h1>
    <div class="admin-layout">
      <nav class="form-list" aria-label="选择表单">
        <section v-for="template in templates" :key="template.templateKey" class="form-group">
          <h2>{{ formTitle(template.templateKey) }}</h2>
          <button v-for="item in template.scopes" :key="item.id" class="scope-item" :class="{ selected: item.id === selectedScopeId }" @click="switchScope(item.id)">
            <span class="scope-name">{{ rangeName(item) }}</span>
            <span class="scope-state"><i :class="{ active: item.status === 'ACTIVE', changed: item.id === selectedScopeId && pendingCount > 0 }" />{{ item.id === selectedScopeId && pendingCount ? '有未发布修改' : item.status === 'ACTIVE' ? '已启用' : item.status === 'RETIRED' ? '已退役' : '草稿' }}</span>
          </button>
          <p v-if="!template.scopes.length" class="small-empty">尚无适用范围</p>
          <button class="new-range" @click="openCreate(template.templateKey)">＋ 新建范围</button>
        </section>
      </nav>

      <main v-if="detail && scope && scope.id === selectedScopeId" class="workspace">
        <header class="workspace-header">
          <div class="workspace-heading">
            <div class="heading-line"><h2>{{ displayTitle }}</h2><span class="status-label">{{ scopeStatusLabel }}</span></div>
            <button class="range-summary" @click="rangeVisible = true">{{ scopeDisplay }}<el-icon><ArrowDown /></el-icon></button>
            <span class="current-version">{{ scope.currentVersionLabel ? `当前版本 ${scope.currentVersionLabel}` : '尚未发布' }}</span>
          </div>
          <div class="header-actions">
            <el-button text :icon="Clock" @click="historyVisible = true">历史版本</el-button>
            <el-dropdown trigger="click" @command="command => command === 'retire' ? retireScope() : deleteScope()">
              <el-button text :icon="MoreFilled" aria-label="更多操作" />
              <template #dropdown><el-dropdown-menu><el-dropdown-item v-if="canRetire" command="retire">退役表单</el-dropdown-item><el-dropdown-item v-if="canDelete" command="delete">删除空白范围</el-dropdown-item><el-dropdown-item v-if="!canRetire && !canDelete" disabled>无其他操作</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
            <el-button :disabled="!pendingCount && !dirty" @click="discardChanges">放弃修改</el-button>
            <el-button type="primary" :loading="publishing || previewing" :disabled="publicationActive || scope.status === 'RETIRED'" @click="createPublication">发布</el-button>
          </div>
        </header>

        <div v-if="headerFailure" class="notice notice-warning" role="status"><span><strong>上次发布失败：</strong>{{ headerFailure }}</span><el-button link :loading="publishing" @click="recoverPublication">重试</el-button></div>
        <div v-else-if="saveError" class="notice notice-warning" role="status"><span>{{ saveError }}</span><el-button link @click="saveDraft">重试保存</el-button></div>
        <div v-else-if="pendingCount" class="notice notice-changes" role="status"><span><strong>{{ pendingCount }}</strong> 项修改未发布</span><span class="save-status">{{ editStatus }}</span></div>
        <div v-else-if="publicationActive" class="notice notice-changes" role="status">{{ publicationStageText }}</div>

        <div class="editor-grid">
          <section class="field-editor" aria-label="编辑字段" :inert="scope.status === 'RETIRED'">
            <div class="section-heading"><h3>表单字段 <span>{{ fieldRows.length }}</span></h3><el-button :icon="Plus" @click="addField">添加字段</el-button></div>
            <article v-for="(row, index) in fieldRows" :key="row.name" class="field-card" :class="{ modified: fieldState(row.name), 'is-added': fieldState(row.name) === '新增', expanded: expanded === row.name }" @dragover.prevent @drop.prevent="dropField(index)">
              <div class="field-row">
                <button class="drag-handle" draggable="true" :aria-label="`拖动${row.label || '新字段'}排序`" @dragstart="startDrag($event, index)" @dragend="dragIndex = null" @keydown.alt.up.prevent="moveField(index, -1)" @keydown.alt.down.prevent="moveField(index, 1)"><el-icon><Rank /></el-icon></button>
                <div class="field-name"><label class="sr-only" :for="`field-label-${index}`">字段名称</label><el-input :id="`field-label-${index}`" v-model="row.label" maxlength="64" placeholder="字段名称" :aria-label="`第${index + 1}个字段名称`" @input="updateRow(index, { label: row.label })" /><span v-if="fieldState(row.name)" class="change-badge">{{ fieldState(row.name) }}</span></div>
                <el-select :model-value="row.control" :aria-label="`${row.label}填写方式`" @change="changeControl(index, $event)"><el-option v-for="option in controlOptionsFor(row)" :key="option.value" :label="option.label" :value="option.value" /></el-select>
                <label class="required-toggle"><el-switch v-model="row.required" size="small" :aria-label="`${row.label}必填`" /><span>必填</span></label>
                <div class="field-actions"><button :aria-label="`${expanded === row.name ? '收起' : '展开'}${row.label}`" :aria-expanded="expanded === row.name" @click="expanded = expanded === row.name ? null : row.name"><el-icon :class="{ rotated: expanded === row.name }"><ArrowDown /></el-icon></button><button :aria-label="`删除${row.label}`" @click="removeField(index)"><el-icon><Delete /></el-icon></button></div>
              </div>
              <div v-if="expanded === row.name" class="field-details">
                <section v-if="row.type === 'enum' || row.type === 'enum-array'" class="detail-section">
                  <h4>选项</h4>
                  <p v-if="row.dictionary" class="field-hint">使用标准原因词典</p>
                  <div v-for="(option, optionIndex) in row.options" v-else :key="option.value" class="option-row"><span>{{ optionIndex + 1 }}</span><el-input :model-value="option.label" :aria-label="`${row.label}选项${optionIndex + 1}`" @input="optionsRowIndex = index; updateOptionLabel(optionIndex, $event)" /><el-button text :icon="Close" :aria-label="`移除选项${option.label}`" @click="optionsRowIndex = index; removeOptionRow(optionIndex)" /></div>
                  <el-button v-if="!row.dictionary" text :icon="Plus" @click="optionsRowIndex = index; addOptionRow()">添加选项</el-button>
                </section>
                <section class="detail-section">
                  <div class="detail-heading"><h4>填写条件</h4><div><el-button text size="small" @click="addFieldCondition(row.name, 'required')">添加必填条件</el-button><el-button v-if="row.type === 'enum-array'" text size="small" @click="addFieldCondition(row.name, 'mutual')">添加互斥条件</el-button></div></div>
                  <div v-for="entry in rowsForField(row.name)" :key="entry.row.key" class="condition-editor">
                    <span class="condition-description">{{ conditionDescription(entry.row) }}</span>
                    <div class="condition-controls"><el-select :model-value="entry.row.triggerField" aria-label="条件字段" @change="changeConditionTrigger(entry.index, $event)"><el-option v-for="option in conditionFieldChoices" :key="option.value" :value="option.value" :label="option.label" /></el-select><el-select v-if="entry.row.kind === 'required'" :model-value="entry.row.operator" aria-label="判断方式" @change="changeConditionOperator(entry.index, $event)"><el-option v-for="option in conditionOperatorChoices(entry.row)" :key="option.value" :label="option.label" :value="option.value" /></el-select><span v-else>选择</span><el-select v-if="conditionChoices(entry.row).length" :model-value="conditionChoices(entry.row).some(option => option.value === entry.row.values[0]) ? entry.row.values[0] : ''" placeholder="选择条件值" aria-label="条件选项" @change="updateCondition(entry.index, { values: $event ? [$event] : [] })"><el-option v-for="option in conditionChoices(entry.row)" :key="option.value" :label="option.label" :value="option.value" /></el-select><el-input v-else :model-value="entry.row.values[0] ?? ''" aria-label="条件值" placeholder="填写条件值" @input="updateCondition(entry.index, { values: [$event] })" /><el-button text :icon="Delete" aria-label="移除条件" @click="removeCondition(entry.index)" /></div>
                    <el-select v-if="entry.row.kind === 'mutual'" :model-value="entry.row.conflictValues.filter(value => conditionChoices(entry.row).some(option => option.value === value))" multiple aria-label="不能同时选择" @change="updateCondition(entry.index, { conflictValues: $event })"><el-option v-for="option in conditionChoices(entry.row)" :key="option.value" :label="option.label" :value="option.value" /></el-select>
                    <label v-else-if="canClearWhen(entry.row, buildSpec())" class="clear-condition"><el-switch :model-value="entry.row.clearWhenOff" size="small" @change="updateCondition(entry.index, { clearWhenOff: Boolean($event) })" /><span>条件不成立时清空</span></label>
                  </div>
                </section>
                <p v-if="row.maxLength" class="field-hint">最多填写 {{ row.maxLength }} 个字</p>
              </div>
            </article>
            <article v-for="entry in removedFields" :key="entry.row.name" class="field-card removed-card"><span>{{ entry.row.label }}</span><span class="change-badge">待删除</span><el-button text @click="undoRemoval(entry.row.name)">撤销删除</el-button></article>
            <div v-if="!fieldRows.length" class="empty-fields">尚无字段 <el-button text @click="addField">添加第一个字段</el-button></div>
          </section>

          <aside class="change-summary" aria-label="变更摘要">
            <div class="summary-heading"><h3>变更摘要</h3><span v-if="pendingCount">{{ pendingCount }} 项</span></div>
            <ul v-if="changes.length"><li v-for="change in changes" :key="change.key"><i :class="change.kind" /><span>{{ change.text }}</span></li></ul>
            <p v-else class="summary-empty">尚无修改</p>
            <p v-if="pendingCount && !saveError" class="summary-save">{{ editStatus }}</p>
          </aside>
        </div>
      </main>
      <main v-else class="workspace empty-workspace"><p>{{ loading ? '正在加载表单…' : '选择一个表单开始编辑' }}</p></main>
    </div>

    <el-drawer v-model="historyVisible" title="历史版本" size="420px"><ol class="version-list"><li v-for="version in detail?.versions ?? []" :key="version.id"><span class="version-dot" /><div><strong>{{ version.versionLabel || '早期版本' }}</strong><p>{{ formattedTime(version.publishedAt) }}<span v-if="version.id === scope?.currentVersionId"> · 当前版本</span></p></div></li></ol><p v-if="!detail?.versions.length" class="muted">尚未发布过版本</p></el-drawer>

    <el-drawer v-model="rangeVisible" title="适用范围" size="460px">
      <el-form label-position="top" class="range-form">
        <el-form-item label="范围名称"><el-input :model-value="String(evidenceDraft.scopeDisplayName ?? '')" placeholder="例如：CCProduct" @input="evidenceDraft = { ...evidenceDraft, scopeDisplayName: $event }" /></el-form-item>
        <el-form-item label="代码平台"><el-select v-model="bindingSourceId" placeholder="选择代码平台" :disabled="bindingBound" @change="loadMilestones"><el-option v-for="option in bindingOptions" :key="option.sourceConfigId" :label="option.name" :value="option.sourceConfigId" /></el-select></el-form-item>
        <el-form-item label="适用项目"><el-input v-model="bindingProjects" :disabled="bindingBound" placeholder="项目编号，多个项目用逗号分隔" @blur="loadMilestones" /></el-form-item>
        <el-form-item label="版本所属项目"><el-input-number v-model="bindingVersionProject" :disabled="bindingBound" :min="1" @change="loadMilestones" /></el-form-item>
        <el-form-item label="版本"><el-select v-if="milestones?.available" v-model="selectedMilestoneId" placeholder="选择项目版本" @change="applyMilestone"><el-option v-for="item in milestones.items" :key="item.id" :label="item.title" :value="item.id" /></el-select><el-input v-else v-model="versionLabel" placeholder="填写项目版本" /></el-form-item>
        <el-form-item label="发布说明"><el-input v-model="evidenceNote" type="textarea" :rows="3" maxlength="500" placeholder="本次调整的说明（选填）" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="finishRange">完成</el-button></template>
    </el-drawer>

    <el-dialog v-model="publishConfirmVisible" title="发布修改" width="min(560px, 90vw)" :close-on-click-modal="false"><ul class="confirm-changes"><li v-for="change in changes" :key="change.key">{{ change.text }}</li></ul><p v-if="!changes.length">将启用此表单供填写。</p><p class="history-effect">新版本从空结构开始；已有记录保留在原版本，不迁移、不改绑。</p><template #footer><el-button @click="publishConfirmVisible = false">继续编辑</el-button><el-button type="primary" @click="confirmPublication">确认发布</el-button></template></el-dialog>

    <el-dialog v-model="createVisible" title="新建适用范围" width="420px"><el-form label-position="top"><el-form-item label="表单"><el-select v-model="createTemplateKey"><el-option v-for="template in templates" :key="template.templateKey" :label="formTitle(template.templateKey)" :value="template.templateKey" /></el-select></el-form-item><el-form-item label="适用范围名称"><el-input v-model="createScopeKey" placeholder="例如：CCProduct" /></el-form-item></el-form><template #footer><el-button @click="createVisible = false">取消</el-button><el-button @click="createScope">创建</el-button></template></el-dialog>

    <el-drawer v-model="conflictVisible" title="其他管理员也修改了此表单" size="460px"><p>你的修改仍保留。选择使用哪份内容。</p><h3 class="conflict-heading">最新内容</h3><ul class="conflict-fields"><li v-for="field in conflictDraft?.fieldSpec.fields ?? []" :key="field.name">{{ field.label }} · {{ controlLabel(field.control) }}</li></ul><div class="conflict-actions"><el-button @click="keepLocalWithLatestRevision(); saveDraft()">保留我的修改</el-button><el-button @click="applyServerDraft">使用最新内容</el-button></div></el-drawer>
  </section>
</template>

<style scoped>
.form-admin { color: #24344a; min-width: 0; padding: 0 4px 40px; }
.page-title { font-size: 22px; font-weight: 650; margin: 0 0 26px; letter-spacing: -.02em; }
.admin-layout { display: grid; grid-template-columns: 184px minmax(0, 1fr); gap: 28px; align-items: start; }
.form-list { padding-top: 5px; position: sticky; top: 20px; }
.form-group + .form-group { margin-top: 28px; }
.form-group h2 { font-size: 14px; font-weight: 650; margin: 0 0 10px 12px; color: #3a4b62; }
.scope-item { width: 100%; text-align: left; border: 0; background: none; padding: 12px; border-radius: 8px; cursor: pointer; color: inherit; }
.scope-item:hover { background: #e9eff7; }
.scope-item.selected { background: #e6efff; color: #195fca; }
.scope-name { display: block; overflow-wrap: anywhere; line-height: 1.5; font-size: 13px; font-weight: 550; }
.scope-state { display: flex; align-items: center; gap: 6px; margin-top: 5px; color: #63748b; font-size: 11px; }
.scope-state i { width: 5px; height: 5px; border-radius: 50%; background: #8794a5; }
.scope-state i.active { background: #229477; }
.scope-state i.changed { background: #3578e6; }
.new-range { margin: 8px 0 0 12px; background: none; border: 0; color: #37679f; font-size: 12px; cursor: pointer; padding: 3px 0; }
.small-empty { margin: 9px 12px 4px; color: #758399; font-size: 12px; }
.workspace { min-width: 0; }
.workspace-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 24px; min-height: 68px; }
.workspace-heading { min-width: 0; }
.heading-line { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }
.heading-line h2 { margin: 0; font-size: 23px; font-weight: 650; letter-spacing: -.025em; }
.status-label { background: #e9f3ee; color: #3c7459; font-size: 11px; padding: 3px 7px; border-radius: 4px; }
.range-summary { display: inline-flex; align-items: center; gap: 7px; border: 0; padding: 0; background: none; color: #49617e; cursor: pointer; font-size: 13px; }
.range-summary:hover { color: #2166ce; }
.current-version { margin-left: 12px; font-size: 12px; color: #728098; }
.header-actions { display: flex; align-items: center; flex-shrink: 0; gap: 7px; }
.header-actions :deep(.el-button + .el-button) { margin-left: 0; }
.header-actions :deep(.el-button--primary) { min-width: 84px; font-weight: 600; background: #246bdb; border-color: #246bdb; }
.notice { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 44px; box-sizing: border-box; padding: 10px 15px; border-radius: 8px; margin-bottom: 22px; font-size: 13px; }
.notice-warning { color: #81570e; background: #fff4df; }
.notice-changes { color: #315e98; background: #eaf1fc; }
.notice strong { font-weight: 600; }
.notice :deep(.el-button) { color: #246bdb; font-weight: 600; padding: 0; min-height: 20px; }
.save-status { font-size: 12px; color: #526c8d; }
.editor-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(235px, 26%); gap: 24px; max-width: 1440px; margin-inline: auto; align-items: start; }
.field-editor { min-width: 0; width: 100%; max-width: 900px; justify-self: center; }
.conflict-heading { font-size: 14px; margin-top: 24px; }
.conflict-fields { list-style: none; padding: 0; color: #526780; font-size: 13px; line-height: 2; }
.section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 15px; min-height: 32px; }
.section-heading h3, .summary-heading h3 { margin: 0; font-size: 14px; font-weight: 650; }
.section-heading h3 span { margin-left: 6px; font-size: 12px; font-weight: 400; color: #7b8799; }
.section-heading :deep(.el-button) { background: transparent; font-size: 12px; }
.field-card { position: relative; background: #fff; border: 1px solid #e0e7ef; border-radius: 10px; margin-bottom: 10px; transition: border-color .15s; }
.field-card:hover { border-color: #c5d3e5; }
.field-card.modified::before { content: ''; position: absolute; left: -1px; top: 14px; bottom: 14px; width: 3px; border-radius: 2px; background: #4c83de; }
.field-card.is-added::before { background: #31967c; }
.field-row { display: grid; grid-template-columns: 18px minmax(100px, 1fr) 117px 58px 54px; gap: 10px; align-items: center; padding: 16px 13px; }
.drag-handle, .field-actions button { border: 0; color: #7f8da1; background: none; padding: 4px 0; display: inline-flex; align-items: center; justify-content: center; cursor: pointer; font-size: 15px; }
.drag-handle { cursor: grab; }
.drag-handle:active { cursor: grabbing; }
.field-name { display: flex; align-items: center; gap: 7px; min-width: 0; }
.field-name :deep(.el-input) { flex: 1; min-width: 0; }
.field-row :deep(.el-input__wrapper) { box-shadow: none; padding-inline: 5px; background: none; }
.field-row :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px #c5d3e5 inset; }
.field-row :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #3b7de0 inset; }
.field-row :deep(.el-input__inner) { color: #273c58; font-size: 13px; }
.field-row :deep(.el-select__wrapper) { box-shadow: none; background: #f5f7fa; min-height: 30px; font-size: 12px; }
.change-badge { flex-shrink: 0; color: #356bb8; background: #edf3fe; font-size: 10px; line-height: 18px; border-radius: 4px; padding: 0 5px; }
.is-added .change-badge { background: #eaf6ef; color: #2c7b60; }
.required-toggle { display: flex; gap: 5px; align-items: center; color: #65738a; font-size: 11px; }
.required-toggle :deep(.el-switch) { --el-switch-on-color: #5084d6; --el-switch-off-color: #d9e0e9; }
.field-actions { display: flex; justify-content: end; gap: 12px; }
.field-actions button:hover { color: #246bdb; }
.field-actions button:last-child:hover { color: #b85a51; }
.field-actions .el-icon { transition: transform .15s; }
.field-actions .rotated { transform: rotate(180deg); }
.field-details { border-top: 1px solid #edf1f5; padding: 18px 20px 18px 40px; }
.detail-section + .detail-section { margin-top: 20px; }
.detail-section h4 { font-size: 12px; margin: 0 0 10px; font-weight: 600; }
.detail-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; }
.detail-heading :deep(.el-button) { color: #37679f; font-size: 11px; padding: 0 4px; }
.option-row { display: flex; align-items: center; gap: 9px; margin-bottom: 8px; }
.option-row > span { width: 14px; font-size: 11px; color: #8893a3; }
.option-row :deep(.el-input) { max-width: 320px; }
.option-row :deep(.el-button) { margin-left: 0; }
.field-hint { margin: 8px 0 0; color: #748096; font-size: 12px; }
.condition-editor { background: #f6f8fb; padding: 12px; border-radius: 7px; margin-bottom: 9px; min-width: 0; }
.condition-description { display: block; font-size: 12px; color: #4d6079; line-height: 1.6; margin-bottom: 10px; overflow-wrap: anywhere; }
.condition-controls { display: grid; grid-template-columns: minmax(0, 1fr) 65px minmax(0, 1fr) 24px; gap: 7px; align-items: center; }
.condition-controls > .el-select { min-width: 0; }
.condition-controls :deep(.el-select__wrapper) { font-size: 12px; }
.clear-condition { display: flex; align-items: center; gap: 7px; margin-top: 9px; font-size: 12px; color: #65758b; }
.removed-card { display: flex; gap: 10px; align-items: center; padding: 12px 18px; background: #fbf8f7; color: #6b6570; }
.removed-card > span:first-child { text-decoration: line-through; }
.removed-card .change-badge { color: #a26150; background: #f7e9e4; }
.removed-card :deep(.el-button) { margin-left: auto; font-size: 12px; }
.change-summary { min-width: 0; position: sticky; top: 24px; border-top: 2px solid #c8d8ee; padding-top: 17px; }
.summary-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.summary-heading > span { color: #75859c; font-size: 11px; }
.change-summary ul { list-style: none; margin: 19px 0 0; padding: 0; }
.change-summary li { display: flex; align-items: start; gap: 9px; font-size: 12px; line-height: 1.85; margin-bottom: 16px; color: #50627c; overflow-wrap: anywhere; }
.change-summary li i { display: block; flex: 0 0 5px; height: 5px; background: #5684c7; border-radius: 50%; margin-top: 9px; }
.change-summary li i.added { background: #369578; }
.change-summary li i.removed { background: #b67d6b; }
.summary-empty { font-size: 12px; color: #7b8799; margin: 18px 0; }
.summary-save { font-size: 11px; color: #758299; border-top: 1px solid #e0e7ef; padding-top: 13px; margin-top: 22px; }
.empty-fields, .empty-workspace { padding: 40px 12px; font-size: 13px; color: #6b7d93; }
.version-list { list-style: none; padding: 0; margin: 10px 0; }
.version-list li { display: flex; gap: 14px; padding: 0 0 28px; position: relative; }
.version-dot { flex: 0 0 8px; height: 8px; margin-top: 6px; border-radius: 50%; background: #6390cc; }
.version-list strong { color: #314760; font-size: 14px; }
.version-list p { color: #7d8b9c; font-size: 12px; margin: 7px 0 0; }
.range-form :deep(.el-select), .range-form :deep(.el-input-number) { width: 100%; }
.confirm-changes { padding-left: 19px; color: #465b77; }
.confirm-changes li { line-height: 1.8; margin-bottom: 10px; }
.history-effect { margin-top: 20px; color: #6a7586; font-size: 12px; }
.conflict-actions { display: flex; gap: 12px; margin-top: 25px; }
.muted { color: #708097; font-size: 13px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
button:focus-visible { outline: 2px solid #5187d4; outline-offset: 3px; }
@media (max-width: 1350px) {
  .admin-layout { grid-template-columns: 160px minmax(0, 1fr); gap: 22px; }
  .editor-grid { grid-template-columns: minmax(0,1fr) 218px; gap: 18px; }
  .field-row { grid-template-columns: 14px minmax(85px,1fr) 103px 54px 47px; gap: 7px; padding-inline: 10px; }
  .header-actions { gap: 3px; }
  .current-version { display: block; margin: 7px 0 0; }
}
@media (max-width: 1100px) {
  .admin-layout { grid-template-columns: 135px minmax(0,1fr); gap: 18px; }
  .editor-grid { grid-template-columns: minmax(0,1fr); }
  .change-summary { position: static; order: -1; padding-bottom: 7px; }
  .change-summary ul { display: flex; flex-wrap: wrap; column-gap: 18px; }
  .change-summary li { margin-bottom: 7px; }
  .summary-save { display: none; }
  .workspace-header { flex-wrap: wrap; }
}

</style>
