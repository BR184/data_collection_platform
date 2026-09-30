import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import type {
  ReviewDataRecordDetailResponse,
  ReviewDataRecordSaveRequest,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import { useReviewRecordDialog } from './useReviewRecordDialog';
import type { RouteTableLoadOutcome } from '../../composables/useRouteTableState';

function record(overrides: Partial<ReviewDataRecordRowResponse> = {}): ReviewDataRecordRowResponse {
  return {
    id: 7,
    projectName: 'project',
    title: 'Architecture review',
    moduleName: 'platform',
    reviewType: 'design',
    reviewDate: '2026-04-27',
    reviewOwner: 'owner',
    reviewExpertsSummary: 'Ada, Grace',
    reviewScalePages: 20,
    reviewProduct: 'design doc',
    authorName: 'author',
    reviewVersion: 'v1',
    problemCount: 2,
    problemDensity: 0.1,
    updatedAt: '2026-04-27T10:00:00',
    deleted: false,
    ...overrides,
  };
}

function detail(overrides: Partial<ReviewDataRecordRowResponse> = {}): ReviewDataRecordDetailResponse {
  return {
    record: record(overrides),
    reviewExperts: ['Ada', 'Grace'],
    problemItems: [],
    descriptions: [],
    contents: [],
  };
}

function savePayload(): ReviewDataRecordSaveRequest {
  return {
    projectName: 'project',
    title: 'Architecture review',
    moduleName: 'platform',
    reviewType: 'design',
    reviewDate: '2026-04-27',
    reviewOwner: 'owner',
    reviewExperts: ['Ada'],
    reviewScalePages: 20,
    reviewProduct: 'design doc',
    authorName: 'author',
    reviewVersion: 'v1',
  };
}

function setup() {
  return {
    loadRecordDetail: vi.fn<(recordId: number) => Promise<ReviewDataRecordDetailResponse>>(),
    createRecord: vi.fn<(payload: ReviewDataRecordSaveRequest) => Promise<ReviewDataRecordDetailResponse>>(),
    updateRecord: vi.fn<(recordId: number, payload: ReviewDataRecordSaveRequest) => Promise<unknown>>(),
    refreshRecords: vi.fn<() => Promise<RouteTableLoadOutcome>>(() => Promise.resolve({ status: 'committed' })),
    afterCreateRecord: vi.fn<(record: ReviewDataRecordDetailResponse) => Promise<void>>(),
    afterUpdateRecord: vi.fn<(recordId: number) => Promise<void>>(),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewRecordDialog', () => {
  it('opens create mode with a fresh empty record form', () => {
    const deps = setup();
    const dialog = useReviewRecordDialog(deps);

    dialog.openCreateRecord();

    expect(dialog.recordDialogVisible.value).toBe(true);
    expect(dialog.recordEditMode.value).toBe(false);
    expect(dialog.editingRecordId.value).toBeNull();
    expect(dialog.recordForm.value.title).toBe('');
    expect(dialog.recordForm.value.reviewExperts).toEqual([]);
  });

  it('loads record detail before opening edit mode', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail({ id: 9, title: 'Loaded review' }));
    const dialog = useReviewRecordDialog(deps);

    await dialog.openEditRecord(9);

    expect(deps.loadRecordDetail).toHaveBeenCalledWith(9);
    expect(dialog.recordDialogVisible.value).toBe(true);
    expect(dialog.recordEditMode.value).toBe(true);
    expect(dialog.editingRecordId.value).toBe(9);
    expect(dialog.recordForm.value.title).toBe('Loaded review');
    expect(dialog.recordForm.value.reviewExperts).toEqual(['Ada', 'Grace']);
  });

  it('creates a record and refreshes the list after saving', async () => {
    const deps = setup();
    deps.createRecord.mockResolvedValue(detail({ id: 11 }));
    const dialog = useReviewRecordDialog(deps);
    const payload = savePayload();

    dialog.openCreateRecord();
    await dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload });

    expect(deps.createRecord).toHaveBeenCalledWith(payload);
    expect(deps.updateRecord).not.toHaveBeenCalled();
    expect(deps.refreshRecords).toHaveBeenCalledTimes(1);
    expect(deps.afterCreateRecord).toHaveBeenCalledWith(detail({ id: 11 }));
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审记录已创建');
    expect(dialog.recordDialogVisible.value).toBe(false);
    expect(dialog.recordDialogSaving.value).toBe(false);
  });

  it('updates the editing record and refreshes the list after saving', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail({ id: 9 }));
    deps.updateRecord.mockResolvedValue({});
    const dialog = useReviewRecordDialog(deps);
    const payload = savePayload();

    await dialog.openEditRecord(9);
    await dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload });

    expect(deps.updateRecord).toHaveBeenCalledWith(9, payload);
    expect(deps.createRecord).not.toHaveBeenCalled();
    expect(deps.refreshRecords).toHaveBeenCalledTimes(1);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审记录已更新');
    expect(dialog.recordDialogVisible.value).toBe(false);
  });

  it('keeps the dialog open and reports the failure when saving fails', async () => {
    const deps = setup();
    deps.createRecord.mockRejectedValue(new Error('save failed'));
    const dialog = useReviewRecordDialog(deps);

    dialog.openCreateRecord();
    await dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload: savePayload() });

    expect(dialog.recordDialogVisible.value).toBe(true);
    expect(dialog.recordDialogSaving.value).toBe(false);
    expect(deps.notifyError).toHaveBeenCalledWith('save failed');
  });

  it('ignores a late edit detail that resolves after switching to create', async () => {
    const deps = setup();
    const deferred = deferredDetail();
    deps.loadRecordDetail.mockReturnValue(deferred.promise);
    const dialog = useReviewRecordDialog(deps);

    const pending = dialog.openEditRecord(9);
    dialog.openCreateRecord();
    deferred.resolve(detail({ id: 9, title: 'Stale A' }));
    await pending;

    expect(dialog.recordEditMode.value).toBe(false);
    expect(dialog.editingRecordId.value).toBeNull();
    expect(dialog.recordForm.value.title).toBe('');
    expect(dialog.recordDialogVisible.value).toBe(true);
  });

  it('keeps the newest edit target when an older detail resolves late', async () => {
    const deps = setup();
    const a = deferredDetail();
    const b = deferredDetail();
    deps.loadRecordDetail.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const dialog = useReviewRecordDialog(deps);

    const pa = dialog.openEditRecord(1);
    const pb = dialog.openEditRecord(2);
    b.resolve(detail({ id: 2, title: 'B' }));
    await pb;
    a.resolve(detail({ id: 1, title: 'A' }));
    await pa;

    expect(dialog.editingRecordId.value).toBe(2);
    expect(dialog.recordForm.value.title).toBe('B');
  });

  it('does not reopen the dialog when a detail resolves after the user closed it', async () => {
    const deps = setup();
    const deferred = deferredDetail();
    deps.loadRecordDetail.mockReturnValue(deferred.promise);
    const dialog = useReviewRecordDialog(deps);

    dialog.openCreateRecord();
    dialog.openEditRecord(9);
    dialog.recordDialogVisible.value = false;
    deferred.resolve(detail({ id: 9 }));
    await flushPromises();

    expect(dialog.recordDialogVisible.value).toBe(false);
    expect(deps.notifyError).not.toHaveBeenCalled();
  });

  it('freezes the submitted target even if the session changes mid-save', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail({ id: 9 }));
    const deferredSave = deferred<unknown>();
    deps.updateRecord.mockReturnValue(deferredSave.promise);
    const dialog = useReviewRecordDialog(deps);
    const payload = savePayload();

    await dialog.openEditRecord(9);
    const submit = dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload });
    dialog.openCreateRecord();
    deferredSave.resolve({});
    await submit;

    expect(deps.updateRecord).toHaveBeenCalledWith(9, payload);
    expect(deps.createRecord).not.toHaveBeenCalled();
    // 迟到的 update 结果不得关闭后来打开的新增弹窗。
    expect(dialog.recordDialogVisible.value).toBe(true);
  });

  it('rejects submission while an edit detail is still loading', async () => {
    const deps = setup();
    const pendingDetail = deferredDetail();
    deps.loadRecordDetail.mockReturnValue(pendingDetail.promise);
    const dialog = useReviewRecordDialog(deps);

    const opening = dialog.openEditRecord(9);
    await dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload: savePayload() });

    expect(deps.updateRecord).not.toHaveBeenCalled();
    expect(deps.createRecord).not.toHaveBeenCalled();
    pendingDetail.resolve(detail({ id: 9 }));
    await opening;
  });

  it('allows only one write when the same session receives repeated submits', async () => {
    const deps = setup();
    const pendingWrite = deferred<ReviewDataRecordDetailResponse>();
    deps.createRecord.mockReturnValue(pendingWrite.promise);
    const dialog = useReviewRecordDialog(deps);
    const payload = savePayload();
    dialog.openCreateRecord();
    const sessionId = dialog.recordDialogSessionId.value!;

    const first = dialog.submitRecord({ sessionId, payload });
    const second = dialog.submitRecord({ sessionId, payload });
    expect(deps.createRecord).toHaveBeenCalledTimes(1);

    pendingWrite.resolve(detail({ id: 11 }));
    await Promise.all([first, second]);
  });

  it('keeps a newer session saving when an older write rejects after close and reopen', async () => {
    const deps = setup();
    const firstWrite = deferred<ReviewDataRecordDetailResponse>();
    const secondWrite = deferred<unknown>();
    deps.createRecord.mockReturnValue(firstWrite.promise);
    deps.loadRecordDetail.mockResolvedValue(detail({ id: 9 }));
    deps.updateRecord.mockReturnValue(secondWrite.promise);
    const dialog = useReviewRecordDialog(deps);

    dialog.openCreateRecord();
    const firstSubmit = dialog.submitRecord({
      sessionId: dialog.recordDialogSessionId.value!,
      payload: savePayload(),
    });
    dialog.recordDialogVisible.value = false;
    await dialog.openEditRecord(9);
    const secondSubmit = dialog.submitRecord({
      sessionId: dialog.recordDialogSessionId.value!,
      payload: savePayload(),
    });

    firstWrite.reject(new Error('old write failed'));
    await firstSubmit;
    expect(dialog.recordDialogSaving.value).toBe(true);
    expect(dialog.editingRecordId.value).toBe(9);
    expect(dialog.recordDialogVisible.value).toBe(true);

    secondWrite.resolve({});
    await secondSubmit;
  });

  it('refreshes the original record after an accepted update even when its dialog closes', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail({ id: 9 }));
    const pendingWrite = deferred<unknown>();
    deps.updateRecord.mockReturnValue(pendingWrite.promise);
    const dialog = useReviewRecordDialog(deps);
    await dialog.openEditRecord(9);

    const saving = dialog.submitRecord({ sessionId: dialog.recordDialogSessionId.value!, payload: savePayload() });
    dialog.recordDialogVisible.value = false;
    pendingWrite.resolve({});
    await saving;

    expect(deps.refreshRecords).toHaveBeenCalledTimes(1);
    expect(deps.afterUpdateRecord).toHaveBeenCalledWith(9);
  });

  it('does not keep a committed create draft available when refresh fails', async () => {
    const deps = setup();
    deps.createRecord.mockResolvedValue(detail({ id: 11 }));
    deps.refreshRecords.mockResolvedValue({ status: 'failed', error: new Error('refresh failed') });
    const dialog = useReviewRecordDialog(deps);
    dialog.openCreateRecord();

    const submission = { sessionId: dialog.recordDialogSessionId.value!, payload: savePayload() };
    await dialog.submitRecord(submission);
    await dialog.submitRecord(submission);

    expect(deps.createRecord).toHaveBeenCalledTimes(1);
    expect(dialog.recordDialogVisible.value).toBe(false);
  });

  it('does not report a late detail failure after the owning scope is unmounted', async () => {
    const deps = setup();
    const pendingDetail = deferredDetail();
    deps.loadRecordDetail.mockReturnValue(pendingDetail.promise);
    let dialog!: ReturnType<typeof useReviewRecordDialog>;
    const wrapper = mount(defineComponent({
      setup() {
        dialog = useReviewRecordDialog(deps);
        void dialog.openEditRecord(9);
        return () => h('div');
      },
    }));

    wrapper.unmount();
    pendingDetail.reject(new Error('late failure'));
    await flushPromises();

    expect(deps.notifyError).not.toHaveBeenCalled();
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function deferredDetail() {
  return deferred<ReviewDataRecordDetailResponse>();
}
