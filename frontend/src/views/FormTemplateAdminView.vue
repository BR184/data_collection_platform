<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ArrowDown, ArrowUp, Delete, Plus, Refresh, Upload } from '@element-plus/icons-vue';
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
  businessChanges,
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
  hasUnsupportedConditions,
  fieldRowsFromSpec,
  historyImpactText,
  moveFieldRow,
  removeOption,
  updateOption,
  validateFieldRows,
  type ConditionRow,
  type ConditionRowKind,
  type FieldRow,
} from './form-template-admin/form-template-admin';

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
const optionsVisible = ref(false);
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
const blockers = computed(() => preview.value?.blockingErrors ?? []);
const canRetire = computed(() => scope.value?.status === 'ACTIVE');
const canDelete = computed(
  () => scope.value?.status === 'DRAFT' && !scope.value.currentVersionId,
);
const publicationActive = computed(() =>
  Boolean(publication.value && ['PREPARED', 'COPYING', 'VERIFIED'].includes(publication.value.stage)),
);
const dirty = computed(
  () => savedSnapshot.value !== null && localSnapshot() !== savedSnapshot.value,
);
const businessRows = computed(() => (preview.value ? businessChanges(preview.value) : []));
const conditionFieldChoices = computed(() => conditionFieldOptions(fieldRows.value));
const targetFieldChoices = computed(() =>
  conditionFieldOptions(fieldRows.value).filter((option) => option.label !== '议题严重级别'),
);
const unsupportedConditions = computed(() => {
  if (!specBase.value) {
    return false;
  }
  try {
    return hasUnsupportedConditions(buildSpec());
  } catch {
    return false;
  }
});
const optionRow = computed<FieldRow | null>(() => {
  if (optionsRowIndex.value === null) {
    return null;
  }
  return fieldRows.value[optionsRowIndex.value] ?? null;
});
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

function versionStatusLabel(status: string): string {
  switch (status) {
    case 'PUBLISHED':
      return '已发布';
    case 'INSTALLED':
      return '已构建';
    default:
      return '草稿';
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', guardBeforeUnload);
  await Promise.all([loadTemplates(), loadBindingOptions()]);
});

onBeforeUnmount(() => window.removeEventListener('beforeunload', guardBeforeUnload));

onBeforeRouteLeave(async () => {
  if (!dirty.value) {
    return true;
  }
  return confirmAction(
    '当前范围有未保存的字段、绑定或说明修改。离开后将丢失这些修改，确认离开？',
    '未保存修改',
  );
});

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
    ElMessage.error(getErrorMessage(error, '表单模板加载失败'));
  } finally {
    loading.value = false;
  }
}

async function refreshTemplatesList() {
  try {
    templates.value = await collectFormsApi.getFormTemplates();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '表单模板加载失败'));
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
  preview.value = fresh.preview;
  publication.value = fresh.latestPublication;
  hydrateDraft(fresh.draft);
  markSaved();
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
    ElMessage.error(getErrorMessage(error, '来源配置加载失败'));
  }
}

async function selectScope(scopeId: number) {
  const epoch = ++scopeLoadEpoch;
  selectedScopeId.value = scopeId;
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
      ElMessage.error(getErrorMessage(error, '表单范围加载失败'));
    }
  }
}

async function switchScope(scopeId: number) {
  if (scopeId === selectedScopeId.value) {
    return;
  }
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
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
    ElMessage.warning(getErrorMessage(error, '版本里程碑加载失败'));
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
  const confirmed = await confirmAction(
    `删除字段「${row.label}」后，新版本将不再显示该字段；历史记录仍保留旧值。确认删除？`,
    '删除字段',
  );
  if (!confirmed) {
    return;
  }
  fieldRows.value = fieldRows.value.filter((_, rowIndex) => rowIndex !== index);
}

function moveField(index: number, delta: number) {
  fieldRows.value = moveFieldRow(fieldRows.value, index, delta);
}

