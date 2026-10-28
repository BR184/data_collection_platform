import { createUuid } from '../../utils/uuid';
import { cloneFields, type FieldValueMap } from './field-logic';

const DRAFT_PREFIX_V2 = 'qaflex.form.draft.v2';
const DRAFT_PREFIX_V1 = 'qaflex.form.draft.v1';
const SESSION_KEY = 'qaflex.form.editing-session';
const LOGOUT_KEY = 'qaflex.form.logout-signal';
const SAVE_OUTCOME_KEY = 'qaflex.form.draft-save-outcome';

export interface FormFillContext {
  gitlabBaseUrl: string;
  projectId: number;
  resourceType: string;
  resourceId: string;
  templateKey: string;
}

export interface DraftFormContext extends FormFillContext {
  scopeId: string;
  storageVersionId: string;
}

export interface SubmitSnapshot {
  requestKey: string | null;
  fields: FieldValueMap;
  confirmSameReviewer: boolean;
  baseRecordVersion: number | null;
  capturedAt: string;
}

export type DraftSubmitState = 'none' | 'pending' | 'unknown' | 'conflict';

export interface DraftSavedElsewhere {
  recordId: string;
  recordVersion: number;
}

export interface FormDraftRecord {
  draftSpecVersion: 1 | 2;
  accountUserId: string;
  contextKey: string;
  context: DraftFormContext;
  draftId: string;
  editingSessionId: string;
  kind: 'create' | 'edit';
  targetRecordId: string | null;
  baseRecordVersion: number | null;
  originVersionId: string | null;
  baseStorageVersionId: string;
  versionSpecHash: string | null;
  baseFields: FieldValueMap;
  fields: FieldValueMap;
  requestKey: string | null;
  firstPendingSnapshot: SubmitSnapshot | null;
  inputRevision: number;
  persistedRevision: number;
  savedAt: string | null;
  status: 'editing' | 'restored';
  submitState: DraftSubmitState;
  submitSnapshot: SubmitSnapshot | null;
  savedElsewhere: DraftSavedElsewhere | null;
  updatedAt: string;
}

export interface FormDraftSavedEntry extends DraftSavedElsewhere {
  draftId: string;
}

export interface FormStorageSignal {
  type: 'logout' | 'draft-save-outcome';
  accountUserId: string;
  contextKey: string | null;
  draftIds: string[];
  submittedFields?: FieldValueMap | null;
  recordId?: string | null;
  recordVersion?: number;
  savedElsewhere?: FormDraftSavedEntry[];
  at: string;
}

export interface FormDraftSaveOutcome {
  accountUserId: string;
  contextKey: string;
  clearedDraftIds: string[];
  savedElsewhere: FormDraftSavedEntry[];
  submittedFields: FieldValueMap;
  recordId: string;
  recordVersion: number;
}

let memorySessionId = '';

/**
 * 编辑会话标识：同一标签页刷新后保留，新建标签页获得独立标识。
 *
 * sessionStorage 不可用时退回模块内存标识，此时草稿仍只保存在当前页面内存中。
 */
export function editingSessionId(): string {
  try {
    const existing = sessionStorage.getItem(SESSION_KEY);
    if (existing) {
      return existing;
    }
    const created = createUuid();
    sessionStorage.setItem(SESSION_KEY, created);
    return created;
  } catch {
    if (!memorySessionId) {
      memorySessionId = createUuid();
    }
    return memorySessionId;
  }
}

/** 与后端保存的 canonicalGitlabBaseUrl 对齐，保证同一来源的草稿命中同一上下文。 */
export function canonicalGitlabBaseUrl(raw: string): string {
  const trimmed = raw.trim();
  try {
    const url = new URL(trimmed);
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return trimmed.replace(/\/+$/, '');
    }
    const path = url.pathname.replace(/\/+$/, '');
    return `${url.protocol}//${url.host}${path}`;
  } catch {
    return trimmed.replace(/\/+$/, '');
  }
}

export function formContextKey(context: FormFillContext): string {
  return [
    canonicalGitlabBaseUrl(context.gitlabBaseUrl),
    String(context.projectId),
    context.resourceType,
    context.resourceId,
    context.templateKey,
  ].join('|');
}

function encodeSegment(value: string): string {
  return encodeURIComponent(value);
}

function draftKey(identity: {
  accountUserId: string;
  contextKey: string;
  editingSessionId: string;
  draftId: string;
}): string {
  return [
    DRAFT_PREFIX_V2,
    encodeSegment(identity.accountUserId),
    encodeSegment(identity.contextKey),
    identity.editingSessionId,
    identity.draftId,
  ].join('|');
}

