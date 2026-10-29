import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpRequestError } from '../../api-client/request';
import type {
  FormCreateResponse,
  FormDetailResponse,
  FormRecordView,
  FormTemplateSpecView,
} from '../../types/api';
import {
  formContextKey,
  readFormDrafts,
  readSessionDrafts,
  saveFormDraft,
  type FormFillContext,
  type FormStorageSignal,
} from './draft-store';
import {
  FormFillingSession,
  type FormApiLike,
  type FormBlockState,
  type FormSessionDialogs,
} from './form-filling-session';

const codeReviewSpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'code_review',
  fields: [
    { name: 'reviewer_user_id', apiKey: 'reviewerUserId', label: '走查人', type: 'string', control: 'person-select', required: true, maxLength: 128 },
    { name: 'review_duration_minutes', apiKey: 'reviewDurationMinutes', label: '走查时间（分钟）', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'specification_count', apiKey: 'specificationCount', label: '规范', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'logic_count', apiKey: 'logicCount', label: '逻辑', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'performance_count', apiKey: 'performanceCount', label: '性能', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'design_count', apiKey: 'designCount', label: '设计', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'other_count', apiKey: 'otherCount', label: '其他', type: 'integer', required: true, min: 0, max: 2147483647 },
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [],
  dictionaries: [],
};

const issueReplySpec: FormTemplateSpecView = {
  spec_version: 1,
  template_key: 'issue_reply',
  fields: [
    {
      name: 'affected_kinds',
      apiKey: 'affectedKinds',
      label: '修改该缺陷可能影响的功能',
      type: 'enum-array',
      control: 'checkbox',
      required: true,
      options: [
        { value: 'KNOWN', label: '已知的受影响功能' },
        { value: 'NEW', label: '新识别的受影响功能' },
        { value: 'NONE', label: '无' },
      ],
    },
    {
      name: 'known_affected_functions',
      apiKey: 'knownAffectedFunctions',
      label: '已知的受影响功能说明',
      type: 'string',
      control: 'textarea',
      required: false,
      requiredWhen: { field: 'affected_kinds', contains: 'KNOWN' },
      clearWhen: { field: 'affected_kinds', notContains: 'KNOWN' },
      maxLength: 8192,
    },
    { name: 'supplement_note', apiKey: 'supplementNote', label: '补充说明', type: 'string', control: 'textarea', required: false, maxLength: 8192 },
  ],
  conditions: [
    { type: 'mutuallyExclusive', field: 'affected_kinds', whenContains: 'NONE', withAnyOf: ['KNOWN', 'NEW'] },
    { type: 'conditionalRequired', target: 'known_affected_functions', requiredWhen: { field: 'affected_kinds', contains: 'KNOWN' }, clearWhen: { field: 'affected_kinds', notContains: 'KNOWN' } },
  ],
  dictionaries: [],
};

const context: FormFillContext = {
  gitlabBaseUrl: 'https://gitlab.example.com',
  projectId: 7,
  resourceType: 'merge_request',
  resourceId: '3',
  templateKey: 'code_review',
};
const contextKey = formContextKey(context);

const codeReviewFields = {
  reviewerUserId: 'alice',
  reviewDurationMinutes: 30,
  specificationCount: 1,
  logicCount: 2,
  performanceCount: 3,
  designCount: 4,
  otherCount: 5,
  supplementNote: null,
};

function record(overrides: Partial<FormRecordView> = {}): FormRecordView {
  return {
    id: '101',
    recordVersion: 1,
    deleted: false,
    legacyIncomplete: false,
    legacyMissingFields: [],
    fields: { ...codeReviewFields },
    fieldNames: { reviewerUserId: 'Alice' },
    snapshotValues: {},
    createdBy: 'alice',
    createdByName: 'Alice',
    templateVersion: 'v1',
    originVersionId: '22',
    storageVersionId: '22',
    createdAt: '2026-10-23T01:00:00.000Z',
    updatedAt: '2026-10-23T01:00:00.000Z',
    canEdit: true,
    canVoid: true,
    ...overrides,
  };
}

