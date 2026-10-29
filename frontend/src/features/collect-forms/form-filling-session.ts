import { reactive } from 'vue';
import type {
  FormContextPayload,
  FormCreatePayload,
  FormUpdatePayload,
  FormVoidPayload,
} from '../../api-client/collect-forms-api';
import {
  ApiBizError,
  HttpRequestError,
  RequestTimeoutError,
} from '../../api-client/request';
import type {
  FormCreateResponse,
  FormDetailResponse,
  FormRecordView,
  FormTemplateSpecView,
} from '../../types/api';
import { getErrorMessage } from '../../utils/user-message';
import {
  cloneFields,
  emptyFields,
  hasFieldChanges,
  hasFieldContent,
  isFieldEmpty,
  normalizeFields,
  resolveFieldChange,
  validateFields,
  type ClearedField,
  type FieldValueMap,
} from './field-logic';
import {
  announceFormDraftSaveOutcome,
  deleteFormDraft,
  readFormDrafts,
  restoreFormDrafts,
  saveFormDraft,
  type DraftSavedElsewhere,
  type DraftSubmitState,
  type FormDraftRecord,
  type FormDraftSavedEntry,
  type FormFillContext,
  type SubmitSnapshot,
  formContextKey,
} from './draft-store';
import { RequestFence, type RequestToken } from './request-fence';

export interface FormApiLike {
  getFormDetail(params: FormContextPayload): Promise<FormDetailResponse>;
  createFormRecord(payload: FormCreatePayload): Promise<FormCreateResponse>;
  updateFormRecord(id: string, payload: FormUpdatePayload): Promise<FormRecordView>;
  voidFormRecord(id: string, payload: FormVoidPayload): Promise<FormRecordView>;
}

export interface FormSessionDialogs {
  confirmClearFields(block: FormBlockState, cleared: ClearedField[]): Promise<boolean>;
  confirmDiscardBlock(block: FormBlockState): Promise<boolean>;
  confirmVoidRecord(record: FormRecordView): Promise<string | null>;
  confirmSameReviewer(reviewerUserId: string | null): Promise<boolean>;
  confirmAcceptLatest(block: FormBlockState, record: FormRecordView): Promise<boolean>;
  notify(message: string, level: 'success' | 'warning' | 'info' | 'error'): void;
  focusField?(block: FormBlockState, apiKey: string): void;
}

export type BlockSaveState = 'idle' | 'saving' | 'saved' | 'failed';

export interface FormBlockState {
  key: string;
  draftId: string;
  editingSessionId: string;
  kind: 'create' | 'edit';
  targetRecordId: string | null;
  baseRecordVersion: number | null;
  baseFields: FieldValueMap;
  fields: FieldValueMap;
  requestKey: string | null;
  firstPendingSnapshot: SubmitSnapshot | null;
  submitSnapshot: SubmitSnapshot | null;
  savedElsewhere: DraftSavedElsewhere | null;
  inputRevision: number;
  persistedRevision: number;
  savedAt: string | null;
  saveState: BlockSaveState;
  restored: boolean;
  submitState: DraftSubmitState;
  frozen: boolean;
  attemptId: number;
  errors: Record<string, string>;
  notice: string;
  readOnly: boolean;
  readOnlyReason: string;
  structureStale: boolean;
  conflictRecord: FormRecordView | null;
  scopeId: string;
  storageVersionId: string;
  originVersionId?: string | null;
  versionSpecHash?: string | null;
  createdAt: string;
}

export interface FormSessionState {
  detail: FormDetailResponse | null;
  records: FormRecordView[];
  blocks: FormBlockState[];
  loading: boolean;
  refreshing: boolean;
  error: string;
  refreshError: string;
  notice: string;
  stopped: boolean;
}

export interface FormSessionOptions {
  context: FormFillContext;
  accountUserId: string;
  editingSessionId: string;
  identityGeneration: () => number;
  api: FormApiLike;
  dialogs: FormSessionDialogs;
  now?: () => Date;
  createId?: () => string;
}

type LoadOutcome = 'applied' | 'superseded' | 'failed';

const SAVE_DEBOUNCE_MS = 400;

function cloneSnapshot(snapshot: SubmitSnapshot): SubmitSnapshot {
  return {
    ...snapshot,
    fields: cloneFields(snapshot.fields),
  };
}

function isUnknownSubmitError(error: unknown): boolean {
  if (error instanceof RequestTimeoutError) {
    return true;
  }
  if (error instanceof HttpRequestError) {
    return error.status >= 500;
  }
  return !(error instanceof ApiBizError);
}

function isConflictError(error: unknown): boolean {
  return error instanceof HttpRequestError && error.status === 409;
}

/**
 * 单模板填写会话：一个页面/资源/模板/编辑会话对应一个实例。
 *
 * 保存块与已保存记录分开维护；查询与提交均绑定发起时的身份代际、上下文与编辑会话，
 * 迟到结果不会覆盖更新后的状态或清除其它块草稿。
 */
export class FormFillingSession {
  readonly state: FormSessionState;

  private readonly context: FormFillContext;
  private readonly contextKey: string;
  private readonly accountUserId: string;
  private readonly editingSession: string;
  private readonly identityGeneration: () => number;
  private readonly api: FormApiLike;
  private readonly dialogs: FormSessionDialogs;
  private readonly now: () => Date;
  private readonly createId: () => string;
  private readonly fence = new RequestFence();
  private readonly saveTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private attemptSequence = 0;
  private disposed = false;
  private stopped = false;

  constructor(options: FormSessionOptions) {
    this.context = options.context;
    this.contextKey = formContextKey(options.context);
    this.accountUserId = options.accountUserId;
    this.editingSession = options.editingSessionId;
    this.identityGeneration = options.identityGeneration;
    this.api = options.api;
    this.dialogs = options.dialogs;
    this.now = options.now ?? (() => new Date());
    this.createId = options.createId ?? (() => `${Date.now()}-${Math.random()}`);
    this.state = reactive<FormSessionState>({
      detail: null,
      records: [],
      blocks: [],
      loading: false,
      refreshing: false,
      error: '',
      refreshError: '',
      notice: '',
      stopped: false,
    });
  }

