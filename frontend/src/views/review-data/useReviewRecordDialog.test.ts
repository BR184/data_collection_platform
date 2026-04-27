import { describe, expect, it, vi } from 'vitest';
import type {
  ReviewDataRecordDetailResponse,
  ReviewDataRecordSaveRequest,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import { useReviewRecordDialog } from './useReviewRecordDialog';

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
    createRecord: vi.fn<(payload: ReviewDataRecordSaveRequest) => Promise<unknown>>(),
    updateRecord: vi.fn<(recordId: number, payload: ReviewDataRecordSaveRequest) => Promise<unknown>>(),
    refreshRecords: vi.fn<() => Promise<void>>(),
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
    deps.createRecord.mockResolvedValue({});
    const dialog = useReviewRecordDialog(deps);
    const payload = savePayload();

    dialog.openCreateRecord();
    await dialog.submitRecord(payload);

    expect(deps.createRecord).toHaveBeenCalledWith(payload);
    expect(deps.updateRecord).not.toHaveBeenCalled();
    expect(deps.refreshRecords).toHaveBeenCalledTimes(1);
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
    await dialog.submitRecord(payload);

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
    await dialog.submitRecord(savePayload());

    expect(dialog.recordDialogVisible.value).toBe(true);
    expect(dialog.recordDialogSaving.value).toBe(false);
    expect(deps.notifyError).toHaveBeenCalledWith('save failed');
  });
});
