import { describe, expect, it, vi } from 'vitest';
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
    await dialog.submitProblemItem(payload);

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
    await dialog.submitProblemItem(payload);

    expect(deps.updateProblemItem).toHaveBeenCalledWith(3, 18, payload);
    expect(deps.createProblemItem).not.toHaveBeenCalled();
    expect(deps.refreshAfterMutation).toHaveBeenCalledWith(3);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审问题已更新');
    expect(dialog.problemDialogVisible.value).toBe(false);
  });

  it('does not save when no owning record has been selected', async () => {
    const deps = setup();
    const dialog = useReviewProblemItemDialog(deps);

    await dialog.submitProblemItem(savePayload());

    expect(deps.createProblemItem).not.toHaveBeenCalled();
    expect(deps.updateProblemItem).not.toHaveBeenCalled();
    expect(deps.refreshAfterMutation).not.toHaveBeenCalled();
  });
});