  get templateKey(): string {
    return this.context.templateKey;
  }

  get severityLevel(): string | null {
    return this.state.detail?.resource.severityLevel ?? null;
  }

  async initialize(): Promise<void> {
    if (this.disposed) {
      return;
    }
    this.state.loading = true;
    const outcome = await this.loadDetail('initial');
    if (outcome === 'applied') {
      this.restoreDrafts();
      this.reconcileBlocks();
    }
    this.state.loading = false;
  }

  async reload(): Promise<LoadOutcome> {
    return this.loadDetail('refresh');
  }

  private contextPayload(): FormContextPayload {
    return {
      gitlabBaseUrl: this.context.gitlabBaseUrl,
      projectId: this.context.projectId,
      resourceType: this.context.resourceType,
      resourceId: this.context.resourceId,
      templateKey: this.context.templateKey,
    };
  }

  private isCurrent(token: RequestToken, generation: number): boolean {
    return (
      !this.disposed
      && !this.stopped
      && this.fence.isCurrent(token)
      && token.scope === this.contextKey
      && generation === this.identityGeneration()
    );
  }

  private async loadDetail(mode: 'initial' | 'refresh'): Promise<LoadOutcome> {
    if (this.disposed || this.stopped) {
      return 'superseded';
    }
    const token = this.fence.next(this.contextKey);
    const generation = this.identityGeneration();
    if (mode === 'initial') {
      this.state.loading = true;
    } else {
      this.state.refreshing = true;
    }
    try {
      const detail = await this.api.getFormDetail(this.contextPayload());
      if (!this.isCurrent(token, generation)) {
        return 'superseded';
      }
      this.state.detail = detail;
      this.state.records = detail.records;
      this.state.error = '';
      this.state.refreshError = '';
      this.reconcileBlocks();
      return 'applied';
    } catch (error) {
      if (!this.isCurrent(token, generation)) {
        return 'superseded';
      }
      const message = getErrorMessage(error, '表单记录加载失败');
      if (mode === 'initial') {
        this.state.error = message;
      } else {
        this.state.refreshError = '列表刷新失败，已保留当前列表与填写内容';
      }
      return 'failed';
    } finally {
      if (this.isCurrent(token, generation)) {
        this.state.loading = false;
        this.state.refreshing = false;
      }
    }
  }

  private currentSpec() {
    return this.state.detail?.fieldSpec ?? null;
  }

  /** 编辑块按自身承载版本的冻结规格校验；新增块使用当前版本规格。 */
  private specForBlock(block: FormBlockState): FormTemplateSpecView | null {
    const detail = this.state.detail;
    if (!detail) {
      return null;
    }
    if (block.kind === 'edit') {
      return detail.versionSpecs?.[block.storageVersionId] ?? null;
    }
    return detail.fieldSpec ?? null;
  }

  private restoreDrafts(): void {
    if (!this.state.detail) {
      return;
    }
    const drafts = restoreFormDrafts(this.accountUserId, this.contextKey, this.editingSession);
    for (const draft of drafts) {
      if (this.state.blocks.some((block) => block.draftId === draft.draftId)) {
        continue;
      }
      const block = this.blockFromDraft(draft);
      this.state.blocks.push(block);
    }
  }

  private blockFromDraft(draft: FormDraftRecord): FormBlockState {
    const detail = this.state.detail;
    const savedElsewhere = draft.savedElsewhere ? { ...draft.savedElsewhere } : null;
    const scopeChanged = Boolean(
      detail && draft.context.scopeId !== detail.scopeId,
    );
    const structureStale = Boolean(
      detail
      && this.structureStale(
        draft.kind,
        draft.context.scopeId,
        draft.context.storageVersionId,
        draft.versionSpecHash,
        detail,
      ),
    );
    const readOnlyReasons: string[] = [];
    if (savedElsewhere) {
      readOnlyReasons.push(
        `原操作已在其它标签页保存（记录 ${savedElsewhere.recordId}），当前输入与已保存内容不同`,
      );
    }
    if (structureStale) {
      readOnlyReasons.push(
        scopeChanged ? '模板范围已变化' : '模板结构版本已变化',
      );
    }
    const readOnlyReason = readOnlyReasons.length
      ? `${readOnlyReasons.join('；')}，当前草稿仅可查看或复制到新填写块`
      : '';
    // 刷新/重开时不存在仍在途的提交：原 pending 只能按结果未知核验。
    const restoredSubmitState: DraftSubmitState = draft.submitState === 'pending'
      ? 'unknown'
      : draft.submitState;
    const block: FormBlockState = {
      key: draft.draftId,
      draftId: draft.draftId,
      editingSessionId: draft.editingSessionId,
      kind: draft.kind,
      targetRecordId: draft.targetRecordId,
      baseRecordVersion: draft.baseRecordVersion,
      baseFields: cloneFields(draft.baseFields),
      fields: cloneFields(draft.fields),
      requestKey: draft.requestKey,
      firstPendingSnapshot: draft.firstPendingSnapshot
        ? cloneSnapshot(draft.firstPendingSnapshot)
        : null,
      submitSnapshot: draft.submitSnapshot ? cloneSnapshot(draft.submitSnapshot) : null,
      savedElsewhere,
      inputRevision: draft.inputRevision,
      persistedRevision: draft.persistedRevision,
      savedAt: draft.savedAt,
      saveState: draft.persistedRevision >= draft.inputRevision ? 'saved' : 'saving',
      restored: true,
      submitState: restoredSubmitState,
      frozen: restoredSubmitState === 'unknown',
      attemptId: 0,
      errors: {},
      notice: savedElsewhere || structureStale
        ? ''
        : (restoredSubmitState === 'unknown'
          ? '提交结果待确认，请先核验本次提交'
          : ''),
      readOnly: Boolean(savedElsewhere) || structureStale,
      readOnlyReason,
      structureStale,
      conflictRecord: null,
      scopeId: draft.context.scopeId,
      storageVersionId: draft.context.storageVersionId,
      originVersionId: draft.originVersionId ?? null,
      versionSpecHash: draft.versionSpecHash ?? null,
      createdAt: draft.updatedAt,
    };
    return block;
  }

