import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  announceFormDraftSaveOutcome,
  announceFormLogout,
  canonicalGitlabBaseUrl,
  deleteFormDraft,
  deleteFormDraftsForAccount,
  editingSessionId,
  formContextKey,
  hasFormDrafts,
  readFormDrafts,
  readSessionDrafts,
  restoreFormDrafts,
  saveFormDraft,
  subscribeFormStorageSignals,
  type FormDraftRecord,
} from './draft-store';

const context = {
  gitlabBaseUrl: 'https://gitlab.example.com',
  projectId: 7,
  resourceType: 'issue',
  resourceId: '31',
  templateKey: 'issue_reply',
};
const contextKey = formContextKey(context);

function draft(overrides: Partial<FormDraftRecord> = {}): FormDraftRecord {
  const base: FormDraftRecord = {
    draftSpecVersion: 1,
    originVersionId: null,
    baseStorageVersionId: '22',
    versionSpecHash: null,
    accountUserId: 'alice',
    contextKey,
    context: { ...context, scopeId: '11', storageVersionId: '22' },
    draftId: 'draft-1',
    editingSessionId: 'session-a',
    kind: 'create',
    targetRecordId: null,
    baseRecordVersion: null,
    baseFields: {},
    fields: { fixStatus: 'RESOLVED' },
    requestKey: 'request-1',
    firstPendingSnapshot: null,
    inputRevision: 3,
    persistedRevision: 3,
    savedAt: '2026-10-23T01:00:00.000Z',
    status: 'editing',
    submitState: 'none',
    submitSnapshot: null,
    savedElsewhere: null,
    updatedAt: '2026-10-23T01:00:00.000Z',
  };
  return { ...base, ...overrides };
}

function draftStorageKey(prefix: string): string {
  return [prefix, encodeURIComponent('alice'), encodeURIComponent(contextKey), 'session-a', 'draft-1'].join('|');
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
});