function draftEntries(): Array<[string, string]> {
  const entries: Array<[string, string]> = [];
  try {
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index);
      if (
        !key
        || (!key.startsWith(`${DRAFT_PREFIX_V2}|`)
          && !key.startsWith(`${DRAFT_PREFIX_V1}|`))
      ) {
        continue;
      }
      const value = localStorage.getItem(key);
      if (value != null) {
        entries.push([key, value]);
      }
    }
  } catch {
    return [];
  }
  return entries;
}

function parseDraft(raw: string): FormDraftRecord | null {
  try {
    const parsed = JSON.parse(raw) as FormDraftRecord;
    if (
      (parsed?.draftSpecVersion !== 1 && parsed?.draftSpecVersion !== 2)
      || typeof parsed.accountUserId !== 'string'
      || typeof parsed.contextKey !== 'string'
      || typeof parsed.draftId !== 'string'
      || typeof parsed.editingSessionId !== 'string'
      || (parsed.kind !== 'create' && parsed.kind !== 'edit')
    ) {
      return null;
    }
    return {
      ...parsed,
      originVersionId: parsed.originVersionId ?? null,
      baseStorageVersionId: parsed.baseStorageVersionId ?? parsed.context.storageVersionId,
      versionSpecHash: parsed.versionSpecHash ?? null,
      savedElsewhere: parsed.savedElsewhere ?? null,
    };
  } catch {
    return null;
  }
}

function writeDraft(record: FormDraftRecord): FormDraftRecord | null {
  const savedAt = new Date().toISOString();
  const stored: FormDraftRecord = {
    ...record,
    draftSpecVersion: 2,
    fields: cloneFields(record.fields),
    baseFields: cloneFields(record.baseFields),
    savedElsewhere: record.savedElsewhere ?? null,
    persistedRevision: record.inputRevision,
    savedAt,
    updatedAt: savedAt,
  };
  try {
    localStorage.setItem(draftKey(stored), JSON.stringify(stored));
    localStorage.removeItem(v1DraftKey(stored));
    return stored;
  } catch {
    return null;
  }
}

/**
 * 把旧版 v1 草稿就地升级为 v2 键。
 *
 * 只改变存储键与规格版本，保留原 draftId、requestKey、首次快照与时间戳；
 * 写入失败时保留 v1 条目，下次读取继续重试。
 */
function migrateLegacyEntries(): void {
  for (const [key, value] of draftEntries()) {
    if (key.startsWith(`${DRAFT_PREFIX_V2}|`)) {
      continue;
    }
    const legacy = parseDraft(value);
    if (!legacy) {
      continue;
    }
    const upgraded: FormDraftRecord = { ...legacy, draftSpecVersion: 2 };
    try {
      localStorage.setItem(draftKey(upgraded), JSON.stringify(upgraded));
      localStorage.removeItem(key);
    } catch {
      // 存储写入失败：保留 v1，草稿仍可读。
    }
  }
}

export function readFormDrafts(accountUserId: string, contextKey: string): FormDraftRecord[] {
  migrateLegacyEntries();
  return draftEntries()
    .map(([, value]) => parseDraft(value))
    .filter((draft): draft is FormDraftRecord => draft != null)
    .filter(
      (draft) => draft.accountUserId === accountUserId && draft.contextKey === contextKey,
    )
    .sort((left, right) => left.updatedAt.localeCompare(right.updatedAt));
}

export function readSessionDrafts(
  accountUserId: string,
  contextKey: string,
  editingSession: string,
): FormDraftRecord[] {
  return readFormDrafts(accountUserId, contextKey).filter(
    (draft) => draft.editingSessionId === editingSession,
  );
}

/**
 * 为新编辑会话克隆同一上下文的旧会话草稿。
 *
 * 副本保留原 draftId、requestKey 与首次待提交快照；各会话按自身键写入，互不覆盖。
 * 已存在同 draftId 副本时不再重复克隆。
 */