  private reconcileBlocks(): void {
    const detail = this.state.detail;
    if (!detail) {
      return;
    }
    for (const block of this.state.blocks) {
      this.applyStructureGuard(block, detail);
      if (block.submitState === 'pending' || block.submitState === 'unknown') {
        continue;
      }
      if (block.readOnly || block.savedElsewhere) {
        continue;
      }
      if (block.kind !== 'edit' || !block.targetRecordId) {
        continue;
      }
      const record = this.findRecord(block.targetRecordId);
      if (!record || record.deleted) {
        block.readOnly = true;
        block.readOnlyReason = '目标记录已作废或不可用，当前内容仅可查看或复制';
        block.submitState = 'none';
        continue;
      }
      if (!record.canEdit) {
        block.readOnly = true;
        block.readOnlyReason = '当前记录不可由本人编辑，内容仅可查看或复制';
        block.submitState = 'none';
        continue;
      }
      const blockSpec = this.specForBlock(block);
      if (!blockSpec) {
        block.readOnly = true;
        block.readOnlyReason = '记录的结构版本不可读，内容仅可查看或复制';
        block.submitState = 'none';
        continue;
      }
      const dirty = hasFieldChanges(block.fields, block.baseFields);
      if (record.recordVersion > (block.baseRecordVersion ?? 0)) {
        if (!dirty) {
          block.baseRecordVersion = record.recordVersion;
          block.baseFields = normalizeFields(blockSpec, record.fields);
          block.fields = cloneFields(block.baseFields);
          block.conflictRecord = null;
          block.submitState = 'none';
          block.notice = '';
        } else {
          block.conflictRecord = record;
          block.submitState = 'conflict';
          block.notice = '该记录已有更新，请对比最新内容后再提交';
        }
      }
    }
  }

  /**
   * 结构守卫：新增草稿跟随当前版本；编辑草稿要求所属版本规格与首次规格哈希证据一致。
   *
   * <p>规格缺失或哈希证据失配时一律按不可用处理，不能用当前版规格代填或归一化旧载荷。
   */
  private structureStale(
    kind: 'create' | 'edit',
    scopeId: string,
    storageVersionId: string,
    versionSpecHash: string | null | undefined,
    detail: FormDetailResponse,
  ): boolean {
    if (scopeId !== detail.scopeId) {
      return true;
    }
    if (!detail.versionSpecs?.[storageVersionId]) {
      return true;
    }
    if (kind === 'create') {
      return storageVersionId !== detail.storageVersionId;
    }
    const currentHash =
      detail.readableVersions?.find((version) => version.versionId === storageVersionId)
        ?.fieldSpecHash ?? null;
    return !currentHash || !versionSpecHash || versionSpecHash !== currentHash;
  }

  private applyStructureGuard(block: FormBlockState, detail: FormDetailResponse): void {
    const scopeChanged = block.scopeId !== detail.scopeId;
    const stale = this.structureStale(
      block.kind,
      block.scopeId,
      block.storageVersionId,
      block.versionSpecHash,
      detail,
    );
    if (stale) {
      block.structureStale = true;
      block.readOnly = true;
      const reasons: string[] = [];
      if (block.savedElsewhere) {
        reasons.push(this.savedElsewhereReason(block.savedElsewhere));
      }
      reasons.push(
        scopeChanged ? '模板范围已变化' : '模板结构版本已变化',
      );
      block.readOnlyReason = `${reasons.join('；')}，当前草稿仅可查看或复制到新填写块`;
      return;
    }
    if (block.structureStale) {
      block.structureStale = false;
      if (block.savedElsewhere) {
        block.readOnly = true;
        block.readOnlyReason = this.savedElsewhereReason(block.savedElsewhere);
      } else {
        block.readOnly = false;
        block.readOnlyReason = '';
      }
    }
  }

  private savedElsewhereReason(saved: DraftSavedElsewhere): string {
    return `原操作已在其它标签页保存（记录 ${saved.recordId}），当前输入与已保存内容不同`;
  }

  private findRecord(recordId: string): FormRecordView | null {
    return this.state.records.find((record) => record.id === recordId) ?? null;
  }

  private persistTimer(key: string): void {
    const timer = this.saveTimers.get(key);
    if (timer != null) {
      clearTimeout(timer);
      this.saveTimers.delete(key);
    }
  }

  private scheduleSave(block: FormBlockState): void {
    if (this.stopped || this.disposed) {
      return;
    }
    this.persistTimer(block.key);
    this.saveTimers.set(
      block.key,
      setTimeout(() => {
        this.saveTimers.delete(block.key);
        this.persistBlockNow(block);
      }, SAVE_DEBOUNCE_MS),
    );
  }

  private toDraft(block: FormBlockState): FormDraftRecord {
    return {
      draftSpecVersion: 2,
      accountUserId: this.accountUserId,
      contextKey: this.contextKey,
      context: {
        ...this.context,
        scopeId: block.scopeId,
        storageVersionId: block.storageVersionId,
      },
      draftId: block.draftId,
      editingSessionId: block.editingSessionId,
      kind: block.kind,
      targetRecordId: block.targetRecordId,
      baseRecordVersion: block.baseRecordVersion,
      originVersionId: block.originVersionId ?? null,
      baseStorageVersionId: block.storageVersionId,
      versionSpecHash: block.versionSpecHash ?? null,
      baseFields: cloneFields(block.baseFields),
      fields: cloneFields(block.fields),
      requestKey: block.requestKey,
      firstPendingSnapshot: block.firstPendingSnapshot
        ? cloneSnapshot(block.firstPendingSnapshot)
        : null,
      inputRevision: block.inputRevision,
      persistedRevision: block.persistedRevision,
      savedAt: block.savedAt,
      status: block.restored ? 'restored' : 'editing',
      submitState: block.submitState,
      submitSnapshot: block.submitSnapshot ? cloneSnapshot(block.submitSnapshot) : null,
      savedElsewhere: block.savedElsewhere ? { ...block.savedElsewhere } : null,
      updatedAt: this.now().toISOString(),
    };
  }