function openOptions(index: number) {
  optionsRowIndex.value = index;
  optionsVisible.value = true;
}

function addOptionRow() {
  if (optionsRowIndex.value === null) {
    return;
  }
  fieldRows.value = addOption(fieldRows.value, optionsRowIndex.value);
}

async function removeOptionRow(optionIndex: number) {
  if (optionsRowIndex.value === null) {
    return;
  }
  const row = fieldRows.value[optionsRowIndex.value];
  const option = row.options[optionIndex];
  const confirmed = await confirmAction(
    `删除选项「${option.label}」后，新版本中不可再选择；历史记录中的旧值仍保留。确认删除？`,
    '删除选项',
  );
  if (!confirmed) {
    return;
  }
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

function addCondition(kind: ConditionRowKind) {
  if (!specBase.value || !fieldRows.value.length) {
    return;
  }
  conditionRows.value = [
    ...conditionRows.value,
    createConditionRow(fieldRows.value, buildSpec(), kind),
  ];
}

async function removeCondition(index: number) {
  const row = conditionRows.value[index];
  if (!row) {
    return;
  }
  if (
    !(await confirmAction(
      `删除条件「${conditionDescription(row)}」？删除后该条件不再生效。`,
      '删除条件',
    ))
  ) {
    return;
  }
  conditionRows.value = conditionRows.value.filter((_, item) => item !== index);
}

function addRequiredCondition() {
  void addCondition('required');
}

function addMutualCondition() {
  void addCondition('mutual');
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

async function saveDraft() {
  if (!detail.value?.draft || !scope.value) {
    return;
  }
  const errors = validateFieldRows(fieldRows.value);
  if (errors.length) {
    ElMessage.error(errors[0]);
    return;
  }
  const hasBinding =
    bindingSourceId.value !== null
    || bindingProjects.value.trim() !== ''
    || bindingVersionProject.value !== null;
  const projectIds = parseProjectIds();
  if (hasBinding && (!bindingSourceId.value || !bindingVersionProject.value || !projectIds.length)) {
    ElMessage.error('绑定必须同时填写来源、项目集合与发布项目');
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
      ElMessage.success('草稿已保存');
    } else {
      // 服务端已接受提交时的内容；等待期间的新输入保留为未保存修改，不能覆盖。
      savedSnapshot.value = submittedSnapshot;
      ElMessage.warning('草稿已保存；等待期间的新输入尚未保存，请再次保存');
    }
    await refreshTemplatesList();
  } catch (error) {
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
      ElMessage.warning('草稿已被他人修改，本地修改已保留');
    } else {
      ElMessage.error(getErrorMessage(error, '草稿保存失败'));
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
  ElMessage.success('已载入服务器最新草稿');
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
  ElMessage.warning('已基于最新修订继续编辑；再次保存将覆盖服务器草稿');
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
  ) {
    return preview.value;
  }
  previewing.value = true;
  try {
    preview.value = await collectFormsApi.previewFormScope(
      scope.value.id,
      detail.value.draft.revision,
    );
    return preview.value;
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '预览失败'));
    return null;
  } finally {
    previewing.value = false;
  }
}

async function ensureSaved(): Promise<boolean> {
  if (dirty.value) {
    ElMessage.warning('请先保存草稿，再进行预览或发布');
    return false;
  }
  if (!detail.value?.draft) {
    ElMessage.warning('草稿尚未加载');
    return false;
  }
  return true;
}