export function restoreFormDrafts(
  accountUserId: string,
  contextKey: string,
  editingSession: string,
): FormDraftRecord[] {
  const all = readFormDrafts(accountUserId, contextKey);
  const own = all.filter((draft) => draft.editingSessionId === editingSession);
  if (own.length > 0) {
    return own;
  }
  // 新会话只恢复最近一次编辑会话的草稿副本；其它活动标签页的草稿保持各自独立，
  // 既不在新页面重复堆叠，也不会被新会话覆盖。
  const latestBySession = new Map<string, FormDraftRecord>();
  for (const draft of all) {
    const current = latestBySession.get(draft.editingSessionId);
    if (!current || current.updatedAt < draft.updatedAt) {
      latestBySession.set(draft.editingSessionId, draft);
    }
  }
  let sourceSession: string | null = null;
  let sourceUpdatedAt = '';
  for (const [session, draft] of latestBySession) {
    if (draft.updatedAt > sourceUpdatedAt) {
      sourceSession = session;
      sourceUpdatedAt = draft.updatedAt;
    }
  }
  if (!sourceSession) {
    return [];
  }
  for (const source of all) {
    if (source.editingSessionId !== sourceSession) {
      continue;
    }
    const restored: FormDraftRecord = {
      ...source,
      editingSessionId: editingSession,
      status: 'restored',
      persistedRevision: source.inputRevision,
    };
    writeDraft(restored);
  }
  return readSessionDrafts(accountUserId, contextKey, editingSession);
}

export function saveFormDraft(record: FormDraftRecord): FormDraftRecord | null {
  return writeDraft(record);
}

function v1DraftKey(identity: {
  accountUserId: string;
  contextKey: string;
  editingSessionId: string;
  draftId: string;
}): string {
  return [
    DRAFT_PREFIX_V1,
    encodeSegment(identity.accountUserId),
    encodeSegment(identity.contextKey),
    identity.editingSessionId,
    identity.draftId,
  ].join('|');
}

export function deleteFormDraft(draft: FormDraftRecord): void {
  try {
    const identity = {
      accountUserId: draft.accountUserId,
      contextKey: draft.contextKey,
      editingSessionId: draft.editingSessionId,
      draftId: draft.draftId,
    };
    localStorage.removeItem(draftKey(identity));
    localStorage.removeItem(v1DraftKey(identity));
  } catch {
    // 存储不可用时内存块仍由调用方移除；离开保护会继续提示未保存风险。
  }
}

export function deleteFormDraftsForAccount(accountUserId: string): void {
  for (const [key, value] of draftEntries()) {
    const draft = parseDraft(value);
    if (draft?.accountUserId === accountUserId) {
      try {
        localStorage.removeItem(key);
      } catch {
        // 退出清理尽力而为，身份代际与后续写入检查仍会阻止旧内容复活。
      }
    }
  }
}

export function hasFormDrafts(accountUserId: string): boolean {
  return draftEntries().some(([, value]) => parseDraft(value)?.accountUserId === accountUserId);
}

function writeSignal(key: string, payload: FormStorageSignal): void {
  try {
    localStorage.setItem(key, JSON.stringify(payload));
  } catch {
    // 广播失败时其它标签页仍会通过身份/鉴权失败停止写入。
  }
}

export function announceFormLogout(accountUserId: string): void {
  deleteFormDraftsForAccount(accountUserId);
  writeSignal(LOGOUT_KEY, {
    type: 'logout',
    accountUserId,
    contextKey: null,
    draftIds: [],
    at: new Date().toISOString(),
  });
}

export function announceFormDraftSaveOutcome(outcome: FormDraftSaveOutcome): void {
  writeSignal(SAVE_OUTCOME_KEY, {
    type: 'draft-save-outcome',
    accountUserId: outcome.accountUserId,
    contextKey: outcome.contextKey,
    draftIds: [...outcome.clearedDraftIds],
    submittedFields: cloneFields(outcome.submittedFields),
    recordId: outcome.recordId,
    recordVersion: outcome.recordVersion,
    savedElsewhere: outcome.savedElsewhere.map((entry) => ({ ...entry })),
    at: new Date().toISOString(),
  });
}

function parseSignal(raw: string | null): FormStorageSignal | null {
  if (!raw) {
    return null;
  }
  try {
    const signal = JSON.parse(raw) as FormStorageSignal;
    return typeof signal?.accountUserId === 'string' && Array.isArray(signal.draftIds)
      ? signal
      : null;
  } catch {
    return null;
  }
}

export function readLatestLogoutSignal(): FormStorageSignal | null {
  try {
    return parseSignal(localStorage.getItem(LOGOUT_KEY));
  } catch {
    return null;
  }
}

/** 监听其它标签页的退出与草稿清理信号；返回移除监听函数。 */
export function subscribeFormStorageSignals(
  listener: (signal: FormStorageSignal) => void,
): () => void {
  if (typeof window === 'undefined') {
    return () => undefined;
  }
  const handler = (event: StorageEvent) => {
    if (event.key !== LOGOUT_KEY && event.key !== SAVE_OUTCOME_KEY) {
      return;
    }
    const signal = parseSignal(event.newValue);
    if (signal) {
      listener(signal);
    }
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}