  /** 立即写入当前块；旧修订写入成功不会把更新的输入标记为已保存。 */
  persistBlockNow(block: FormBlockState): boolean {
    if (this.stopped || this.disposed) {
      return false;
    }
    this.persistTimer(block.key);
    const revision = block.inputRevision;
    const stored = saveFormDraft(this.toDraft(block));
    if (!stored) {
      block.saveState = 'failed';
      return false;
    }
    block.persistedRevision = revision;
    block.savedAt = stored.savedAt;
    if (block.inputRevision === revision) {
      block.saveState = 'saved';
    } else {
      block.saveState = 'saving';
      this.scheduleSave(block);
    }
    return true;
  }

  flushDrafts(): void {
    for (const block of this.state.blocks) {
      if (this.saveTimers.has(block.key) || block.inputRevision > block.persistedRevision) {
        this.persistBlockNow(block);
      }
    }
  }

  addCreateBlock(): FormBlockState | null {
    const spec = this.currentSpec();
    const detail = this.state.detail;
    if (!spec || !detail || !detail.writable || this.stopped || this.disposed) {
      return null;
    }
    const blank = this.state.blocks.find(
      (block) => block.kind === 'create'
        && !hasFieldContent(block.fields)
        && block.submitState === 'none'
        && !block.readOnly,
    );
    if (blank) {
      return blank;
    }
    const fields = emptyFields(spec);
    const createdAt = this.now().toISOString();
    const draftId = this.createId();
    const block: FormBlockState = {
      key: draftId,
      draftId,
      editingSessionId: this.editingSession,
      kind: 'create',
      targetRecordId: null,
      baseRecordVersion: null,
      baseFields: cloneFields(fields),
      fields,
      requestKey: this.createId(),
      firstPendingSnapshot: null,
      submitSnapshot: null,
      savedElsewhere: null,
      inputRevision: 0,
      persistedRevision: 0,
      savedAt: null,
      saveState: 'idle',
      restored: false,
      submitState: 'none',
      frozen: false,
      attemptId: 0,
      errors: {},
      notice: '',
      readOnly: false,
      readOnlyReason: '',
      structureStale: false,
      conflictRecord: null,
      scopeId: detail.scopeId,
      storageVersionId: detail.storageVersionId,
      createdAt,
    };
    this.state.blocks.push(block);
    this.persistBlockNow(block);
    return block;
  }

  /** 记录按自身承载版本的冻结规格显示/编辑；缺失时明确不可用，不得回退当前版规格。 */
  specForRecord(record: FormRecordView): FormTemplateSpecView | null {
    return this.state.detail?.versionSpecs?.[record.storageVersionId] ?? null;
  }

  startEdit(record: FormRecordView): FormBlockState | null {
    const detail = this.state.detail;
    if (!detail || !detail.writable || this.stopped || this.disposed) {
      return null;
    }
    // 编辑只按记录自身承载版本的冻结规格开放；版本不可读时不得用当前版规格代填。
    const spec = detail.versionSpecs?.[record.storageVersionId] ?? null;
    if (!spec) {
      this.dialogs.notify('该记录的结构版本不可读，暂不能编辑', 'warning');
      return null;
    }
    const existing = this.state.blocks.find(
      (block) => block.kind === 'edit' && block.targetRecordId === record.id,
    );
    if (existing) {
      return existing;
    }
    if (!record.canEdit) {
      this.dialogs.notify('该记录不属于当前账号或已不可编辑', 'warning');
      return null;
    }
    const fields = normalizeFields(spec, record.fields);
    const draftId = this.createId();
    const block: FormBlockState = {
      key: draftId,
      draftId,
      editingSessionId: this.editingSession,
      kind: 'edit',
      targetRecordId: record.id,
      baseRecordVersion: record.recordVersion,
      baseFields: cloneFields(fields),
      fields: cloneFields(fields),
      requestKey: null,
      firstPendingSnapshot: null,
      submitSnapshot: null,
      savedElsewhere: null,
      inputRevision: 0,
      persistedRevision: 0,
      savedAt: null,
      saveState: 'idle',
      restored: false,
      submitState: 'none',
      frozen: false,
      attemptId: 0,
      errors: {},
      notice: '',
      readOnly: false,
      readOnlyReason: '',
      structureStale: false,
      conflictRecord: null,
      scopeId: detail.scopeId,
      storageVersionId: record.storageVersionId,
      originVersionId: record.originVersionId,
      versionSpecHash:
        detail.readableVersions?.find(
          (version) => version.versionId === record.storageVersionId,
        )?.fieldSpecHash ?? null,
      createdAt: this.now().toISOString(),
    };
    this.state.blocks.push(block);
    this.persistBlockNow(block);
    return block;
  }

  blockIsDirty(block: FormBlockState): boolean {
    return block.kind === 'create'
      ? hasFieldContent(block.fields)
      : hasFieldChanges(block.fields, block.baseFields);
  }

  canSubmitBlock(block: FormBlockState): boolean {
    if (!this.canSubmit(block)) {
      return false;
    }
    return block.kind === 'create' || this.blockIsDirty(block);
  }