async function createPublication() {
  if (!scope.value || !detail.value?.draft) {
    return;
  }
  if (!(await ensureSaved())) {
    return;
  }
  if (!bindingBound.value) {
    ElMessage.warning('请先保存完整绑定并重新预览');
    return;
  }
  const target = await previewScope(false);
  if (!target || target.blockingErrors.length) {
    ElMessage.warning('预览存在阻断问题，不能发布');
    return;
  }
  const confirmed = await confirmAction(
    `将以 ${detail.value.draft.versionLabel || '未命名版本'} 发布当前已保存结构；发布期间该范围停写，确认继续？`,
    '发布确认',
  );
  if (!confirmed) {
    return;
  }
  const expectedCurrentVersionId = scope.value.currentVersionId;
  if (
    !pendingRequest.value
    || pendingRequest.value.draftRevision !== target.draftRevision
    || pendingRequest.value.previewId !== target.id
    || pendingRequest.value.expectedCurrentVersionId !== expectedCurrentVersionId
  ) {
    pendingRequest.value = {
      draftRevision: target.draftRevision,
      previewId: target.id,
      expectedCurrentVersionId,
      key: crypto.randomUUID(),
    };
  }
  publishing.value = true;
  try {
    publication.value = await collectFormsApi.createFormPublication(scope.value.id, {
      draftRevision: pendingRequest.value.draftRevision,
      previewId: pendingRequest.value.previewId,
      expectedCurrentVersionId: pendingRequest.value.expectedCurrentVersionId,
      requestKey: pendingRequest.value.key,
    });
    await pollPublication(publication.value.id);
  } catch (error) {
    if (isConflictError(error)) {
      ElMessage.warning('范围或草稿已变化，请刷新后重试；本地修改已保留');
    } else {
      ElMessage.error(getErrorMessage(error, '发布任务创建失败'));
    }
  } finally {
    publishing.value = false;
  }
}

async function pollPublication(publicationId: number) {
  for (let attempt = 0; attempt < 120; attempt += 1) {
    publication.value = await collectFormsApi.getFormPublication(publicationId);
    if (!['PREPARED', 'COPYING', 'VERIFIED'].includes(publication.value.stage)) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  if (publication.value?.stage === 'PUBLISHED') {
    pendingRequest.value = null;
    ElMessage.success('结构版本已发布');
    const scopeId = scope.value?.id;
    if (scopeId) {
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
          ElMessage.error(getErrorMessage(error, '表单范围刷新失败'));
        }
      }
    }
    return;
  }
  if (publication.value?.stage === 'FAILED') {
    ElMessage.error(publication.value.lastError || '发布失败，请查看错误并恢复');
  }
}

async function recoverPublication() {
  if (!publication.value || !scope.value) {
    return;
  }
  try {
    publication.value = await collectFormsApi.recoverFormPublication(publication.value.id);
    ElMessage.success('任务已恢复为待执行');
    await pollPublication(publication.value.id);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '发布任务恢复失败'));
  }
}

async function refreshCurrent() {
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
  const scopeId = selectedScopeId.value;
  if (!scopeId) {
    await loadTemplates();
    return;
  }
  const epoch = ++scopeLoadEpoch;
  const baselineSnapshot = localSnapshot();
  try {
    const outcome = await refreshScopePreservingInput(
      scopeId,
      baselineSnapshot,
      epoch,
      false,
    );
    if (outcome === 'kept') {
      ElMessage.warning('刷新期间的新输入已保留，未覆盖本地修改');
    }
  } catch (error) {
    if (epoch === scopeLoadEpoch) {
      ElMessage.error(getErrorMessage(error, '表单范围刷新失败'));
    }
  }
}

async function retireScope() {
  if (!scope.value?.currentVersionId) {
    return;
  }
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
  const confirmed = await confirmAction(
    '退役后该范围拒绝新增与编辑，历史记录仍可查看。确认退役？',
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
    ElMessage.success('范围已退役');
    await loadTemplates(scope.value.id);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '范围退役失败'));
  }
}

async function deleteScope() {
  if (!scope.value) {
    return;
  }
  if (!(await confirmDiscardIfDirty())) {
    return;
  }
  const confirmed = await confirmAction(
    '仅未发布且无历史记录的范围可以删除。确认删除？',
    '删除确认',
  );
  if (!confirmed) {
    return;
  }
  try {
    await collectFormsApi.deleteFormScope(scope.value.id);
    ElMessage.success('范围已删除');
    selectedScopeId.value = null;
    await loadTemplates();
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '范围删除失败'));
  }
}

