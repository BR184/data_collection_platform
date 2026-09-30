import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import type {
  ReviewDataProblemItemResponse,
  ReviewDataProblemItemSaveRequest,
  ReviewDataRecordDetailResponse,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import { useReviewProblemItemDialog } from './useReviewProblemItemDialog';

function record(id = 3): ReviewDataRecordRowResponse {
  return {
    id,
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
  };
}

function detail(recordId = 3): ReviewDataRecordDetailResponse {
  return {
    record: record(recordId),
    reviewExperts: ['Ada', 'Grace'],
    problemItems: [],
    descriptions: [],
    contents: [],
  };
}

function item(overrides: Partial<ReviewDataProblemItemResponse> = {}): ReviewDataProblemItemResponse {
  return {
    id: 12,
    reviewRecordId: 3,
    reviewerName: 'Ada',
    workloadHours: 1.5,
    reviewCategory: 'design',
    documentPosition: '2.1',
    problemCategory: 'logic',
    problemDescription: 'description',
    suggestedSolution: 'solution',
    ownerName: 'owner',
    rejectionReason: '',
    problemStatus: 'new',
    updatedAt: '2026-04-27T10:00:00',
    ...overrides,
  };
}

function savePayload(): ReviewDataProblemItemSaveRequest {
  return {
    reviewerName: 'Ada',
    workloadHours: 1.5,
    reviewCategory: 'design',
    documentPosition: '2.1',
    problemCategory: 'logic',
    problemDescription: 'description',
    suggestedSolution: 'solution',
    ownerName: 'owner',
    rejectionReason: '',
    problemStatus: 'new',
  };
}

function setup() {
  return {
    loadRecordDetail: vi.fn<(recordId: number) => Promise<ReviewDataRecordDetailResponse>>(),
    createProblemItem: vi.fn<(recordId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>>(),
    updateProblemItem:
      vi.fn<(recordId: number, itemId: number, payload: ReviewDataProblemItemSaveRequest) => Promise<unknown>>(),
    refreshAfterMutation: vi.fn<(recordId: number) => Promise<void>>(),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewProblemItemDialog', () => {
  it('loads record experts before opening create mode', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    const dialog = useReviewProblemItemDialog(deps);

    await dialog.openCreateProblemItem(3);

    expect(deps.loadRecordDetail).toHaveBeenCalledWith(3);
    expect(dialog.problemDialogVisible.value).toBe(true);
    expect(dialog.problemDialogEditMode.value).toBe(false);
    expect(dialog.currentProblemRecordId.value).toBe(3);
    expect(dialog.currentProblemItemId.value).toBeNull();
    expect(dialog.currentProblemExpertOptions.value).toEqual(['Ada', 'Grace']);
    expect(dialog.problemForm.value.ownerName).toBe('owner');
    expect(dialog.problemForm.value.problemDescription).toBe('');
  });

  it('loads record experts and item values before opening edit mode', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    const dialog = useReviewProblemItemDialog(deps);

    await dialog.openEditProblemItem(3, item({ id: 18, problemDescription: 'loaded issue' }));

    expect(dialog.problemDialogVisible.value).toBe(true);
    expect(dialog.problemDialogEditMode.value).toBe(true);
    expect(dialog.currentProblemRecordId.value).toBe(3);
    expect(dialog.currentProblemItemId.value).toBe(18);
    expect(dialog.problemForm.value.problemDescription).toBe('loaded issue');
    expect(dialog.currentProblemExpertOptions.value).toEqual(['Ada', 'Grace']);
  });

  it('creates a problem item and refreshes the owning record', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    deps.createProblemItem.mockResolvedValue({});
    const dialog = useReviewProblemItemDialog(deps);
    const payload = savePayload();

    await dialog.openCreateProblemItem(3);
    await dialog.submitProblemItem({ sessionId: dialog.problemDialogSessionId.value!, payload });

    expect(deps.createProblemItem).toHaveBeenCalledWith(3, payload);
    expect(deps.updateProblemItem).not.toHaveBeenCalled();
    expect(deps.refreshAfterMutation).toHaveBeenCalledWith(3);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审问题已新增');
    expect(dialog.problemDialogVisible.value).toBe(false);
    expect(dialog.problemDialogSaving.value).toBe(false);
  });

  it('updates a problem item and refreshes the owning record', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    deps.updateProblemItem.mockResolvedValue({});
    const dialog = useReviewProblemItemDialog(deps);
    const payload = savePayload();

    await dialog.openEditProblemItem(3, item({ id: 18 }));
    await dialog.submitProblemItem({ sessionId: dialog.problemDialogSessionId.value!, payload });

    expect(deps.updateProblemItem).toHaveBeenCalledWith(3, 18, payload);
    expect(deps.createProblemItem).not.toHaveBeenCalled();
    expect(deps.refreshAfterMutation).toHaveBeenCalledWith(3);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审问题已更新');
    expect(dialog.problemDialogVisible.value).toBe(false);
  });

  it('does not save when no owning record has been selected', async () => {
    const deps = setup();
    const dialog = useReviewProblemItemDialog(deps);

    await dialog.submitProblemItem({ sessionId: 1, payload: savePayload() });

    expect(deps.createProblemItem).not.toHaveBeenCalled();
    expect(deps.updateProblemItem).not.toHaveBeenCalled();
    expect(deps.refreshAfterMutation).not.toHaveBeenCalled();
  });

  it('ignores a late create detail after opening another record edit', async () => {
    const deps = setup();
    const createDeferred = deferred<ReviewDataRecordDetailResponse>();
    deps.loadRecordDetail.mockReturnValueOnce(createDeferred.promise).mockResolvedValueOnce(detail(5));
    const dialog = useReviewProblemItemDialog(deps);

    const pendingCreate = dialog.openCreateProblemItem(3);
    await dialog.openEditProblemItem(5, item({ id: 21, problemDescription: 'issue on 5' }));
    createDeferred.resolve(detail(3));
    await pendingCreate;

    expect(dialog.currentProblemRecordId.value).toBe(5);
    expect(dialog.currentProblemItemId.value).toBe(21);
    expect(dialog.problemForm.value.problemDescription).toBe('issue on 5');
  });

  it('keeps the newest edit target when an older detail resolves late', async () => {
    const deps = setup();
    const a = deferred<ReviewDataRecordDetailResponse>();
    const b = deferred<ReviewDataRecordDetailResponse>();
    deps.loadRecordDetail.mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise);
    const dialog = useReviewProblemItemDialog(deps);

    const pa = dialog.openEditProblemItem(3, item({ id: 11, problemDescription: 'A' }));
    const pb = dialog.openEditProblemItem(4, item({ id: 12, problemDescription: 'B' }));
    b.resolve(detail(4));
    await pb;
    a.resolve(detail(3));
    await pa;

    expect(dialog.currentProblemRecordId.value).toBe(4);
    expect(dialog.currentProblemItemId.value).toBe(12);
    expect(dialog.problemForm.value.problemDescription).toBe('B');
  });

  it('does not reopen after close when a detail resolves late', async () => {
    const deps = setup();
    const deferredDetail = deferred<ReviewDataRecordDetailResponse>();
    deps.loadRecordDetail.mockReturnValue(deferredDetail.promise);
    const dialog = useReviewProblemItemDialog(deps);

    const pending = dialog.openEditProblemItem(3, item({ id: 11 }));
    dialog.problemDialogVisible.value = true;
    dialog.problemDialogVisible.value = false;
    deferredDetail.resolve(detail(3));
    await pending;
    await flushPromises();

    expect(dialog.problemDialogVisible.value).toBe(false);
  });

  it('rejects submission while the owning record detail is still loading', async () => {
    const deps = setup();
    const pendingDetail = deferred<ReviewDataRecordDetailResponse>();
    deps.loadRecordDetail.mockReturnValue(pendingDetail.promise);
    const dialog = useReviewProblemItemDialog(deps);

    const opening = dialog.openCreateProblemItem(3);
    await dialog.submitProblemItem({ sessionId: dialog.problemDialogSessionId.value!, payload: savePayload() });

    expect(deps.createProblemItem).not.toHaveBeenCalled();
    expect(deps.updateProblemItem).not.toHaveBeenCalled();
    pendingDetail.resolve(detail(3));
    await opening;
  });

  it('allows only one write when the same problem session receives repeated submits', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    const pendingWrite = deferred<unknown>();
    deps.createProblemItem.mockReturnValue(pendingWrite.promise);
    const dialog = useReviewProblemItemDialog(deps);
    const payload = savePayload();
    await dialog.openCreateProblemItem(3);
    const sessionId = dialog.problemDialogSessionId.value!;

    const first = dialog.submitProblemItem({ sessionId, payload });
    const second = dialog.submitProblemItem({ sessionId, payload });
    expect(deps.createProblemItem).toHaveBeenCalledTimes(1);

    pendingWrite.resolve({});
    await Promise.all([first, second]);
  });

  it('keeps a newer problem session saving when an older write rejects after close and reopen', async () => {
    const deps = setup();
    const firstWrite = deferred<unknown>();
    const secondWrite = deferred<unknown>();
    deps.loadRecordDetail.mockResolvedValue(detail(4));
    deps.createProblemItem.mockReturnValue(firstWrite.promise);
    deps.updateProblemItem.mockReturnValue(secondWrite.promise);
    const dialog = useReviewProblemItemDialog(deps);

    await dialog.openCreateProblemItem(3);
    const firstSubmit = dialog.submitProblemItem({
      sessionId: dialog.problemDialogSessionId.value!,
      payload: savePayload(),
    });
    dialog.problemDialogVisible.value = false;
    await dialog.openEditProblemItem(4, item({ id: 21, reviewRecordId: 4 }));
    const secondSubmit = dialog.submitProblemItem({
      sessionId: dialog.problemDialogSessionId.value!,
      payload: savePayload(),
    });

    firstWrite.reject(new Error('old write failed'));
    await firstSubmit;
    expect(dialog.problemDialogSaving.value).toBe(true);
    expect(dialog.currentProblemRecordId.value).toBe(4);
    expect(dialog.currentProblemItemId.value).toBe(21);
    expect(dialog.problemDialogVisible.value).toBe(true);

    secondWrite.resolve({});
    await secondSubmit;
  });

  it('refreshes the original record after an accepted write even when its dialog closes', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    const pendingWrite = deferred<unknown>();
    deps.createProblemItem.mockReturnValue(pendingWrite.promise);
    const dialog = useReviewProblemItemDialog(deps);
    await dialog.openCreateProblemItem(3);

    const saving = dialog.submitProblemItem({
      sessionId: dialog.problemDialogSessionId.value!,
      payload: savePayload(),
    });
    dialog.problemDialogVisible.value = false;
    pendingWrite.resolve({});
    await saving;

    expect(deps.refreshAfterMutation).toHaveBeenCalledWith(3);
  });

  it('does not keep a committed create draft available when refresh fails', async () => {
    const deps = setup();
    deps.loadRecordDetail.mockResolvedValue(detail(3));
    deps.createProblemItem.mockResolvedValue({});
    deps.refreshAfterMutation.mockRejectedValue(new Error('refresh failed'));
    const dialog = useReviewProblemItemDialog(deps);
    await dialog.openCreateProblemItem(3);

    const submission = {
      sessionId: dialog.problemDialogSessionId.value!,
      payload: savePayload(),
    };
    await dialog.submitProblemItem(submission);
    await dialog.submitProblemItem(submission);

    expect(deps.createProblemItem).toHaveBeenCalledTimes(1);
    expect(dialog.problemDialogVisible.value).toBe(false);
  });

  it('does not report a late detail failure after the owning scope is unmounted', async () => {
    const deps = setup();
    const pendingDetail = deferred<ReviewDataRecordDetailResponse>();
    deps.loadRecordDetail.mockReturnValue(pendingDetail.promise);
    let dialog!: ReturnType<typeof useReviewProblemItemDialog>;
    const wrapper = mount(defineComponent({
      setup() {
        dialog = useReviewProblemItemDialog(deps);
        void dialog.openEditProblemItem(3, item({ id: 11 }));
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