  /** 其它标签页完成同一次新增后的处理：仅清理内容未分叉的安全副本。 */
  applyRemoteSaveOutcome(outcome: {
    clearedDraftIds: string[];
    submittedFields: FieldValueMap | null;
    record: DraftSavedElsewhere | null;
    savedElsewhere: FormDraftSavedEntry[];
  }): void {
    const cleared = new Set(outcome.clearedDraftIds);
    let changed = false;
    for (const block of [...this.state.blocks]) {
      if (!cleared.has(block.draftId)) {
        continue;
      }
      if (block.submitState === 'pending') {
        // 本页提交仍在途，由该次请求自行结算，不依据其它页结果删除。
        continue;
      }
      const same = outcome.submittedFields
        ? hasSameFields(block.fields, outcome.submittedFields)
        : false;
      if (same && block.submitState === 'none' && !block.savedElsewhere) {
        this.persistTimer(block.key);
        this.removeBlock(block);
        changed = true;
        continue;
      }
      if (outcome.record) {
        this.markSavedElsewhere(block, outcome.record);
      } else {
        block.readOnly = true;
        block.readOnlyReason = '原操作已在其它标签页保存，当前输入已保留，请核对后处理';
        this.persistBlockNow(block);
      }
      changed = true;
    }
    for (const entry of outcome.savedElsewhere) {
      const block = this.state.blocks.find((item) => item.draftId === entry.draftId);
      if (!block || block.submitState === 'pending') {
        continue;
      }
      this.markSavedElsewhere(block, entry);
      changed = true;
    }
    if (changed) {
      this.state.notice = outcome.savedElsewhere.length > 0
        ? '原操作已在其它标签页保存，已保留当前标签页的不同输入'
        : '该记录已在其它标签页保存';
    }
  }

  private markSavedElsewhere(block: FormBlockState, saved: DraftSavedElsewhere): void {
    block.savedElsewhere = {
      recordId: saved.recordId,
      recordVersion: saved.recordVersion,
    };
    block.readOnly = true;
    block.readOnlyReason = this.savedElsewhereReason(block.savedElsewhere);
    block.notice = '';
    this.persistBlockNow(block);
  }

  async changeField(
    block: FormBlockState,
    apiKey: string,
    value: unknown,
  ): Promise<boolean> {
    const spec = this.specForBlock(block);
    if (
      !spec
      || block.frozen
      || block.readOnly
      || !this.state.detail?.writable
      || this.stopped
      || this.disposed
    ) {
      return false;
    }
    const outcome = resolveFieldChange(spec, block.fields, apiKey, value, this.severityLevel);
    if (outcome.cleared.length > 0) {
      const confirmed = await this.dialogs.confirmClearFields(block, outcome.cleared);
      if (!confirmed) {
        return false;
      }
    }
    block.fields = outcome.fields;
    block.errors = {};
    block.notice = '';
    block.restored = false;
    block.inputRevision += 1;
    block.saveState = 'saving';
    this.scheduleSave(block);
    return true;
  }

  /** 结果未知或提交在途时不能丢弃：原请求键与提交快照必须先核验。 */
  canDiscardBlock(block: FormBlockState): boolean {
    return block.submitState !== 'pending' && block.submitState !== 'unknown';
  }

  async discardBlock(block: FormBlockState): Promise<boolean> {
    if (!this.canDiscardBlock(block)) {
      this.dialogs.notify(
        block.submitState === 'pending'
          ? '提交正在进行，请等待结果后再丢弃'
          : '提交结果待确认，请先核验本次提交后再丢弃',
        'warning',
      );
      return false;
    }
    const dirty = this.blockIsDirty(block) || block.submitState !== 'none';
    if (dirty && !(await this.dialogs.confirmDiscardBlock(block))) {
      return false;
    }
    this.persistTimer(block.key);
    deleteFormDraft(this.toDraft(block));
    this.removeBlock(block);
    return true;
  }

  /** 结果未知或提交在途时不能复制成新提交：原请求键与快照必须先核验。 */
  canCopyBlock(block: FormBlockState): boolean {
    return block.submitState !== 'pending' && block.submitState !== 'unknown';
  }

  copyBlock(block: FormBlockState): FormBlockState | null {
    if (!this.canCopyBlock(block)) {
      this.dialogs.notify(
        block.submitState === 'pending'
          ? '提交正在进行，请等待结果后再复制'
          : '提交结果待确认，请先核验本次提交后再复制',
        'warning',
      );
      return null;
    }
    const spec = this.currentSpec();
    const detail = this.state.detail;
    if (!spec || !detail || !detail.writable) {
      return null;
    }
    const copied = this.addCreateBlockForFields(block.fields);
    if (copied) {
      copied.notice = '已复制原草稿内容，请核对模板结构后重新填写并提交';
      this.persistBlockNow(copied);
    }
    return copied;
  }

  private addCreateBlockForFields(source: FieldValueMap): FormBlockState | null {
    const spec = this.currentSpec();
    const detail = this.state.detail;
    if (!spec || !detail) {
      return null;
    }
    const fields: FieldValueMap = {};
    for (const field of spec.fields) {
      const value = source[field.apiKey];
      fields[field.apiKey] = Array.isArray(value) ? [...value] : (value ?? null);
    }
    const draftId = this.createId();
    const block: FormBlockState = {
      key: draftId,
      draftId,
      editingSessionId: this.editingSession,
      kind: 'create',
      targetRecordId: null,
      baseRecordVersion: null,
      baseFields: emptyFields(spec),
      fields,
      requestKey: this.createId(),
      firstPendingSnapshot: null,
      submitSnapshot: null,
      savedElsewhere: null,
      inputRevision: 1,
      persistedRevision: 0,
      savedAt: null,
      saveState: 'saving',
      restored: false,
      submitState: 'none',
      frozen: false,
      attemptId: 0,
      errors: {},
      notice: '',
      readOnly: false,
      readOnlyReason: '',
      structureStale: false,
      conflictRecord: null,
      scopeId: detail.scopeId,
      storageVersionId: detail.storageVersionId,
      createdAt: this.now().toISOString(),
    };
    this.state.blocks.push(block);
    this.persistBlockNow(block);
    return block;
  }