describe('form draft store', () => {
  it('isolates drafts by account, context, editing session and draft id', () => {
    saveFormDraft(draft());
    saveFormDraft(draft({ accountUserId: 'bob' }));
    saveFormDraft(draft({ editingSessionId: 'session-b' }));
    saveFormDraft(draft({ contextKey: 'other-context' }));

    expect(readFormDrafts('alice', contextKey)).toHaveLength(2);
    expect(readSessionDrafts('alice', contextKey, 'session-a')).toHaveLength(1);
    expect(readFormDrafts('bob', contextKey)).toHaveLength(1);
    expect(hasFormDrafts('alice')).toBe(true);
    expect(hasFormDrafts('carol')).toBe(false);
  });

  it('clones drafts into a new editing session without overwriting the source', () => {
    saveFormDraft(
      draft({
        firstPendingSnapshot: {
          requestKey: 'request-1',
          fields: { fixStatus: 'RESOLVED' },
          confirmSameReviewer: false,
          baseRecordVersion: null,
          capturedAt: '2026-10-23T01:00:00.000Z',
        },
      }),
    );

    const restored = restoreFormDrafts('alice', contextKey, 'session-b');
    expect(restored).toHaveLength(1);
    expect(restored[0].editingSessionId).toBe('session-b');
    expect(restored[0].draftId).toBe('draft-1');
    expect(restored[0].requestKey).toBe('request-1');
    expect(restored[0].firstPendingSnapshot?.requestKey).toBe('request-1');
    expect(restored[0].status).toBe('restored');

    const restoredAgain = restoreFormDrafts('alice', contextKey, 'session-b');
    expect(restoredAgain).toHaveLength(1);

    const source = readSessionDrafts('alice', contextKey, 'session-a');
    expect(source).toHaveLength(1);
    expect(source[0].status).toBe('editing');
  });

  it('restores only the most recently updated session when several tabs have drafts', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-23T01:00:00.000Z'));
    saveFormDraft(draft({ editingSessionId: 'session-old', draftId: 'old-draft' }));
    vi.setSystemTime(new Date('2026-10-23T03:00:00.000Z'));
    saveFormDraft(
      draft({
        editingSessionId: 'session-new',
        draftId: 'new-draft',
        fields: { fixStatus: 'DEFERRED' },
      }),
    );
    vi.useRealTimers();

    const restored = restoreFormDrafts('alice', contextKey, 'session-third');
    expect(restored).toHaveLength(1);
    expect(restored[0].draftId).toBe('new-draft');
    expect(restored[0].editingSessionId).toBe('session-third');
    expect(readSessionDrafts('alice', contextKey, 'session-old')).toHaveLength(1);
    expect(readSessionDrafts('alice', contextKey, 'session-new')).toHaveLength(1);
  });

  it('keeps session copies independent after cloning', () => {
    saveFormDraft(draft());
    restoreFormDrafts('alice', contextKey, 'session-b');

    saveFormDraft(draft({ editingSessionId: 'session-b', fields: { fixStatus: 'DEFERRED' }, inputRevision: 5, persistedRevision: 5 }));

    expect(readSessionDrafts('alice', contextKey, 'session-a')[0].fields.fixStatus).toBe('RESOLVED');
    expect(readSessionDrafts('alice', contextKey, 'session-b')[0].fields.fixStatus).toBe('DEFERRED');
  });

  it('persists saved-elsewhere marks on one copy without touching the others', () => {
    saveFormDraft(draft());
    saveFormDraft(draft({ draftId: 'draft-2' }));
    restoreFormDrafts('alice', contextKey, 'session-b');

    const sessionBDraft = readSessionDrafts('alice', contextKey, 'session-b')
      .find((item) => item.draftId === 'draft-1');
    expect(sessionBDraft).toBeTruthy();
    deleteFormDraft(sessionBDraft!);
    expect(readSessionDrafts('alice', contextKey, 'session-b')).toHaveLength(1);

    const diverged = readSessionDrafts('alice', contextKey, 'session-a')
      .find((item) => item.draftId === 'draft-1')!;
    saveFormDraft({ ...diverged, savedElsewhere: { recordId: '101', recordVersion: 1 } });
    const marked = readSessionDrafts('alice', contextKey, 'session-a')
      .find((item) => item.draftId === 'draft-1')!;
    expect(marked.savedElsewhere).toEqual({ recordId: '101', recordVersion: 1 });
    expect(marked.requestKey).toBe('request-1');
    expect(marked.fields).toEqual({ fixStatus: 'RESOLVED' });

    expect(
      readSessionDrafts('alice', contextKey, 'session-a').map((item) => item.draftId).sort(),
    ).toEqual(['draft-1', 'draft-2']);
    expect(
      readSessionDrafts('alice', contextKey, 'session-b').map((item) => item.draftId),
    ).toEqual(['draft-2']);
  });

  it('clears all drafts for an account and broadcasts logout', () => {
    saveFormDraft(draft());
    saveFormDraft(draft({ accountUserId: 'bob' }));
    const signals: string[] = [];
    const unsubscribe = subscribeFormStorageSignals((signal) => signals.push(signal.type));

    announceFormLogout('alice');
    expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
    expect(readFormDrafts('bob', contextKey)).toHaveLength(1);

    const raw = localStorage.getItem('qaflex.form.logout-signal');
    expect(raw).toBeTruthy();
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'qaflex.form.logout-signal',
        newValue: raw,
      }),
    );
    expect(signals).toEqual(['logout']);
    unsubscribe();
    deleteFormDraftsForAccount('bob');
    expect(readFormDrafts('bob', contextKey)).toHaveLength(0);
  });

  it('broadcasts the save outcome so other tabs can judge their copies', () => {
    saveFormDraft(draft());
    restoreFormDrafts('alice', contextKey, 'session-b');
    const signals: Array<{
      type: string;
      draftIds: string[];
      submittedFields?: unknown;
      recordId?: string | null;
      savedElsewhere?: unknown;
    }> = [];
    const unsubscribe = subscribeFormStorageSignals((signal) =>
      signals.push({
        type: signal.type,
        draftIds: signal.draftIds,
        submittedFields: signal.submittedFields,
        recordId: signal.recordId,
        savedElsewhere: signal.savedElsewhere,
      }),
    );

    announceFormDraftSaveOutcome({
      accountUserId: 'alice',
      contextKey,
      clearedDraftIds: ['draft-1'],
      savedElsewhere: [{ draftId: 'draft-1', recordId: '101', recordVersion: 1 }],
      submittedFields: { fixStatus: 'RESOLVED' },
      recordId: '101',
      recordVersion: 1,
    });
    const raw = localStorage.getItem('qaflex.form.draft-save-outcome');
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'qaflex.form.draft-save-outcome',
        newValue: raw,
      }),
    );
    expect(signals).toEqual([
      {
        type: 'draft-save-outcome',
        draftIds: ['draft-1'],
        submittedFields: { fixStatus: 'RESOLVED' },
        recordId: '101',
        savedElsewhere: [{ draftId: 'draft-1', recordId: '101', recordVersion: 1 }],
      },
    ]);
    unsubscribe();
  });

  it('upgrades a legacy v1 draft entry to v2 without changing its identity', () => {
    const legacyPayload = {
      ...draft(),
      draftSpecVersion: 1,
      originVersionId: undefined,
      baseStorageVersionId: undefined,
      versionSpecHash: undefined,
    };
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(v1Key, JSON.stringify(legacyPayload));

    const [migrated] = readFormDrafts('alice', contextKey);
    expect(migrated.draftSpecVersion).toBe(2);
    expect(migrated.draftId).toBe('draft-1');
    expect(migrated.requestKey).toBe('request-1');
    expect(migrated.fields).toEqual({ fixStatus: 'RESOLVED' });
    expect(migrated.originVersionId).toBeNull();
    expect(migrated.baseStorageVersionId).toBe('22');
    expect(migrated.versionSpecHash).toBeNull();
    expect(migrated.updatedAt).toBe('2026-10-23T01:00:00.000Z');
    expect(localStorage.getItem(v1Key)).toBeNull();
    expect(localStorage.getItem(v2Key)).toBeTruthy();

    deleteFormDraft(migrated);
    expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
    expect(localStorage.getItem(v2Key)).toBeNull();
  });

  it('writes v2 keys and removes the matching v1 entry on save', () => {
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(v1Key, JSON.stringify({ ...draft(), draftSpecVersion: 1 }));

    saveFormDraft(draft());
    expect(localStorage.getItem(v1Key)).toBeNull();
    expect(localStorage.getItem(v2Key)).toBeTruthy();
    expect(readSessionDrafts('alice', contextKey, 'session-a')[0].draftSpecVersion).toBe(2);
  });

  it('skips corrupted draft entries instead of failing the list', () => {
    saveFormDraft(draft());
    localStorage.setItem('qaflex.form.draft.v1|%7B%22broken', '{not-json');
    expect(readFormDrafts('alice', contextKey)).toHaveLength(1);
  });

  it('keeps the editing session id stable inside a tab', () => {
    const first = editingSessionId();
    sessionStorage.setItem('qaflex.form.editing-session', first);
    expect(editingSessionId()).toBe(first);
    sessionStorage.setItem('qaflex.form.editing-session', 'session-explicit');
    expect(editingSessionId()).toBe('session-explicit');
  });

  it('canonicalizes source addresses so equivalent links share drafts', () => {
    expect(canonicalGitlabBaseUrl('HTTPS://GitLab.Example.com:443/root/')).toBe(
      'https://gitlab.example.com/root',
    );
    expect(canonicalGitlabBaseUrl('http://gitlab.example.com:80')).toBe(
      'http://gitlab.example.com',
    );
    expect(
      formContextKey({ ...context, gitlabBaseUrl: 'https://gitlab.example.com/' }),
    ).toBe(contextKey);
    expect(formContextKey({ ...context, templateKey: 'issue_response' })).not.toBe(contextKey);
  });
});