async function createScope() {
  if (!createScopeKey.value.trim()) {
    ElMessage.warning('请填写范围键');
    return;
  }
  try {
    const created = await collectFormsApi.createFormScope({
      templateKey: createTemplateKey.value,
      scopeKey: createScopeKey.value.trim(),
    });
    createVisible.value = false;
    createScopeKey.value = '';
    ElMessage.success('范围已创建');
    await loadTemplates(created.scope.id);
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '范围创建失败'));
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

function publicationKindLabel(kind: string | undefined): string {
  switch (kind) {
    case 'INITIAL_EMPTY':
      return '首次启用（无历史）';
    case 'INITIAL_MIGRATION':
      return '旧表首次迁移';
    case 'MIGRATION':
      return '结构升级迁移';
    default:
      return kind ?? '-';
  }
}

function publicationStageLabel(stage: string): string {
  switch (stage) {
    case 'PREPARED':
      return '待执行';
    case 'COPYING':
      return '复制中';
    case 'VERIFIED':
      return '已核验';
    case 'PUBLISHED':
      return '已发布';
    case 'FAILED':
      return '失败';
    default:
      return stage;
  }
}

async function confirmAction(message: string, title: string): Promise<boolean> {
  const result = await ElMessageBox.confirm(message, title, {
    type: 'warning',
    confirmButtonText: '确认',
    cancelButtonText: '取消',
  }).catch(() => false);
  return Boolean(result);
}

async function confirmDiscardIfDirty(): Promise<boolean> {
  if (!dirty.value) {
    return true;
  }
  return confirmAction(
    '当前范围有未保存的修改，继续操作将丢失这些修改。确认继续？',
    '未保存修改',
  );
}

function guardBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) {
    return;
  }
  event.preventDefault();
  event.returnValue = '';
}
</script>