  async acceptLatest(block: FormBlockState): Promise<boolean> {
    const spec = this.specForBlock(block);
    const record = block.conflictRecord ?? (block.targetRecordId ? this.findRecord(block.targetRecordId) : null);
    if (!spec || !record) {
      return false;
    }
    if (!(await this.dialogs.confirmAcceptLatest(block, record))) {
      return false;
    }
    block.baseRecordVersion = record.recordVersion;
    block.baseFields = normalizeFields(spec, record.fields);
    block.fields = cloneFields(block.baseFields);
    block.conflictRecord = null;
    block.submitState = 'none';
    block.frozen = false;
    block.submitSnapshot = null;
    block.firstPendingSnapshot = null;
    block.notice = '已采用最新记录作为编辑基准，请核对后重新编辑';
    block.inputRevision += 1;
    block.saveState = 'saving';
    this.persistBlockNow(block);
    return true;
  }

  async submit(block: FormBlockState): Promise<void> {
    if (block.submitState === 'unknown') {
      await this.verify(block);
      return;
    }
    await this.startSubmit(block);
  }

  private canSubmit(block: FormBlockState): boolean {
    const detail = this.state.detail;
    if (!detail || !detail.writable || this.stopped) {
      return false;
    }
    if (block.readOnly || block.structureStale || block.frozen || block.submitState === 'pending') {
      return false;
    }
    if (block.submitState === 'conflict') {
      return false;
    }
    if (block.kind === 'edit') {
      const record = block.targetRecordId ? this.findRecord(block.targetRecordId) : null;
      if (!record || !record.canEdit || record.deleted) {
        return false;
      }
    }
    return true;
  }

  private async startSubmit(block: FormBlockState): Promise<void> {
    const spec = this.specForBlock(block);
    if (!spec || !this.canSubmit(block)) {
      return;
    }
    const validation = validateFields(spec, block.fields, this.severityLevel);
    block.errors = validation.errors;
    if (validation.firstInvalidField) {
      this.dialogs.notify('请先处理标红字段再提交', 'warning');
      this.dialogs.focusField?.(block, validation.firstInvalidField.apiKey);
      return;
    }
    let snapshot = block.submitSnapshot ? cloneSnapshot(block.submitSnapshot) : null;
    if (!snapshot) {
      snapshot = {
        requestKey: block.requestKey,
        fields: cloneFields(block.fields),
        confirmSameReviewer: block.firstPendingSnapshot?.confirmSameReviewer ?? false,
        baseRecordVersion: block.baseRecordVersion,
        capturedAt: this.now().toISOString(),
      };
      block.firstPendingSnapshot = cloneSnapshot(snapshot);
      block.submitSnapshot = cloneSnapshot(snapshot);
      this.persistBlockNow(block);
    }
    await this.performSubmit(block, snapshot);
  }

  private attemptIsCurrent(block: FormBlockState, attemptId: number, generation: number): boolean {
    return (
      !this.disposed
      && !this.stopped
      && generation === this.identityGeneration()
      && block.attemptId === attemptId
      && this.state.blocks.some((item) => item.key === block.key)
    );
  }

  /** 身份代际变化后，旧请求不得写回结果；仍保留的块转为未知结果等待核验。 */
  private markAttemptAbandoned(block: FormBlockState, generation: number): void {
    if (
      this.stopped
      || this.disposed
      || generation === this.identityGeneration()
      || !this.state.blocks.some((item) => item.key === block.key)
    ) {
      return;
    }
    block.submitState = 'unknown';
    block.frozen = true;
    block.notice = '账号身份已变化，提交结果待确认';
    this.persistBlockNow(block);
  }

  private async performSubmit(
    block: FormBlockState,
    snapshot: SubmitSnapshot,
    mode: 'submit' | 'verify' = 'submit',
  ): Promise<void> {
    const detail = this.state.detail;
    if (!detail) {
      return;
    }
    const attemptId = ++this.attemptSequence;
    const generation = this.identityGeneration();
    block.attemptId = attemptId;
    block.submitState = 'pending';
    block.frozen = true;
    block.notice = '';
    this.persistBlockNow(block);
    const basePayload = {
      ...this.contextPayload(),
      scopeId: block.scopeId,
      storageVersionId: block.storageVersionId,
    };
    try {
      if (block.kind === 'create') {
        if (!snapshot.requestKey) {
          throw new Error('新增草稿缺少请求标识');
        }
        const response = await this.api.createFormRecord({
          ...basePayload,
          requestKey: snapshot.requestKey,
          confirmSameReviewer: snapshot.confirmSameReviewer,
          fields: cloneFields(snapshot.fields),
        });
        if (!this.attemptIsCurrent(block, attemptId, generation)) {
          this.markAttemptAbandoned(block, generation);
          return;
        }
        if (response.outcome === 'CONFIRM_REQUIRED') {
          const verificationPending = mode === 'verify';
          if (!verificationPending) {
            block.submitState = 'none';
            block.frozen = false;
          }
          const confirmed = await this.dialogs.confirmSameReviewer(response.reviewerUserId ?? null);
          if (
            this.stopped
            || this.disposed
            || generation !== this.identityGeneration()
            || !this.state.blocks.some((item) => item.key === block.key)
          ) {
            return;
          }
          if (!confirmed) {
            if (verificationPending) {
              block.submitState = 'unknown';
              block.frozen = true;
              block.notice = '核验未完成：需要确认同走查人后才能重放本次提交';
              this.persistBlockNow(block);
              this.dialogs.notify('核验未完成，原提交结果仍未确认；请稍后重试核验', 'warning');
              return;
            }
            block.notice = '尚未提交';
            block.submitSnapshot = null;
            this.persistBlockNow(block);
            return;
          }
          const nextSnapshot = { ...cloneSnapshot(snapshot), confirmSameReviewer: true };
          block.submitSnapshot = cloneSnapshot(nextSnapshot);
          this.persistBlockNow(block);
          await this.performSubmit(block, nextSnapshot, mode);
          return;
        }
        if (!response.record) {
          throw new Error('保存结果缺少记录');
        }
        await this.completeSave(block, response.record);
        return;
      }
      if (!block.targetRecordId || snapshot.baseRecordVersion == null) {
        throw new Error('编辑草稿缺少记录版本');
      }
      const saved = await this.api.updateFormRecord(block.targetRecordId, {
        ...basePayload,
        expectedStorageVersionId: block.storageVersionId,
        recordVersion: snapshot.baseRecordVersion,
        fields: cloneFields(snapshot.fields),
      });
      if (!this.attemptIsCurrent(block, attemptId, generation)) {
        this.markAttemptAbandoned(block, generation);
        return;
      }
      await this.completeSave(block, saved);
    } catch (error) {
      if (!this.attemptIsCurrent(block, attemptId, generation)) {
        this.markAttemptAbandoned(block, generation);
        return;
      }
      if (mode === 'verify') {
        // 核验失败不代表原写结果未提交：409 等拒绝可能发生在原 key 查询之前。
        block.submitState = 'unknown';
        block.frozen = true;
        block.notice = `核验未完成：${getErrorMessage(error, '无法确认提交结果')}。原提交结果仍未确认，请保留草稿并在条件恢复后重新核验`;
        this.persistBlockNow(block);
        this.dialogs.notify('核验未完成，原提交结果仍未确认，请稍后重试核验', 'warning');
        return;
      }
      if (isUnknownSubmitError(error)) {
        block.submitState = 'unknown';
        block.frozen = true;
        block.notice = '提交结果待确认，请先核验本次提交';
        this.persistBlockNow(block);
        this.dialogs.notify('提交结果待确认，请核验后再修改或重新提交', 'warning');
        return;
      }
      block.submitState = isConflictError(error) ? 'conflict' : 'none';
      block.frozen = false;
      if (block.submitState === 'none') {
        block.submitSnapshot = null;
      }
      if (block.submitState === 'conflict') {
        block.notice = '记录版本已变化，请核对最新内容后再提交';
        const latest = block.targetRecordId ? this.findRecord(block.targetRecordId) : null;
        block.conflictRecord = latest;
        this.persistBlockNow(block);
        await this.loadDetail('refresh');
      } else {
        this.persistBlockNow(block);
      }
      this.dialogs.notify(getErrorMessage(error, '表单保存失败'), 'error');
    }
  }

