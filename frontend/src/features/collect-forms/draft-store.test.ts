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

  it('keeps a valid v2 draft when a stale v1 entry shares its identity', () => {
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(
      v1Key,
      JSON.stringify({
        ...draft({
          fields: { fixStatus: 'stale-v1' },
          requestKey: 'older-key',
          submitState: 'none',
          inputRevision: 1,
          persistedRevision: 1,
        }),
        draftSpecVersion: 1,
      }),
    );
    localStorage.setItem(
      v2Key,
      JSON.stringify(
        draft({
          draftSpecVersion: 2,
          fields: { fixStatus: 'newer-v2' },
          requestKey: 'newer-key',
          submitState: 'unknown',
          inputRevision: 5,
          persistedRevision: 5,
        }),
      ),
    );

    const drafts = readFormDrafts('alice', contextKey);
    expect(drafts).toHaveLength(1);
    expect(drafts[0].draftSpecVersion).toBe(2);
    expect(drafts[0].fields).toEqual({ fixStatus: 'newer-v2' });
    expect(drafts[0].requestKey).toBe('newer-key');
    expect(drafts[0].submitState).toBe('unknown');
    expect(drafts[0].inputRevision).toBe(5);
    expect(localStorage.getItem(v1Key)).toBeNull();
    expect(localStorage.getItem(v2Key)).toContain('newer-v2');

    // 旧标签页重新写入 v1 后，再次读取仍以有效 v2 为准。
    localStorage.setItem(
      v1Key,
      JSON.stringify({
        ...draft({ fields: { fixStatus: 'rewritten-v1' }, requestKey: 'rewritten-key' }),
        draftSpecVersion: 1,
      }),
    );
    const reread = readFormDrafts('alice', contextKey);
    expect(reread[0].fields).toEqual({ fixStatus: 'newer-v2' });
    expect(reread[0].requestKey).toBe('newer-key');
  });

  it('falls back to v1 only when the v2 entry is unreadable', () => {
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(
      v1Key,
      JSON.stringify({
        ...draft({ fields: { fixStatus: 'legacy' }, requestKey: 'legacy-key' }),
        draftSpecVersion: 1,
      }),
    );
    localStorage.setItem(v2Key, '{broken');

    const [migrated] = readFormDrafts('alice', contextKey);
    expect(migrated.draftSpecVersion).toBe(2);
    expect(migrated.fields).toEqual({ fixStatus: 'legacy' });
    expect(migrated.requestKey).toBe('legacy-key');
    expect(localStorage.getItem(v1Key)).toBeNull();
    expect(JSON.parse(localStorage.getItem(v2Key)!)).toMatchObject({ draftSpecVersion: 2 });
  });

  it('does not migrate drafts that belong to another account', () => {
    const bobV1Key = [
      'qaflex.form.draft.v1',
      encodeURIComponent('bob'),
      encodeURIComponent(contextKey),
      'session-b',
      'draft-b',
    ].join('|');
    const bobV2Key = bobV1Key.replace('qaflex.form.draft.v1', 'qaflex.form.draft.v2');
    localStorage.setItem(
      bobV1Key,
      JSON.stringify({
        ...draft({ accountUserId: 'bob', editingSessionId: 'session-b', draftId: 'draft-b' }),
        draftSpecVersion: 1,
      }),
    );

    expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
    expect(localStorage.getItem(bobV1Key)).toBeTruthy();
    expect(localStorage.getItem(bobV2Key)).toBeNull();
  });

  it('keeps the v2 draft authoritative when the v1 cleanup fails', () => {
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(
      v1Key,
      JSON.stringify({ ...draft({ fields: { fixStatus: 'stale' }, requestKey: 'old-key' }), draftSpecVersion: 1 }),
    );
    const originalRemove = Storage.prototype.removeItem;
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(function (
      this: Storage,
      key: string,
    ) {
      if (key === v1Key) {
        throw new Error('denied');
      }
      return originalRemove.call(this, key);
    });

    const saved = saveFormDraft(
      draft({ fields: { fixStatus: 'newest' }, requestKey: 'new-key', inputRevision: 6, persistedRevision: 6 }),
    );
    expect(saved).not.toBeNull();
    expect(localStorage.getItem(v2Key)).toContain('new-key');

    const reread = readFormDrafts('alice', contextKey);
    expect(reread[0].fields).toEqual({ fixStatus: 'newest' });
    expect(reread[0].requestKey).toBe('new-key');
  });

  it('keeps the v1 entry when writing the v2 key fails', () => {
    const v1Key = draftStorageKey('qaflex.form.draft.v1');
    const v2Key = draftStorageKey('qaflex.form.draft.v2');
    localStorage.setItem(
      v1Key,
      JSON.stringify({ ...draft({ fields: { fixStatus: 'legacy' } }), draftSpecVersion: 1 }),
    );
    const originalSet = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string,
    ) {
      if (key === v2Key) {
        throw new Error('quota');
      }
      return originalSet.call(this, key, value);
    });

    const [pending] = readFormDrafts('alice', contextKey);
    expect(pending.draftSpecVersion).toBe(1);
    expect(pending.fields).toEqual({ fixStatus: 'legacy' });
    expect(localStorage.getItem(v1Key)).toBeTruthy();
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