<template>
  <div class="form-template-admin" v-loading="loading">
    <div class="form-template-admin__header">
      <div>
        <h2>表单管理</h2>
        <p>直接维护字段名称、填写方式、必填、选项与顺序；系统负责内部字段身份、存储与发布。</p>
      </div>
      <div class="form-template-admin__actions">
        <el-button :icon="Refresh" @click="refreshCurrent">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="createVisible = true">新建范围</el-button>
      </div>
    </div>

    <el-container class="form-template-admin__body">
      <el-aside width="320px" class="form-template-admin__aside">
        <el-empty v-if="!templates.length" description="暂无模板" />
        <div v-for="template in templates" :key="template.templateKey" class="template-group">
          <div class="template-group__title">
            {{ template.displayName }}
            <span class="template-group__key">{{ template.templateKey }}</span>
          </div>
          <div
            v-for="item in template.scopes"
            :key="item.id"
            class="scope-item"
            :class="{ 'scope-item--active': item.id === selectedScopeId }"
            @click="switchScope(item.id)"
          >
            <div class="scope-item__title">
              <span>{{ item.scopeKey }}</span>
              <el-tag size="small" :type="item.status === 'ACTIVE' ? 'success' : item.status === 'RETIRED' ? 'info' : 'warning'">
                {{ item.status === 'ACTIVE' ? '已启用' : item.status === 'RETIRED' ? '已退役' : '草稿' }}
              </el-tag>
            </div>
            <div class="scope-item__meta">
              当前版本 {{ item.currentVersionLabel || '未发布' }}
              <span v-if="item.activePublications"> · 发布中</span>
            </div>
          </div>
          <el-empty v-if="!template.scopes.length" description="暂无范围" :image-size="48" />
        </div>
      </el-aside>

      <el-main v-if="detail && scope" class="form-template-admin__main">
        <div class="scope-header">
          <div>
            <h3>{{ scope.templateKey }} / {{ scope.scopeKey }}</h3>
            <div class="scope-header__meta">
              <el-tag :type="scope.status === 'ACTIVE' ? 'success' : scope.status === 'RETIRED' ? 'info' : 'warning'">
                {{ scopeStatusLabel }}
              </el-tag>
              <span>草稿修订 {{ detail.draft?.revision ?? '-' }}</span>
              <span>当前版本 {{ scope.currentVersionLabel || '未发布' }}</span>
              <span>记录 {{ detail.recordCount }} · 实例 {{ detail.instanceCount }}</span>
              <el-tag v-if="dirty" size="small" type="warning">有未保存修改</el-tag>
            </div>
          </div>
          <div class="scope-header__actions">
            <el-button v-if="canRetire" @click="retireScope">退役</el-button>
            <el-button v-if="canDelete" type="danger" plain :icon="Delete" @click="deleteScope">删除</el-button>
          </div>
        </div>

        <el-tabs>
          <el-tab-pane label="业务字段">
            <div class="toolbar">
              <el-button :icon="Plus" @click="addField">新增字段</el-button>
              <el-button type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
            </div>
            <el-alert
              v-if="scope.status === 'ACTIVE'"
              type="info"
              :closable="false"
              show-icon
              title="字段身份与存储由系统维护；修改显示名称不会改变历史值。变更类型或删除字段前请先查看预览影响。"
            />
            <el-table :data="fieldRows" border size="small" class="field-table" row-key="name">
              <el-table-column label="#" width="52">
                <template #default="{ $index }">{{ $index + 1 }}</template>
              </el-table-column>
              <el-table-column label="字段名称" min-width="170">
                <template #default="{ row, $index }">
                  <el-input
                    v-model="row.label"
                    size="small"
                    maxlength="64"
                    @input="updateRow($index, { label: row.label })"
                  />
                </template>
              </el-table-column>
              <el-table-column label="填写方式" width="150">
                <template #default="{ row, $index }">
                  <el-select
                    :model-value="row.control"
                    size="small"
                    @change="changeControl($index, $event)"
                  >
                    <el-option
                      v-for="option in controlOptionsFor(row)"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="80">
                <template #default="{ row }">
                  <el-switch v-model="row.required" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="选项" min-width="200">
                <template #default="{ row, $index }">
                  <template v-if="row.type === 'enum' || row.type === 'enum-array'">
                    <span v-if="row.dictionary" class="option-summary">标准词典</span>
                    <template v-else>
                      <span class="option-summary">
                        {{ row.options.map((option) => option.label).join(' / ') || '未配置' }}
                      </span>
                      <el-button link type="primary" @click="openOptions($index)">配置选项</el-button>
                    </template>
                  </template>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ $index }">
                  <el-button link :icon="ArrowUp" @click="moveField($index, -1)" />
                  <el-button link :icon="ArrowDown" @click="moveField($index, 1)" />
                  <el-button link type="danger" :icon="Delete" @click="removeField($index)" />
                </template>
              </el-table-column>
            </el-table>

            <div class="condition-panel">
              <div class="condition-panel__title">
                字段条件
                <span class="condition-panel__actions">
                  <el-button link type="primary" @click="addRequiredCondition">新增必填条件</el-button>
                  <el-button link type="primary" @click="addMutualCondition">新增互斥条件</el-button>
                </span>
              </div>
              <el-table
                v-if="conditionRows.length"
                :data="conditionRows"
                size="small"
                border
                class="condition-table"
              >
                <el-table-column label="触发条件" min-width="360">
                  <template #default="{ row, $index }">
                    <div class="condition-editor__line">
                      <el-select
                        :model-value="row.triggerField"
                        size="small"
                        class="condition-trigger"
                        @change="changeConditionTrigger($index, $event)"
                      >
                        <el-option
                          v-for="option in conditionFieldChoices"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <el-select
                        v-if="row.kind === 'required'"
                        :model-value="row.operator"
                        size="small"
                        class="condition-operator"
                        @change="changeConditionOperator($index, $event)"
                      >
                        <el-option
                          v-for="option in conditionOperatorChoices(row)"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <span v-else class="muted">选择</span>
                      <el-select
                        :model-value="row.values[0] ?? ''"
                        size="small"
                        class="condition-value"
                        @change="updateCondition($index, { values: $event ? [$event] : [] })"
                      >
                        <el-option
                          v-for="option in conditionChoices(row)"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <template v-if="row.kind === 'mutual'">
                        <span class="muted">时，不能同时选择</span>
                        <el-select
                          :model-value="row.conflictValues"
                          multiple
                          size="small"
                          class="condition-conflicts"
                          @change="updateCondition($index, { conflictValues: $event })"
                        >
                          <el-option
                            v-for="option in conditionChoices(row)"
                            :key="option.value"
                            :label="option.label"
                            :value="option.value"
                          />
                        </el-select>
                      </template>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="结果" min-width="260">
                  <template #default="{ row, $index }">
                    <div v-if="row.kind === 'required'" class="condition-editor__line">
                      <el-select
                        :model-value="row.target"
                        size="small"
                        class="condition-target"
                        @change="updateCondition($index, { target: $event })"
                      >
                        <el-option
                          v-for="option in targetFieldChoices"
                          :key="option.value"
                          :label="option.label"
                          :value="option.value"
                        />
                      </el-select>
                      <span class="muted">必填</span>
                      <el-switch
                        :model-value="row.clearWhenOff"
                        size="small"
                        :disabled="!canClearWhen(row, buildSpec())"
                        @change="updateCondition($index, { clearWhenOff: Boolean($event) })"
                      />
                      <span class="muted">否则清空</span>
                    </div>
                    <span v-else class="muted">互斥校验</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="70" fixed="right">
                  <template #default="{ $index }">
                    <el-button link type="danger" :icon="Delete" @click="removeCondition($index)" />
                  </template>
                </el-table-column>
              </el-table>
              <div v-else class="muted">暂无字段条件</div>
              <div v-if="unsupportedConditions" class="muted condition-panel__hint">
                存在暂不支持在此编辑的条件规则，保存时原样保留；请在发布预览中核对。
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="范围与依据">
            <el-form label-width="140px" class="binding-form">
              <el-form-item label="来源配置">
                <el-select
                  v-model="bindingSourceId"
                  placeholder="选择 GitLab 来源"
                  style="width: 100%"
                  @change="loadMilestones"
                >
                  <el-option
                    v-for="option in bindingOptions"
                    :key="option.sourceConfigId"
                    :label="`${option.name}（${option.canonicalGitlabBaseUrl ?? '地址无效'}）`"
                    :value="option.sourceConfigId"
                    :disabled="!option.canonicalGitlabBaseUrl"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="项目集合">
                <el-input v-model="bindingProjects" placeholder="例如 9, 12" @blur="loadMilestones" />
              </el-form-item>
              <el-form-item label="发布项目">
                <el-input-number v-model="bindingVersionProject" :min="1" @change="loadMilestones" />
              </el-form-item>
              <el-form-item label="发布标签">
                <el-input v-model="versionLabel" maxlength="64" placeholder="例如 CC2027R1" />
              </el-form-item>
              <el-form-item label="版本里程碑">
                <el-select
                  v-if="milestones?.available"
                  v-model="selectedMilestoneId"
                  placeholder="从 ODS 里程碑选择"
                  style="width: 100%"
                  @change="applyMilestone"
                >
                  <el-option
                    v-for="item in milestones.items"
                    :key="item.id"
                    :label="item.title"
                    :value="item.id"
                  />
                </el-select>
                <el-alert
                  v-else
                  type="warning"
                  :closable="false"
                  show-icon
                  title="ODS 里程碑未就绪，请先完成镜像同步"
                />
              </el-form-item>
              <el-form-item label="核对说明">
                <el-input
                  v-model="evidenceNote"
                  type="textarea"
                  :rows="3"
                  maxlength="500"
                  placeholder="例如：已核对里程碑与项目范围，来源数据完整"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="saving" @click="saveDraft">保存草稿与依据</el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <el-tab-pane label="预览与发布">
            <div class="toolbar">
              <el-button :loading="previewing" @click="previewScope(true)">生成预览</el-button>
              <el-button
                type="primary"
                :icon="Upload"
                :loading="publishing"
                :disabled="publicationActive"
                @click="createPublication"
              >
                发布结构
              </el-button>
              <el-tag v-if="!bindingBound" type="warning">绑定未完成</el-tag>
              <el-tag v-if="dirty" type="warning">请先保存草稿</el-tag>
            </div>

            <el-alert
              v-for="(blocker, index) in blockers"
              :key="index"
              type="error"
              :closable="false"
              show-icon
              :title="blocker"
              class="blocker"
            />

            <el-descriptions v-if="preview" :column="3" border size="small" class="preview-meta">
              <el-descriptions-item label="发布类型">
                {{ publicationKindLabel(preview.publicationKind) }}
              </el-descriptions-item>
              <el-descriptions-item label="影响记录">{{ preview.impact.records ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="影响实例">{{ preview.impact.instances ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="新增字段">{{ preview.diff.added ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="删除字段">{{ preview.diff.removed ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="调整字段">{{ preview.diff.modified ?? 0 }}</el-descriptions-item>
            </el-descriptions>

            <el-alert
              v-if="preview"
              type="info"
              :closable="false"
              show-icon
              :title="historyImpactText(preview)"
              class="impact"
            />

            <el-table v-if="businessRows.length" :data="businessRows" border size="small" class="diff-table">
              <el-table-column prop="fieldLabel" label="字段" width="160" />
              <el-table-column prop="changeLabel" label="变化" width="150" />
              <el-table-column prop="before" label="调整前" min-width="180" />
              <el-table-column prop="after" label="调整后" min-width="180" />
              <el-table-column prop="history" label="历史记录影响" min-width="260" />
            </el-table>

            <div v-if="publication" class="publication-card">
              <div class="publication-card__title">
                最近发布 #{{ publication.id }}
                <el-tag :type="publication.stage === 'PUBLISHED' ? 'success' : publication.stage === 'FAILED' ? 'danger' : 'warning'">
                  {{ publicationStageLabel(publication.stage) }}
                </el-tag>
              </div>
              <div class="publication-card__meta">
                {{ publicationKindLabel(publication.publicationKind) }} · 尝试 {{ publication.attemptNo }} ·
                目标版本 {{ publication.toVersionId }}
                <span v-if="publication.recoveryCycle"> · 恢复 {{ publication.recoveryCycle }} 次</span>
              </div>
              <el-alert
                v-if="publication.lastError"
                type="error"
                :closable="false"
                show-icon
                :title="publication.lastError"
              />
              <div class="publication-card__actions">
                <el-button
                  v-if="publication.stage === 'FAILED'"
                  type="warning"
                  @click="recoverPublication"
                >
                  恢复任务
                </el-button>
                <el-button @click="pollPublication(publication.id)">刷新状态</el-button>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane label="版本历史">
            <el-table :data="detail.versions" border size="small" class="version-table">
              <el-table-column prop="versionLabel" label="版本标签" min-width="160" />
              <el-table-column label="状态" width="160">
                <template #default="{ row }">
                  {{ versionStatusLabel(row.versionStatus) }}
                  <el-tag v-if="row.id === scope?.currentVersionId" size="small" type="success">
                    当前版本
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="publishedAt" label="发布时间" min-width="180" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </el-main>

      <el-main v-else class="form-template-admin__empty">
        <el-empty description="选择左侧范围查看字段、绑定与发布状态" />
      </el-main>
    </el-container>

    <el-dialog v-model="optionsVisible" :title="`配置选项：${optionRow?.label ?? ''}`" width="560px">
      <div v-if="optionRow" class="option-editor">
        <div v-for="(option, index) in optionRow.options" :key="option.value" class="option-editor__row">
          <el-input
            :model-value="option.label"
            size="small"
            maxlength="64"
            @input="updateOptionLabel(index, $event)"
          />
          <el-button link type="danger" :icon="Delete" @click="removeOptionRow(index)" />
        </div>
        <el-empty v-if="!optionRow.options.length" description="尚未配置选项" :image-size="48" />
      </div>
      <template #footer>
        <el-button @click="addOptionRow">新增选项</el-button>
        <el-button type="primary" @click="optionsVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="conflictVisible" title="草稿已被他人修改" width="640px">
      <p>
        服务器上的草稿修订为 {{ conflictDraft?.revision ?? '-' }}。您的本地修改仍然保留；
        请选择如何处理，系统不会自动覆盖任何一方。
      </p>
      <el-descriptions v-if="conflictDraft" :column="2" border size="small">
        <el-descriptions-item label="服务器发布标签">
          {{ conflictDraft.versionLabel || '未填写' }}
        </el-descriptions-item>
        <el-descriptions-item label="服务器字段数">
          {{ conflictDraft.fieldSpec?.fields?.length ?? 0 }}
        </el-descriptions-item>
        <el-descriptions-item label="本地字段数">{{ fieldRows.length }}</el-descriptions-item>
        <el-descriptions-item label="本地发布标签">{{ versionLabel || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="服务器字段" :span="2">
          {{ conflictDraft.fieldSpec?.fields?.map((field) => field.label).join('、') || '无' }}
        </el-descriptions-item>
        <el-descriptions-item label="本地字段" :span="2">
          {{ fieldRows.map((row) => row.label).join('、') || '无' }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="conflictVisible = false">保留双方，稍后处理</el-button>
        <el-button @click="applyServerDraft">载入服务器版本（覆盖本地）</el-button>
        <el-button type="primary" @click="keepLocalWithLatestRevision">
          保留本地，基于最新修订继续
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建表单范围" width="480px">
      <el-form label-width="110px">
        <el-form-item label="模板">
          <el-select v-model="createTemplateKey" style="width: 100%">
            <el-option
              v-for="template in templates"
              :key="template.templateKey"
              :label="template.displayName"
              :value="template.templateKey"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="范围键">
          <el-input v-model="createScopeKey" maxlength="64" placeholder="例如 cc-2027-r1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="createScope">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.form-template-admin {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
}
.form-template-admin__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.form-template-admin__header h2 {
  margin: 0 0 6px;
}
.form-template-admin__header p {
  margin: 0;
  color: var(--el-text-color-secondary);
}
.form-template-admin__actions {
  display: flex;
  gap: 8px;
}
.form-template-admin__body {
  min-height: 520px;
  gap: 16px;
}
.form-template-admin__aside {
  border-right: 1px solid var(--el-border-color-light);
  padding-right: 12px;
}
.template-group {
  margin-bottom: 16px;
}
.template-group__title {
  font-weight: 600;
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}
.template-group__key {
  color: var(--el-text-color-secondary);
  font-weight: 400;
  font-size: 12px;
}
.scope-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.scope-item:hover,
.scope-item--active {
  background: var(--el-fill-color-light);
}
.scope-item__title {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}
.scope-item__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}
.form-template-admin__main {
  padding: 0;
}
.form-template-admin__empty {
  display: flex;
  align-items: center;
  justify-content: center;
}
.scope-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.scope-header h3 {
  margin: 0 0 8px;
}
.scope-header__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.field-table,
.diff-table {
  margin-top: 12px;
}
.preview-meta {
  margin: 12px 0;
}
.blocker {
  margin-bottom: 8px;
}
.impact {
  margin-bottom: 12px;
}
.binding-form {
  max-width: 720px;
}
.publication-card {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}
.publication-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.publication-card__meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 8px 0;
}
.publication-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}
.condition-panel {
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.condition-panel__title {
  font-weight: 600;
  margin-bottom: 6px;
}
.condition-panel ul {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-regular);
}
.option-summary {
  margin-right: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.option-editor__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.engineering {
  margin-top: 12px;
}
</style>