  async verify(block: FormBlockState): Promise<void> {
    if (block.submitState !== 'unknown' || !block.submitSnapshot) {
      return;
    }
    const snapshot = cloneSnapshot(block.submitSnapshot);
    if (block.kind === 'create') {
      await this.performSubmit(block, snapshot, 'verify');
      return;
    }
    const detail = this.state.detail;
    if (!detail || !block.targetRecordId) {
      return;
    }
    const attemptId = ++this.attemptSequence;
    const generation = this.identityGeneration();
    block.attemptId = attemptId;
    block.submitState = 'pending';
    block.frozen = true;
    const outcome = await this.loadDetail('refresh');
    if (!this.attemptIsCurrent(block, attemptId, generation) || outcome === 'superseded') {
      this.markAttemptAbandoned(block, generation);
      return;
    }
    if (outcome === 'failed') {
      block.submitState = 'unknown';
      block.frozen = true;
      block.notice = '暂时无法核验提交结果，请稍后重试';
      this.persistBlockNow(block);
      return;
    }
    // 编辑核验需要所属版本规格与首次规格哈希证据仍一致；缺失或失配时不得用其它规格
    // 归一化载荷得出冲突，也不能放开复制/丢弃，保留首次快照等待原证据恢复后继续核验。
    const refreshedDetail = this.state.detail;
    const spec = this.specForBlock(block);
    if (!refreshedDetail || !spec) {
      block.submitState = 'unknown';
      block.frozen = true;
      block.notice = '核验未完成：所属结构版本当前不可用，无法比对保存内容；原提交结果仍未确认';
      this.persistBlockNow(block);
      return;
    }
    if (
      this.structureStale(
        block.kind,
        block.scopeId,
        block.storageVersionId,
        block.versionSpecHash,
        refreshedDetail,
      )
    ) {
      block.submitState = 'unknown';
      block.frozen = true;
      block.notice = '核验未完成：所属结构版本或首次规格证据已变化，无法比对保存内容；原提交结果仍未确认';
      this.persistBlockNow(block);
      return;
    }
    const record = this.findRecord(block.targetRecordId);
    if (!record || record.deleted) {
      block.submitState = 'conflict';
      block.frozen = false;
      block.notice = '目标记录已作废或不可用，当前内容仅可查看或复制';
      this.persistBlockNow(block);
      return;
    }
    const savedFields = normalizeFields(spec, record.fields);
    if (
      record.recordVersion === (snapshot.baseRecordVersion ?? -1) + 1
      && hasSameFields(savedFields, snapshot.fields)
    ) {
      await this.completeSave(block, record);
      return;
    }
    if (record.recordVersion === snapshot.baseRecordVersion && !record.deleted) {
      block.submitState = 'none';
      block.frozen = false;
      block.submitSnapshot = null;
      block.notice = '已确认本次提交未写入，可重新提交';
      this.persistBlockNow(block);
      return;
    }
    block.submitState = 'conflict';
    block.frozen = false;
    block.conflictRecord = record;
    block.notice = '记录已有新的保存结果，请对比最新内容后再决定';
    this.persistBlockNow(block);
  }

  private async completeSave(block: FormBlockState, record: FormRecordView): Promise<void> {
    this.mergeRecord(record);
    this.persistTimer(block.key);
    if (block.kind === 'create') {
      this.resolveCreateDraftCopies(block, record);
    } else {
      deleteFormDraft(this.toDraft(block));
    }
    this.removeBlock(block);
    this.state.notice = '记录已保存';
    this.dialogs.notify('记录已保存', 'success');
    const outcome = await this.loadDetail('refresh');
    if (outcome === 'failed') {
      this.state.refreshError = '记录已保存，列表刷新失败，可单独重试';
    }
  }