function detail(
  records: FormRecordView[] = [],
  overrides: Partial<FormDetailResponse> = {},
): FormDetailResponse {
  return {
    templateKey: 'code_review',
    fieldSpec: codeReviewSpec,
    scopeId: '11',
    currentVersionId: '22',
    storageVersionId: '22',
    versionLabel: 'v1',
    scopeStatus: 'ACTIVE',
    writable: true,
    instanceId: '31',
    readableVersions: [],
    versionSpecs: { '22': codeReviewSpec },
    sourceSetRevision: '0',
    sourceSetHash: '0'.repeat(64),
    records,
    resource: {
      gitlabBaseUrl: 'https://gitlab.example.com',
      projectId: '7',
      resourceType: 'merge_request',
      resourceId: '3',
      rootId: '41',
      webUrl: 'https://gitlab.example.com/group/project/-/merge_requests/3',
      severityLevel: null,
    },
    account: { userId: 'alice', displayName: 'Alice' },
    capabilities: { canCreate: true, canEditOwn: true, canVoidOwn: true },
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

interface Harness {
  session: FormFillingSession;
  api: {
    getFormDetail: ReturnType<typeof vi.fn>;
    createFormRecord: ReturnType<typeof vi.fn>;
    updateFormRecord: ReturnType<typeof vi.fn>;
    voidFormRecord: ReturnType<typeof vi.fn>;
  };
  dialogs: FormSessionDialogs;
  identity: { generation: number };
}

const sessions: FormFillingSession[] = [];

function harness(options: {
  apiDetail?: FormDetailResponse;
  account?: string;
  editingSession?: string;
  templateKey?: string;
  spec?: FormTemplateSpecView;
  dialogs?: Partial<FormSessionDialogs>;
} = {}): Harness {
  const api = {
    getFormDetail: vi.fn(),
    createFormRecord: vi.fn(),
    updateFormRecord: vi.fn(),
    voidFormRecord: vi.fn(),
  };
  const currentDetail = options.apiDetail ?? detail();
  api.getFormDetail.mockResolvedValue(currentDetail);

  const identity = { generation: 1 };
  const dialogs: FormSessionDialogs = {
    confirmClearFields: vi.fn(async () => true),
    confirmDiscardBlock: vi.fn(async () => true),
    confirmVoidRecord: vi.fn(async () => '作废'),
    confirmSameReviewer: vi.fn(async () => true),
    confirmAcceptLatest: vi.fn(async () => true),
    notify: vi.fn(),
    focusField: vi.fn(),
    ...options.dialogs,
  };
  const sessionContext: FormFillContext = options.templateKey
    ? { ...context, resourceType: 'issue', templateKey: options.templateKey }
    : context;
  let sequence = 0;
  const session = new FormFillingSession({
    context: sessionContext,
    accountUserId: options.account ?? 'alice',
    editingSessionId: options.editingSession ?? 'session-a',
    identityGeneration: () => identity.generation,
    api: api as unknown as FormApiLike,
    dialogs,
    now: () => new Date('2026-10-23T02:00:00.000Z'),
    createId: () => `id-${++sequence}`,
  });
  sessions.push(session);
  return { session, api, dialogs, identity };
}

async function fillValid(session: FormFillingSession, block: FormBlockState) {
  await session.changeField(block, 'reviewerUserId', 'alice');
  await session.changeField(block, 'reviewDurationMinutes', 30);
  await session.changeField(block, 'specificationCount', 1);
  await session.changeField(block, 'logicCount', 2);
  await session.changeField(block, 'performanceCount', 3);
  await session.changeField(block, 'designCount', 4);
  await session.changeField(block, 'otherCount', 5);
}

function readSaveSignal(): FormStorageSignal {
  const raw = localStorage.getItem('qaflex.form.draft-save-outcome');
  expect(raw).toBeTruthy();
  return JSON.parse(raw as string) as FormStorageSignal;
}

function applySaveSignal(session: FormFillingSession, signal: FormStorageSignal): void {
  session.applyRemoteSaveOutcome({
    clearedDraftIds: signal.draftIds,
    submittedFields: signal.submittedFields ?? null,
    record:
      signal.recordId && signal.recordVersion != null
        ? { recordId: signal.recordId, recordVersion: signal.recordVersion }
        : null,
    savedElsewhere: signal.savedElsewhere ?? [],
  });
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

afterEach(() => {
  for (const session of sessions.splice(0)) {
    session.dispose();
  }
  vi.useRealTimers();
  localStorage.clear();
  sessionStorage.clear();
});

describe('form filling session', () => {
  it('keeps drafts and saved records independent for two concurrent users', async () => {
    const alice = harness({ account: 'alice', editingSession: 'session-alice' });
    const bob = harness({ account: 'bob', editingSession: 'session-bob' });
    await alice.session.initialize();
    await bob.session.initialize();

    const aliceBlock = alice.session.addCreateBlock()!;
    const bobBlock = bob.session.addCreateBlock()!;
    expect(aliceBlock).toBeTruthy();
    expect(bobBlock).toBeTruthy();
    await alice.session.changeField(aliceBlock, 'reviewerUserId', 'alice');
    await bob.session.changeField(bobBlock, 'reviewerUserId', 'bob');
    alice.session.flushDrafts();
    bob.session.flushDrafts();

    expect(readSessionDrafts('alice', contextKey, 'session-alice')).toHaveLength(1);
    expect(readSessionDrafts('bob', contextKey, 'session-bob')).toHaveLength(1);
    expect(readSessionDrafts('alice', contextKey, 'session-bob')).toHaveLength(0);

    const saved = record({ id: '201' });
    alice.api.createFormRecord.mockResolvedValueOnce({
      outcome: 'CREATED',
      record: saved,
      reviewerUserId: null,
    } satisfies FormCreateResponse);
    alice.api.getFormDetail.mockResolvedValue(detail([saved]));

    await fillValid(alice.session, aliceBlock);
    await alice.session.submit(aliceBlock);

    expect(alice.session.state.records.map((item) => item.id)).toEqual(['201']);
    expect(alice.session.state.blocks).toHaveLength(0);
    expect(bob.session.state.blocks).toHaveLength(1);
    expect(readSessionDrafts('bob', contextKey, 'session-bob')).toHaveLength(1);
  });

  it('restores a clone in a second tab while the first session keeps its own draft', async () => {
    const first = harness({ editingSession: 'session-a' });
    await first.session.initialize();
    const block = first.session.addCreateBlock()!;
    expect(block.key).toBe(block.draftId);
    await first.session.changeField(block, 'reviewerUserId', 'alice');
    first.session.flushDrafts();
    const originalRequestKey = block.requestKey;

    const second = harness({ editingSession: 'session-b' });
    await second.session.initialize();
    expect(second.session.state.blocks).toHaveLength(1);
    const restored = second.session.state.blocks[0];
    expect(restored.key).toBe(block.key);
    expect(restored.draftId).toBe(block.draftId);
    expect(restored.requestKey).toBe(originalRequestKey);
    expect(restored.restored).toBe(true);

    await second.session.changeField(restored, 'supplementNote', '标签页 B 的内容');
    second.session.flushDrafts();

    expect(readSessionDrafts('alice', contextKey, 'session-a')[0].fields.supplementNote).toBeNull();
    expect(readSessionDrafts('alice', contextKey, 'session-b')[0].fields.supplementNote).toBe(
      '标签页 B 的内容',
    );
  });

  it('restores an unresolved submission with its original request key and payload', async () => {
    const first = harness({ editingSession: 'session-a' });
    await first.session.initialize();
    const block = first.session.addCreateBlock()!;
    await fillValid(first.session, block);
    first.api.createFormRecord.mockRejectedValueOnce(new HttpRequestError('服务暂不可用', 503));

    await first.session.submit(block);
    expect(block.submitState).toBe('unknown');
    expect(block.requestKey).toBeTruthy();
    expect(block.firstPendingSnapshot?.requestKey).toBe(block.requestKey);

    const second = harness({ editingSession: 'session-b' });
    await second.session.initialize();
    expect(second.session.state.blocks).toHaveLength(1);
    const restored = second.session.state.blocks[0];
    expect(restored.submitState).toBe('unknown');
    expect(restored.frozen).toBe(true);
    expect(restored.requestKey).toBe(block.requestKey);
    expect(restored.firstPendingSnapshot?.requestKey).toBe(block.requestKey);
    expect(restored.submitSnapshot?.fields.reviewerUserId).toBe('alice');
    expect(second.session.canCopyBlock(restored)).toBe(false);
    expect(second.session.copyBlock(restored)).toBeNull();
    expect(second.session.state.blocks).toHaveLength(1);
  });

  it('reports draft save state for the current revision', async () => {
    vi.useFakeTimers();
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await current.session.changeField(block, 'reviewerUserId', 'alice');
    expect(block.saveState).toBe('saving');
    vi.advanceTimersByTime(400);
    expect(block.saveState).toBe('saved');
    expect(block.savedAt).toBeTruthy();

    await current.session.changeField(block, 'reviewerUserId', 'bob');
    expect(block.saveState).toBe('saving');
    current.session.flushDrafts();
    expect(block.saveState).toBe('saved');
    expect(readSessionDrafts('alice', contextKey, 'session-a')[0].fields.reviewerUserId).toBe('bob');
  });

  it('ignores a late detail response that belongs to an older request', async () => {
    const current = harness();
    const first = deferred<FormDetailResponse>();
    const second = deferred<FormDetailResponse>();
    current.api.getFormDetail
      .mockReset()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const initialization = current.session.initialize();
    const refresh = current.session.reload();
    second.resolve(detail([record({ id: '202' })]));
    await refresh;
    expect(current.session.state.records.map((item) => item.id)).toEqual(['202']);

    first.resolve(detail([record({ id: '101' })]));
    await initialization;
    expect(current.session.state.records.map((item) => item.id)).toEqual(['202']);
  });

  it('submits only the selected block and keeps the other draft intact', async () => {
    const current = harness();
    await current.session.initialize();
    const first = current.session.addCreateBlock()!;
    await fillValid(current.session, first);
    const second = current.session.addCreateBlock()!;
    expect(first.key).not.toBe(second.key);
    await current.session.changeField(second, 'reviewerUserId', 'bob');
    current.session.flushDrafts();

    const saved = record({ id: '301' });
    const serverRecords: FormRecordView[] = [];
    current.api.createFormRecord.mockImplementationOnce(async (payload: { fields: Record<string, unknown> }) => {
      serverRecords.push({ ...saved, fields: payload.fields });
      return { outcome: 'CREATED', record: serverRecords[0], reviewerUserId: null };
    });
    current.api.getFormDetail.mockImplementation(async () => detail([...serverRecords]));

    await current.session.submit(first);

    expect(current.session.state.blocks.map((block) => block.key)).toEqual([second.key]);
    expect(current.session.state.records.map((item) => item.id)).toEqual(['301']);
    const remaining = readSessionDrafts('alice', contextKey, 'session-a');
    expect(remaining).toHaveLength(1);
    expect(remaining[0].fields.reviewerUserId).toBe('bob');
  });

  it('keeps the saved record when the post-save list refresh fails', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await fillValid(current.session, block);

    const saved = record({ id: '401' });
    current.api.createFormRecord.mockResolvedValueOnce({
      outcome: 'CREATED',
      record: saved,
      reviewerUserId: null,
    } satisfies FormCreateResponse);
    current.api.getFormDetail.mockRejectedValueOnce(new Error('network down'));

    await current.session.submit(block);

    expect(current.session.state.records.map((item) => item.id)).toEqual(['401']);
    expect(current.session.state.blocks).toHaveLength(0);
    expect(current.session.state.refreshError).toBe('记录已保存，列表刷新失败，可单独重试');
  });

  it('replays the same request key and payload after same-reviewer confirmation', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await fillValid(current.session, block);
    const requestKey = block.requestKey;

    current.api.createFormRecord
      .mockResolvedValueOnce({
        outcome: 'CONFIRM_REQUIRED',
        record: null,
        reviewerUserId: 'alice',
      } satisfies FormCreateResponse)
      .mockResolvedValueOnce({
        outcome: 'CREATED',
        record: record({ id: '501' }),
        reviewerUserId: 'alice',
      } satisfies FormCreateResponse);
    current.api.getFormDetail.mockResolvedValue(detail([record({ id: '501' })]));

    await current.session.submit(block);

    expect(current.api.createFormRecord).toHaveBeenCalledTimes(2);
    const firstPayload = current.api.createFormRecord.mock.calls[0][0];
    const secondPayload = current.api.createFormRecord.mock.calls[1][0];
    expect(firstPayload.requestKey).toBe(requestKey);
    expect(secondPayload.requestKey).toBe(requestKey);
    expect(secondPayload.fields).toEqual(firstPayload.fields);
    expect(firstPayload.confirmSameReviewer).toBe(false);
    expect(secondPayload.confirmSameReviewer).toBe(true);
    expect(current.session.state.blocks).toHaveLength(0);
  });

  it('keeps the draft and releases the block when same-reviewer confirmation is cancelled', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await fillValid(current.session, block);
    current.api.createFormRecord.mockResolvedValueOnce({
      outcome: 'CONFIRM_REQUIRED',
      record: null,
      reviewerUserId: 'alice',
    } satisfies FormCreateResponse);
    (current.dialogs.confirmSameReviewer as ReturnType<typeof vi.fn>).mockResolvedValueOnce(false);

    await current.session.submit(block);

    expect(current.api.createFormRecord).toHaveBeenCalledTimes(1);
    expect(block.submitState).toBe('none');
    expect(block.frozen).toBe(false);
    expect(block.submitSnapshot).toBeNull();
    expect(current.session.state.blocks).toHaveLength(1);
    expect(readSessionDrafts('alice', contextKey, 'session-a')).toHaveLength(1);
  });

  it('verifies an unknown create by replaying the frozen snapshot', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await fillValid(current.session, block);
    const requestKey = block.requestKey;

    current.api.createFormRecord
      .mockRejectedValueOnce(new HttpRequestError('timeout', 504))
      .mockResolvedValueOnce({
        outcome: 'REPLAYED',
        record: record({ id: '601' }),
        reviewerUserId: null,
      } satisfies FormCreateResponse);
    current.api.getFormDetail.mockResolvedValue(detail([record({ id: '601' })]));

    await current.session.submit(block);
    expect(block.submitState).toBe('unknown');
    const stored = readSessionDrafts('alice', contextKey, 'session-a')[0];
    expect(stored.submitState).toBe('unknown');

    await current.session.verify(block);
    expect(current.api.createFormRecord).toHaveBeenCalledTimes(2);
    expect(current.api.createFormRecord.mock.calls[1][0].requestKey).toBe(requestKey);
    expect(current.api.createFormRecord.mock.calls[1][0].fields).toEqual(
      current.api.createFormRecord.mock.calls[0][0].fields,
    );
    expect(current.session.state.blocks).toHaveLength(0);
    expect(current.session.state.records.map((item) => item.id)).toEqual(['601']);
  });

  it('verifies an unknown edit as saved when the record version advanced with the submitted fields', async () => {
    const base = record({ id: '701', recordVersion: 3, fields: { ...codeReviewFields, supplementNote: '原值' } });
    const serverRecords = [base];
    const current = harness({ apiDetail: detail(serverRecords) });
    await current.session.initialize();
    const block = current.session.startEdit(base)!;
    await current.session.changeField(block, 'supplementNote', '新值');
    current.api.updateFormRecord.mockRejectedValueOnce(new HttpRequestError('timeout', 504));
    current.api.getFormDetail.mockImplementation(async () => detail([...serverRecords]));

    await current.session.submit(block);
    expect(block.submitState).toBe('unknown');
    expect(block.submitSnapshot?.baseRecordVersion).toBe(3);

    serverRecords[0] = record({
      id: '701',
      recordVersion: 4,
      fields: { ...block.submitSnapshot!.fields },
    });
    await current.session.verify(block);

    expect(current.session.state.blocks).toHaveLength(0);
    expect(current.session.state.records[0].recordVersion).toBe(4);
  });

  it('keeps the draft and exposes authority values when an edit conflict is detected', async () => {
    const base = record({ id: '801', recordVersion: 3, fields: { ...codeReviewFields, supplementNote: '原值' } });
    const serverRecords = [base];
    const current = harness({ apiDetail: detail(serverRecords) });
    await current.session.initialize();
    const block = current.session.startEdit(base)!;
    expect(block.key).toBe(block.draftId);
    await current.session.changeField(block, 'supplementNote', '我的内容');
    current.api.updateFormRecord.mockRejectedValueOnce(
      new HttpRequestError('数据已被他人修改', 409, 'A0409'),
    );
    serverRecords[0] = record({
      id: '801',
      recordVersion: 4,
      fields: { ...codeReviewFields, supplementNote: '他人内容' },
    });
    current.api.getFormDetail.mockImplementation(async () => detail([...serverRecords]));

    await current.session.submit(block);

    expect(block.submitState).toBe('conflict');
    expect(block.frozen).toBe(false);
    expect(block.conflictRecord?.recordVersion).toBe(4);
    expect(block.fields.supplementNote).toBe('我的内容');
    expect(current.session.canSubmitBlock(block)).toBe(false);
    expect(current.session.state.blocks).toHaveLength(1);
  });

  it('renders and edits each record against its own storage version spec', async () => {
    const v2Spec: FormTemplateSpecView = {
      spec_version: 2,
      template_key: 'code_review',
      fields: [
        ...codeReviewSpec.fields.filter((field) => field.name !== 'other_count'),
        { name: 'security_count', apiKey: 'securityCount', label: '安全', type: 'integer', required: true, min: 0, max: 2147483647 },
      ],
      conditions: [],
      dictionaries: [],
    };
    const base = record({ id: '301', storageVersionId: '22' });
    const oldRecord = record({
      id: '302',
      originVersionId: '22',
      storageVersionId: '33',
      fields: {
        reviewerUserId: 'alice',
        reviewDurationMinutes: 30,
        specificationCount: 1,
        logicCount: 2,
        performanceCount: 3,
        designCount: 4,
        securityCount: 6,
      },
      fieldNames: { reviewerUserId: 'Alice' },
    });
    const overrides: Partial<FormDetailResponse> = {
      currentVersionId: '22',
      storageVersionId: '22',
      readableVersions: [
        { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        { versionId: '33', versionLabel: 'v2', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'b'.repeat(64) },
      ],
      versionSpecs: { '22': codeReviewSpec, '33': v2Spec },
    };
    const current = harness({ apiDetail: detail([base, oldRecord], overrides) });
    await current.session.initialize();

    expect(current.session.specForRecord(base)).toEqual(codeReviewSpec);
    expect(current.session.specForRecord(oldRecord)).toEqual(v2Spec);
    // 原版记录按自身 v2 规格保留原值，新增字段不回填到 v1 记录。
    const block = current.session.startEdit(oldRecord)!;
    expect(block.storageVersionId).toBe('33');
    expect(block.originVersionId).toBe('22');
    expect(block.versionSpecHash).toBe('b'.repeat(64));
    expect(block.fields.securityCount).toBe(6);
    expect(block.fields.otherCount).toBeUndefined();

    await current.session.changeField(block, 'securityCount', 7);
    const updated = record({
      id: '302',
      originVersionId: '22',
      storageVersionId: '33',
      recordVersion: 2,
      fields: { ...oldRecord.fields, securityCount: 7 },
      fieldNames: { reviewerUserId: 'Alice' },
    });
    current.api.updateFormRecord.mockResolvedValueOnce(updated);
    current.api.getFormDetail.mockImplementation(async () => detail([base, updated], overrides));
    await current.session.submit(block);
    expect(current.api.updateFormRecord).toHaveBeenCalledWith('302', expect.objectContaining({
      storageVersionId: '33',
      expectedStorageVersionId: '33',
      recordVersion: 1,
      fields: expect.objectContaining({ securityCount: 7 }),
    }));

    current.api.voidFormRecord.mockResolvedValueOnce(
      record({ id: '302', storageVersionId: '33', recordVersion: 3, deleted: true }),
    );
    current.api.getFormDetail.mockImplementation(async () => detail([base], overrides));
    const voidAccepted = await current.session.voidRecord(
      current.session.state.records.find((item) => item.id === '302')!,
    );
    expect(voidAccepted).toBe(true);
    expect(current.api.voidFormRecord).toHaveBeenCalledWith('302', expect.objectContaining({
      storageVersionId: '33',
      expectedStorageVersionId: '33',
      recordVersion: 2,
    }));
    expect(current.session.state.records.map((item) => item.id)).toEqual(['301']);
  });

  it('locks an edit block when its storage version spec hash changes', async () => {
    const base = record({ id: '301', storageVersionId: '22' });
    const overrides: Partial<FormDetailResponse> = {
      currentVersionId: '22',
      storageVersionId: '22',
      readableVersions: [
        { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
      ],
      versionSpecs: { '22': codeReviewSpec },
    };
    const current = harness({ apiDetail: detail([base], overrides) });
    await current.session.initialize();
    const block = current.session.startEdit(current.session.state.records[0])!;
    expect(block.readOnly).toBe(false);
    expect(block.versionSpecHash).toBe('a'.repeat(64));
    const originalFields = { ...block.fields };

    current.api.getFormDetail.mockResolvedValueOnce(
      detail([base], {
        ...overrides,
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'c'.repeat(64) },
        ],
      }),
    );
    await current.session.reload();

    expect(block.structureStale).toBe(true);
    expect(block.readOnly).toBe(true);
    expect(block.fields).toEqual(originalFields);
    expect(current.session.canSubmitBlock(block)).toBe(false);
  });

  it('does not fall back to the current spec when a record version spec is missing', async () => {
    const orphan = record({ id: '303', storageVersionId: '77', originVersionId: '22', canEdit: true });
    const current = harness({
      apiDetail: detail([orphan], {
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
      }),
    });
    await current.session.initialize();

    expect(current.session.specForRecord(orphan)).toBeNull();
    expect(current.session.startEdit(orphan)).toBeNull();
  });

  it('keeps a restored draft read-only when its storage version spec is unavailable', async () => {
    const draftContext = { ...context, scopeId: '11', storageVersionId: '77' };
    saveFormDraft({
      draftSpecVersion: 2,
      originVersionId: '22',
      baseStorageVersionId: '77',
      versionSpecHash: 'b'.repeat(64),
      accountUserId: 'alice',
      contextKey,
      context: draftContext,
      draftId: 'draft-missing-spec',
      editingSessionId: 'session-a',
      kind: 'edit',
      targetRecordId: '303',
      baseRecordVersion: 3,
      baseFields: { reviewerUserId: 'alice', reviewDurationMinutes: 30 },
      fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
      requestKey: null,
      firstPendingSnapshot: null,
      inputRevision: 2,
      persistedRevision: 2,
      savedAt: '2026-10-23T01:00:00.000Z',
      status: 'editing',
      submitState: 'unknown',
      submitSnapshot: null,
      savedElsewhere: null,
      updatedAt: '2026-10-23T01:00:00.000Z',
    });
    const orphan = record({ id: '303', storageVersionId: '77', originVersionId: '22', canEdit: true });
    const current = harness({
      apiDetail: detail([orphan], {
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
      }),
    });
    await current.session.initialize();

    const block = current.session.state.blocks.find((item) => item.draftId === 'draft-missing-spec')!;
    expect(block).toBeTruthy();
    expect(block.structureStale).toBe(true);
    expect(block.readOnly).toBe(true);
    expect(block.submitState).toBe('unknown');
    expect(block.fields).toEqual({ reviewerUserId: 'alice', reviewDurationMinutes: 45 });
  });

  it('keeps an unknown edit unresolved instead of reporting a false conflict when its spec is missing', async () => {
    const draftContext = { ...context, scopeId: '11', storageVersionId: '77' };
    saveFormDraft({
      draftSpecVersion: 2,
      originVersionId: '22',
      baseStorageVersionId: '77',
      versionSpecHash: 'b'.repeat(64),
      accountUserId: 'alice',
      contextKey,
      context: draftContext,
      draftId: 'draft-unknown-missing-spec',
      editingSessionId: 'session-a',
      kind: 'edit',
      targetRecordId: '303',
      baseRecordVersion: 3,
      baseFields: { reviewerUserId: 'alice', reviewDurationMinutes: 30 },
      fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
      requestKey: null,
      firstPendingSnapshot: null,
      inputRevision: 2,
      persistedRevision: 2,
      savedAt: '2026-10-23T01:00:00.000Z',
      status: 'editing',
      submitState: 'unknown',
      submitSnapshot: {
        requestKey: null,
        fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
        confirmSameReviewer: false,
        baseRecordVersion: 3,
        capturedAt: '2026-10-23T01:00:00.000Z',
      },
      savedElsewhere: null,
      updatedAt: '2026-10-23T01:00:00.000Z',
    });
    const orphan = record({
      id: '303',
      recordVersion: 4,
      storageVersionId: '77',
      originVersionId: '22',
      canEdit: true,
      fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
    });
    const current = harness({
      apiDetail: detail([orphan], {
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
      }),
    });
    await current.session.initialize();

    const block = current.session.state.blocks.find(
      (item) => item.draftId === 'draft-unknown-missing-spec',
    )!;
    expect(block.submitState).toBe('unknown');
    expect(block.structureStale).toBe(true);

    await current.session.verify(block);

    expect(block.submitState).toBe('unknown');
    expect(block.conflictRecord).toBeNull();
    expect(block.notice).toContain('结构版本当前不可用');
    expect(block.submitSnapshot).not.toBeNull();
    expect(current.session.canDiscardBlock(block)).toBe(false);
    expect(current.session.canCopyBlock(block)).toBe(false);
  });

  it('converges the same unknown edit snapshot after its spec is restored', async () => {
    const draftContext = { ...context, scopeId: '11', storageVersionId: '77' };
    saveFormDraft({
      draftSpecVersion: 2,
      originVersionId: '22',
      baseStorageVersionId: '77',
      versionSpecHash: 'b'.repeat(64),
      accountUserId: 'alice',
      contextKey,
      context: draftContext,
      draftId: 'draft-unknown-restored-spec',
      editingSessionId: 'session-a',
      kind: 'edit',
      targetRecordId: '303',
      baseRecordVersion: 3,
      baseFields: { reviewerUserId: 'alice', reviewDurationMinutes: 30 },
      fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
      requestKey: null,
      firstPendingSnapshot: null,
      inputRevision: 2,
      persistedRevision: 2,
      savedAt: '2026-10-23T01:00:00.000Z',
      status: 'editing',
      submitState: 'unknown',
      submitSnapshot: {
        requestKey: null,
        fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
        confirmSameReviewer: false,
        baseRecordVersion: 3,
        capturedAt: '2026-10-23T01:00:00.000Z',
      },
      savedElsewhere: null,
      updatedAt: '2026-10-23T01:00:00.000Z',
    });
    const orphan = record({
      id: '303',
      recordVersion: 4,
      storageVersionId: '77',
      originVersionId: '22',
      canEdit: true,
      fields: { reviewerUserId: 'alice', reviewDurationMinutes: 45 },
    });
    const current = harness({
      apiDetail: detail([orphan], {
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec },
      }),
    });
    await current.session.initialize();
    const block = current.session.state.blocks.find(
      (item) => item.draftId === 'draft-unknown-restored-spec',
    )!;
    expect(block.structureStale).toBe(true);

    current.api.getFormDetail.mockResolvedValueOnce(
      detail([orphan], {
        readableVersions: [
          { versionId: '22', versionLabel: 'v1', versionStatus: 'PUBLISHED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'a'.repeat(64) },
          { versionId: '77', versionLabel: 'v9', versionStatus: 'RETIRED', instanceMode: 'MATERIALIZED', fieldSpecHash: 'b'.repeat(64) },
        ],
        versionSpecs: { '22': codeReviewSpec, '77': codeReviewSpec },
      }),
    );

    await current.session.verify(block);

    expect(current.session.state.blocks.some((item) => item.key === block.key)).toBe(false);
    expect(current.session.state.notice).toBe('记录已保存');
    expect(readSessionDrafts('alice', contextKey, 'session-a')).toHaveLength(0);
  });

  it('does not write a late create result after the identity generation changes', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await fillValid(current.session, block);
    const pending = deferred<FormCreateResponse>();
    current.api.createFormRecord.mockReturnValueOnce(pending.promise);

    const submission = current.session.submit(block);
    expect(block.submitState).toBe('pending');
    current.identity.generation = 2;
    pending.resolve({ outcome: 'CREATED', record: record({ id: '901' }), reviewerUserId: null });
    await submission;

    expect(current.session.state.records).toHaveLength(0);
    expect(current.session.state.blocks).toHaveLength(1);
    expect(block.submitState).toBe('unknown');
    expect(block.notice).toBe('账号身份已变化，提交结果待确认');
  });

  it('protects leave only for real unsubmitted content or unresolved results', async () => {
    const base = record({ id: '1001', recordVersion: 2, fields: { ...codeReviewFields, supplementNote: '原值' } });
    const current = harness({ apiDetail: detail([base]) });
    await current.session.initialize();

    const create = current.session.addCreateBlock()!;
    expect(current.session.hasUnsubmitted()).toBe(false);
    await current.session.changeField(create, 'reviewerUserId', 'alice');
    expect(current.session.hasUnsubmitted()).toBe(true);
    await current.session.changeField(create, 'reviewerUserId', '');
    expect(current.session.hasUnsubmitted()).toBe(false);

    const edit = current.session.startEdit(base)!;
    expect(current.session.hasUnsubmitted()).toBe(false);
    await current.session.changeField(edit, 'supplementNote', '改过的值');
    expect(current.session.hasUnsubmitted()).toBe(true);
    expect(current.session.unsubmittedBlocks().map((block) => block.key)).toEqual([edit.key]);
    await current.session.changeField(edit, 'supplementNote', '原值');
    expect(current.session.hasUnsubmitted()).toBe(false);
  });

  it('confirms clearing dependent input and keeps state when cancelled', async () => {
    const issueDetail = detail([], {
      templateKey: 'issue_reply',
      fieldSpec: issueReplySpec,
      resource: {
        gitlabBaseUrl: 'https://gitlab.example.com',
        projectId: '7',
        resourceType: 'issue',
        resourceId: '3',
        rootId: '41',
        webUrl: 'https://gitlab.example.com/group/project/-/issues/3',
        severityLevel: null,
      },
    });
    const current = harness({ templateKey: 'issue_reply', apiDetail: issueDetail });
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await current.session.changeField(block, 'affectedKinds', ['KNOWN']);
    await current.session.changeField(block, 'knownAffectedFunctions', '已有说明');
    (current.dialogs.confirmClearFields as ReturnType<typeof vi.fn>).mockResolvedValueOnce(false);

    await current.session.changeField(block, 'affectedKinds', ['NONE']);
    expect(block.fields.affectedKinds).toEqual(['KNOWN']);
    expect(block.fields.knownAffectedFunctions).toBe('已有说明');
    expect(current.dialogs.confirmClearFields).toHaveBeenCalledTimes(1);
    const cleared = (current.dialogs.confirmClearFields as ReturnType<typeof vi.fn>).mock.calls[0][1];
    expect(cleared[0].label).toBe('已知的受影响功能说明');

    await current.session.changeField(block, 'affectedKinds', ['NONE']);
    expect(block.fields.affectedKinds).toEqual(['NONE']);
    expect(block.fields.knownAffectedFunctions).toBeNull();
  });

  it('stops accepting input after a logout signal', async () => {
    const current = harness();
    await current.session.initialize();
    const block = current.session.addCreateBlock()!;
    await current.session.changeField(block, 'reviewerUserId', 'alice');
    current.session.flushDrafts();

    current.session.stopForLogout();

    expect(current.session.state.stopped).toBe(true);
    expect(current.session.state.blocks).toHaveLength(0);
    expect(current.session.addCreateBlock()).toBeNull();
    expect(await current.session.changeField(block, 'reviewerUserId', 'bob')).toBe(false);
    expect(readFormDrafts('alice', contextKey)).toHaveLength(1);
  });

  describe('W3-R01 跨标签页保存结算', () => {
    it('keeps a diverged copy when the other tab saves first', async () => {
      const alice = harness({ editingSession: 'session-a' });
      await alice.session.initialize();
      const block = alice.session.addCreateBlock()!;
      await fillValid(alice.session, block);
      alice.session.flushDrafts();
      const requestKey = block.requestKey;

      const bob = harness({ editingSession: 'session-b' });
      await bob.session.initialize();
      const copy = bob.session.state.blocks[0];
      await bob.session.changeField(copy, 'supplementNote', 'B 标签页的独立修改');
      bob.session.flushDrafts();

      bob.api.createFormRecord.mockResolvedValueOnce({
        outcome: 'CREATED',
        record: record({ id: '210', fields: { ...copy.fields } }),
        reviewerUserId: null,
      });
      await bob.session.submit(copy);

      const kept = readSessionDrafts('alice', contextKey, 'session-a')[0];
      expect(kept.requestKey).toBe(requestKey);
      expect(kept.fields.supplementNote).toBeNull();
      expect(kept.savedElsewhere).toEqual({ recordId: '210', recordVersion: 1 });

      applySaveSignal(alice.session, readSaveSignal());
      expect(alice.session.state.blocks.map((item) => item.key)).toEqual([block.key]);
      expect(block.fields.supplementNote).toBeNull();
      expect(block.requestKey).toBe(requestKey);
      expect(block.savedElsewhere).toEqual({ recordId: '210', recordVersion: 1 });
      expect(block.readOnly).toBe(true);
      expect(alice.session.canSubmitBlock(block)).toBe(false);
      expect(await alice.session.changeField(block, 'reviewerUserId', 'bob')).toBe(false);
      expect(readSessionDrafts('alice', contextKey, 'session-a')[0].savedElsewhere).toEqual({
        recordId: '210',
        recordVersion: 1,
      });

      const copied = alice.session.copyBlock(block)!;
      expect(copied.fields.supplementNote).toBeNull();
      expect(copied.requestKey).not.toBe(requestKey);
    });

    it('keeps diverged input that was still waiting for its debounce when the other tab saved first', async () => {
      const alice = harness({ editingSession: 'session-a' });
      await alice.session.initialize();
      const block = alice.session.addCreateBlock()!;
      await fillValid(alice.session, block);
      alice.session.flushDrafts();

      const bob = harness({ editingSession: 'session-b' });
      await bob.session.initialize();
      const copy = bob.session.state.blocks[0];
      await bob.session.changeField(copy, 'supplementNote', '尚未写入本机的独立修改');
      expect(
        readSessionDrafts('alice', contextKey, 'session-b')[0].fields.supplementNote,
      ).toBeNull();

      alice.api.createFormRecord.mockResolvedValueOnce({
        outcome: 'CREATED',
        record: record({ id: '211', fields: { ...block.fields } }),
        reviewerUserId: null,
      });
      await alice.session.submit(block);

      expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
      const signal = readSaveSignal();
      expect(signal.savedElsewhere).toEqual([]);

      applySaveSignal(bob.session, signal);
      expect(bob.session.state.blocks.map((item) => item.key)).toEqual([copy.key]);
      expect(copy.fields.supplementNote).toBe('尚未写入本机的独立修改');
      expect(copy.readOnly).toBe(true);
      expect(copy.savedElsewhere).toEqual({ recordId: '211', recordVersion: 1 });
      expect(copy.requestKey).toBe(block.requestKey);

      const stored = readSessionDrafts('alice', contextKey, 'session-b')[0];
      expect(stored.fields.supplementNote).toBe('尚未写入本机的独立修改');
      expect(stored.savedElsewhere).toEqual({ recordId: '211', recordVersion: 1 });

      const reopened = harness({ editingSession: 'session-b' });
      await reopened.session.initialize();
      const restored = reopened.session.state.blocks[0];
      expect(restored.fields.supplementNote).toBe('尚未写入本机的独立修改');
      expect(restored.readOnly).toBe(true);
      expect(restored.savedElsewhere).toEqual({ recordId: '211', recordVersion: 1 });
      expect(restored.requestKey).toBe(block.requestKey);
    });

    it('cleans unforked copies in other tabs after a successful save', async () => {
      const alice = harness({ editingSession: 'session-a' });
      await alice.session.initialize();
      const block = alice.session.addCreateBlock()!;
      await fillValid(alice.session, block);
      alice.session.flushDrafts();

      const bob = harness({ editingSession: 'session-b' });
      await bob.session.initialize();
      expect(bob.session.state.blocks).toHaveLength(1);

      alice.api.createFormRecord.mockResolvedValueOnce({
        outcome: 'CREATED',
        record: record({ id: '212', fields: { ...block.fields } }),
        reviewerUserId: null,
      });
      await alice.session.submit(block);

      expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
      const signal = readSaveSignal();
      expect(signal.savedElsewhere).toEqual([]);

      applySaveSignal(bob.session, signal);
      expect(bob.session.state.blocks).toHaveLength(0);
      expect(readFormDrafts('alice', contextKey)).toHaveLength(0);
    });

    it('leaves an in-flight copy untouched and does not create a duplicate record', async () => {
      const alice = harness({ editingSession: 'session-a' });
      await alice.session.initialize();
      const block = alice.session.addCreateBlock()!;
      await fillValid(alice.session, block);
      alice.session.flushDrafts();
      const requestKey = block.requestKey;

      const bob = harness({ editingSession: 'session-b' });
      await bob.session.initialize();
      const copy = bob.session.state.blocks[0];
      const bobPending = deferred<FormCreateResponse>();
      bob.api.createFormRecord.mockReturnValueOnce(bobPending.promise);
      const bobSubmission = bob.session.submit(copy);
      expect(copy.submitState).toBe('pending');
      expect(bob.session.canDiscardBlock(copy)).toBe(false);
      expect(readSessionDrafts('alice', contextKey, 'session-b')[0].submitState).toBe('pending');

      alice.api.createFormRecord.mockResolvedValueOnce({
        outcome: 'CREATED',
        record: record({ id: '213', fields: { ...block.fields } }),
        reviewerUserId: null,
      });
      await alice.session.submit(block);

      const bobDraft = readSessionDrafts('alice', contextKey, 'session-b')[0];
      expect(bobDraft.requestKey).toBe(requestKey);
      expect(bobDraft.fields.reviewerUserId).toBe('alice');
      expect(bobDraft.submitState).toBe('pending');
      expect(bobDraft.savedElsewhere).toBeNull();
      expect(alice.api.createFormRecord).toHaveBeenCalledTimes(1);
      expect(bob.api.createFormRecord).toHaveBeenCalledTimes(1);

      applySaveSignal(bob.session, readSaveSignal());
      expect(bob.session.state.blocks).toHaveLength(1);
      expect(copy.savedElsewhere).toBeNull();

      bobPending.resolve({
        outcome: 'REPLAYED',
        record: record({ id: '213', fields: { ...copy.fields } }),
        reviewerUserId: null,
      });
      await bobSubmission;
      expect(bob.session.state.blocks).toHaveLength(0);
      expect(readSessionDrafts('alice', contextKey, 'session-b')).toHaveLength(0);
    });
  });

  describe('W3-R02 结果未知时的丢弃保护', () => {
    it('refuses to discard an unknown submission and keeps the verification evidence', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord.mockRejectedValueOnce(
        new HttpRequestError('服务暂不可用', 503),
      );

      await current.session.submit(block);
      const snapshot = block.submitSnapshot;
      const requestKey = block.requestKey;

      expect(block.submitState).toBe('unknown');
      expect(current.session.canDiscardBlock(block)).toBe(false);
      expect(await current.session.discardBlock(block)).toBe(false);
      expect(current.dialogs.confirmDiscardBlock).not.toHaveBeenCalled();
      expect(current.dialogs.notify).toHaveBeenCalledWith(
        '提交结果待确认，请先核验本次提交后再丢弃',
        'warning',
      );
      expect(current.session.state.blocks).toHaveLength(1);
      expect(block.requestKey).toBe(requestKey);
      expect(block.submitSnapshot).toBe(snapshot);
      expect(block.submitSnapshot?.fields.reviewerUserId).toBe('alice');
      expect(readSessionDrafts('alice', contextKey, 'session-a')[0].submitState).toBe('unknown');
    });

    it('keeps the unknown state when verification fails again', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord
        .mockRejectedValueOnce(new HttpRequestError('timeout', 504))
        .mockRejectedValueOnce(new HttpRequestError('服务暂不可用', 503));

      await current.session.submit(block);
      await current.session.verify(block);

      expect(block.submitState).toBe('unknown');
      expect(current.session.canDiscardBlock(block)).toBe(false);
      expect(await current.session.discardBlock(block)).toBe(false);
      expect(current.session.state.blocks).toHaveLength(1);
      expect(block.submitSnapshot?.requestKey).toBe(block.requestKey);
    });

    it('refuses to copy an unknown submission before verification', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord.mockRejectedValueOnce(new HttpRequestError('timeout', 504));

      await current.session.submit(block);
      const snapshot = block.submitSnapshot;
      const requestKey = block.requestKey;

      expect(current.session.canCopyBlock(block)).toBe(false);
      expect(current.session.copyBlock(block)).toBeNull();
      expect(current.dialogs.notify).toHaveBeenCalledWith(
        '提交结果待确认，请先核验本次提交后再复制',
        'warning',
      );
      expect(current.session.state.blocks).toHaveLength(1);
      expect(readSessionDrafts('alice', contextKey, 'session-a')).toHaveLength(1);
      expect(block.requestKey).toBe(requestKey);
      expect(block.submitSnapshot).toBe(snapshot);
    });

    it('refuses to copy an in-flight submission', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      const submission = deferred<FormCreateResponse>();
      current.api.createFormRecord.mockReturnValueOnce(submission.promise);

      const pending = current.session.submit(block);
      expect(block.submitState).toBe('pending');
      expect(current.session.canCopyBlock(block)).toBe(false);
      expect(current.session.copyBlock(block)).toBeNull();
      expect(current.dialogs.notify).toHaveBeenCalledWith(
        '提交正在进行，请等待结果后再复制',
        'warning',
      );

      submission.resolve({
        outcome: 'CREATED',
        record: record({ id: '901' }),
        reviewerUserId: null,
      });
      current.api.getFormDetail.mockResolvedValue(detail([record({ id: '901' })]));
      await pending;
    });

    it('keeps an unknown draft uncopyable after the scope changes', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord.mockRejectedValueOnce(new HttpRequestError('timeout', 504));
      await current.session.submit(block);

      current.api.getFormDetail.mockResolvedValue(detail([], { scopeId: '12' }));
      await current.session.reload();

      expect(block.structureStale).toBe(true);
      expect(block.readOnly).toBe(true);
      expect(current.session.canCopyBlock(block)).toBe(false);
      expect(current.session.copyBlock(block)).toBeNull();
      expect(current.session.state.blocks).toHaveLength(1);
      expect(block.submitSnapshot?.requestKey).toBe(block.requestKey);
    });

    it('keeps the unknown create when verification is refused by a stale version after the original was committed', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord
        .mockRejectedValueOnce(new HttpRequestError('timeout', 504))
        .mockRejectedValueOnce(new HttpRequestError('模板结构版本已变化', 409));
      await current.session.submit(block);
      const snapshot = block.submitSnapshot;
      const requestKey = block.requestKey;
      current.api.getFormDetail.mockResolvedValue(
        detail([record({ id: '902', fields: { ...block.fields } })]),
      );
      await current.session.reload();

      await current.session.verify(block);

      expect(block.submitState).toBe('unknown');
      expect(block.frozen).toBe(true);
      expect(block.notice).toContain('核验未完成');
      expect(current.session.canDiscardBlock(block)).toBe(false);
      expect(await current.session.discardBlock(block)).toBe(false);
      expect(current.session.state.blocks).toHaveLength(1);
      expect(block.requestKey).toBe(requestKey);
      expect(block.submitSnapshot).toBe(snapshot);
      expect(readSessionDrafts('alice', contextKey, 'session-a')[0].submitState).toBe('unknown');
    });

    it('keeps the unknown create when verification is refused by a stale version with nothing committed', async () => {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      current.api.createFormRecord
        .mockRejectedValueOnce(new HttpRequestError('timeout', 504))
        .mockRejectedValueOnce(new HttpRequestError('模板结构版本已变化', 409));
      await current.session.submit(block);
      const snapshot = block.submitSnapshot;
      const requestKey = block.requestKey;

      await current.session.verify(block);

      expect(current.api.createFormRecord).toHaveBeenCalledTimes(2);
      expect(block.submitState).toBe('unknown');
      expect(block.frozen).toBe(true);
      expect(block.notice).toContain('核验未完成');
      expect(current.session.canDiscardBlock(block)).toBe(false);
      expect(await current.session.discardBlock(block)).toBe(false);
      expect(current.session.state.blocks).toHaveLength(1);
      expect(block.requestKey).toBe(requestKey);
      expect(block.submitSnapshot).toBe(snapshot);
    });

    it('allows discarding after verification confirms the edit was not written', async () => {
      const base = record({ id: '704', recordVersion: 3, fields: { ...codeReviewFields, supplementNote: '原值' } });
      const current = harness({ apiDetail: detail([base]) });
      await current.session.initialize();
      const block = current.session.startEdit(base)!;
      await current.session.changeField(block, 'supplementNote', '待核验内容');
      current.api.updateFormRecord.mockRejectedValueOnce(new HttpRequestError('timeout', 504));

      await current.session.submit(block);
      expect(block.submitState).toBe('unknown');
      expect(current.session.canDiscardBlock(block)).toBe(false);

      await current.session.verify(block);
      expect(block.submitState).toBe('none');
      expect(block.notice).toBe('已确认本次提交未写入，可重新提交');
      expect(current.session.canDiscardBlock(block)).toBe(true);
      expect(await current.session.discardBlock(block)).toBe(true);
      expect(current.session.state.blocks).toHaveLength(0);
      expect(readSessionDrafts('alice', contextKey, 'session-a')).toHaveLength(0);
    });
  });

  describe('W3-R03 权威详情变化后的草稿校验', () => {
    async function filledBlock() {
      const current = harness();
      await current.session.initialize();
      const block = current.session.addCreateBlock()!;
      await fillValid(current.session, block);
      return { current, block };
    }

    it('stops submission and keeps input when the scope changes on refresh', async () => {
      const { current, block } = await filledBlock();

      current.api.getFormDetail.mockResolvedValue(detail([], { scopeId: '12' }));
      await current.session.reload();

      expect(block.structureStale).toBe(true);
      expect(block.readOnly).toBe(true);
      expect(block.readOnlyReason).toContain('模板范围已变化');
      expect(block.fields.reviewerUserId).toBe('alice');
      expect(current.session.canSubmitBlock(block)).toBe(false);
      expect(await current.session.changeField(block, 'reviewerUserId', 'bob')).toBe(false);
    });

    it('stops submission when the structure version changes on refresh', async () => {
      const { current, block } = await filledBlock();

      current.api.getFormDetail.mockResolvedValue(detail([], { storageVersionId: '23' }));
      await current.session.reload();

      expect(block.structureStale).toBe(true);
      expect(block.readOnly).toBe(true);
      expect(block.readOnlyReason).toContain('模板结构版本已变化');
      expect(block.fields.reviewerUserId).toBe('alice');
      expect(current.session.canSubmitBlock(block)).toBe(false);
    });

    it('keeps the block editable when a refresh returns the same structure', async () => {
      const { current, block } = await filledBlock();

      await current.session.reload();

      expect(block.structureStale).toBe(false);
      expect(block.readOnly).toBe(false);
      expect(current.session.canSubmitBlock(block)).toBe(true);
    });

    it('blocks submission when the refreshed scope is no longer writable', async () => {
      const { current, block } = await filledBlock();

      current.api.getFormDetail.mockResolvedValue(detail([], { writable: false }));
      await current.session.reload();

      expect(current.session.canSubmitBlock(block)).toBe(false);
      expect(block.fields.reviewerUserId).toBe('alice');
    });

    it('marks a draft saved under an older structure as read-only when restored', async () => {
      const first = harness({ editingSession: 'session-a' });
      await first.session.initialize();
      const block = first.session.addCreateBlock()!;
      await fillValid(first.session, block);
      first.session.flushDrafts();

      const second = harness({
        editingSession: 'session-a',
        apiDetail: detail([], { storageVersionId: '23' }),
      });
      await second.session.initialize();

      const restored = second.session.state.blocks[0];
      expect(restored.structureStale).toBe(true);
      expect(restored.readOnly).toBe(true);
      expect(restored.fields.reviewerUserId).toBe('alice');
      expect(restored.requestKey).toBe(block.requestKey);
      expect(second.session.canSubmitBlock(restored)).toBe(false);
    });

    it('keeps the submit snapshot and verification path when the structure changes after an unknown result', async () => {
      const { current, block } = await filledBlock();
      current.api.createFormRecord.mockRejectedValueOnce(new HttpRequestError('timeout', 504));
      await current.session.submit(block);
      expect(block.submitState).toBe('unknown');
      const snapshot = { ...block.submitSnapshot! };

      current.api.getFormDetail.mockResolvedValue(detail([], { storageVersionId: '23' }));
      await current.session.reload();

      expect(block.readOnly).toBe(true);
      expect(block.submitState).toBe('unknown');
      expect(block.submitSnapshot?.requestKey).toBe(snapshot.requestKey);
      expect(block.submitSnapshot?.fields).toEqual(snapshot.fields);

      current.api.createFormRecord.mockResolvedValueOnce({
        outcome: 'REPLAYED',
        record: record({ id: '214' }),
        reviewerUserId: null,
      });
      current.api.getFormDetail.mockResolvedValue(detail([record({ id: '214' })], { storageVersionId: '23' }));
      await current.session.verify(block);

      expect(current.session.state.blocks).toHaveLength(0);
      expect(current.session.state.records.map((item) => item.id)).toEqual(['214']);
    });
  });
});