  /**
   * 同一次新增保存成功后结算其它标签页的同源草稿副本。
   *
   * 只清理内容与本次提交一致、且未在提交中的副本；已分叉、结果未知或提交在途的副本
   * 保留原内容、请求键与提交快照，标记为“原操作已在其它标签页保存”交用户显式处理。
   */
  private resolveCreateDraftCopies(block: FormBlockState, record: FormRecordView): void {
    const submittedFields = block.submitSnapshot
      ? cloneFields(block.submitSnapshot.fields)
      : cloneFields(block.fields);
    const saved: DraftSavedElsewhere = {
      recordId: record.id,
      recordVersion: record.recordVersion,
    };
    const savedElsewhere: FormDraftSavedEntry[] = [];
    for (const draft of readFormDrafts(this.accountUserId, this.contextKey)) {
      if (draft.kind !== 'create' || draft.draftId !== block.draftId) {
        continue;
      }
      if (draft.editingSessionId === this.editingSession) {
        continue;
      }
      if (draft.submitState === 'pending') {
        // 其它标签页的提交仍在途，由该次请求自行结算。
        continue;
      }
      if (draft.submitState === 'none' && hasSameFields(draft.fields, submittedFields)) {
        deleteFormDraft(draft);
        continue;
      }
      if (draft.savedElsewhere?.recordId !== saved.recordId) {
        saveFormDraft({ ...draft, savedElsewhere: saved });
      }
      savedElsewhere.push({ draftId: draft.draftId, ...saved });
    }
    deleteFormDraft(this.toDraft(block));
    announceFormDraftSaveOutcome({
      accountUserId: this.accountUserId,
      contextKey: this.contextKey,
      clearedDraftIds: [block.draftId],
      savedElsewhere,
      submittedFields,
      recordId: record.id,
      recordVersion: record.recordVersion,
    });
  }

  private mergeRecord(record: FormRecordView): void {
    const index = this.state.records.findIndex((item) => item.id === record.id);
    if (index >= 0) {
      this.state.records.splice(index, 1, record);
    } else {
      this.state.records.push(record);
    }
  }

  private removeBlock(block: FormBlockState): void {
    this.persistTimer(block.key);
    const index = this.state.blocks.findIndex((item) => item.key === block.key);
    if (index >= 0) {
      this.state.blocks.splice(index, 1);
    }
  }

  async voidRecord(record: FormRecordView): Promise<boolean> {
    const detail = this.state.detail;
    if (!detail || !record.canVoid || !detail.writable) {
      return false;
    }
    const confirmationText = await this.dialogs.confirmVoidRecord(record);
    if (confirmationText == null) {
      return false;
    }
    try {
      const voided = await this.api.voidFormRecord(record.id, {
        ...this.contextPayload(),
        scopeId: detail.scopeId,
        storageVersionId: record.storageVersionId,
        expectedStorageVersionId: record.storageVersionId,
        recordVersion: record.recordVersion,
        confirmVoid: true,
        confirmationText,
      });
      const index = this.state.records.findIndex((item) => item.id === record.id);
      if (index >= 0) {
        if (voided.deleted) {
          this.state.records.splice(index, 1);
        } else {
          this.state.records.splice(index, 1, voided);
        }
      }
      for (const block of this.state.blocks) {
        if (block.targetRecordId === record.id) {
          block.readOnly = true;
          block.readOnlyReason = '目标记录已作废，当前内容仅可查看或复制';
          block.submitState = 'none';
          this.persistBlockNow(block);
        }
      }
      this.dialogs.notify('记录已作废', 'success');
      const outcome = await this.loadDetail('refresh');
      if (outcome === 'failed') {
        this.state.refreshError = '记录已作废，列表刷新失败，可单独重试';
      }
      return true;
    } catch (error) {
      this.dialogs.notify(getErrorMessage(error, '表单记录作废失败'), 'error');
      return false;
    }
  }

  hasUnsubmitted(): boolean {
    return this.state.blocks.some((block) => {
      if (block.submitState === 'unknown') {
        return true;
      }
      return block.kind === 'create'
        ? hasFieldContent(block.fields)
        : hasFieldChanges(block.fields, block.baseFields);
    });
  }

  hasFailedDrafts(): boolean {
    return this.state.blocks.some((block) => block.saveState === 'failed');
  }

  unsubmittedBlocks(): FormBlockState[] {
    return this.state.blocks.filter((block) => {
      if (block.submitState === 'unknown') {
        return true;
      }
      return block.kind === 'create'
        ? hasFieldContent(block.fields)
        : hasFieldChanges(block.fields, block.baseFields);
    });
  }

  /** 退出登录广播后停止本地写入并清空内存输入。 */
  stopForLogout(): void {
    this.stopped = true;
    for (const key of [...this.saveTimers.keys()]) {
      this.persistTimer(key);
    }
    this.state.blocks.splice(0, this.state.blocks.length);
    this.state.stopped = true;
    this.state.notice = '已退出登录，本平台表单草稿已清除';
  }

  dispose(): void {
    this.disposed = true;
    for (const key of [...this.saveTimers.keys()]) {
      this.persistTimer(key);
    }
    this.fence.close();
  }
}

function hasSameFields(left: FieldValueMap, right: FieldValueMap): boolean {
  const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
  for (const key of keys) {
    const leftValue = left[key];
    const rightValue = right[key];
    if (Array.isArray(leftValue) && Array.isArray(rightValue)) {
      const leftSorted = [...leftValue].sort();
      const rightSorted = [...rightValue].sort();
      if (leftSorted.length !== rightSorted.length) {
        return false;
      }
      if (leftSorted.some((value, index) => value !== rightSorted[index])) {
        return false;
      }
      continue;
    }
    if (isFieldEmpty(leftValue) && isFieldEmpty(rightValue)) {
      continue;
    }
    if (leftValue !== rightValue) {
      return false;
    }
  }
  return true;
}
