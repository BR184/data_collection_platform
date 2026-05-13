import { describe, expect, it, vi } from 'vitest';
import type {
  ReviewDataProblemItemResponse,
  ReviewDataRecordRowResponse,
} from '../../types/api';
import { useReviewDataPageActions } from './useReviewDataPageActions';

function record(id = 3): ReviewDataRecordRowResponse {
  return {
    id,
    projectName: 'project',
    title: `review-${id}`,
    moduleName: 'module',
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

function problemItem(id = 9): ReviewDataProblemItemResponse {
  return {
    id,
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
  };
}

function setup() {
  return {
    refreshRecords: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    syncGitlabContext: vi.fn<(recordIds: number[]) => Promise<{ accepted: boolean; message: string }>>(
      () => Promise.resolve({ accepted: true, message: '已同步关联 GitLab 上下文' }),
    ),
    toggleProblemPanel: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    isProblemExpanded: vi.fn<(recordId: number) => boolean>(() => false),
    openDetail: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    openCreateRecord: vi.fn<() => void>(),
    openEditRecord: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    openCreateProblemItem: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    openEditProblemItem: vi.fn<(recordId: number, item: ReviewDataProblemItemResponse) => Promise<void>>(() => Promise.resolve()),
    deleteRecord: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    deleteProblemItem: vi.fn<(recordId: number, itemId: number) => Promise<void>>(() => Promise.resolve()),
    refreshAfterProblemItemMutation: vi.fn<(recordId: number) => Promise<void>>(() => Promise.resolve()),
    confirm: vi.fn<(message: string, title: string) => Promise<unknown>>(() => Promise.resolve(true)),
    notifySuccess: vi.fn<(message: string) => void>(),
    notifyError: vi.fn<(message: string) => void>(),
  };
}

describe('useReviewDataPageActions', () => {
  it('routes row-level actions to record-aware dependencies', async () => {
    const deps = setup();
    deps.isProblemExpanded.mockReturnValue(true);
    const state = useReviewDataPageActions(deps);
    const row = { __raw: record(3) };

    await state.toggleProblemPanelByRow(row);
    await state.handleCreateProblemItemByRow(row);
    await state.handleOpenDetail(row);
    await state.handleEditRecord(row);

    expect(state.isProblemExpandedByRow(row)).toBe(true);
    expect(deps.toggleProblemPanel).toHaveBeenCalledWith(3);
    expect(deps.openCreateProblemItem).toHaveBeenCalledWith(3);
    expect(deps.openDetail).toHaveBeenCalledWith(3);
    expect(deps.openEditRecord).toHaveBeenCalledWith(3);
  });

  it('refreshes and toggles rule explanation state', async () => {
    const deps = setup();
    const state = useReviewDataPageActions(deps);

    await state.handleRefresh();
    state.openRuleExplanation();

    expect(deps.refreshRecords).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审数据列表已刷新');
    expect(state.ruleExplanationVisible.value).toBe(true);
  });

  it('syncs linked GitLab context without replacing local refresh behavior', async () => {
    const deps = setup();
    const state = useReviewDataPageActions(deps);

    await state.handleSyncGitlabContext([
      { ...record(7), gitlabProjectId: 325, gitlabResourceIid: 12, gitlabResourceType: 'merge_request' },
    ]);

    expect(deps.syncGitlabContext).toHaveBeenCalledWith([7]);
    expect(deps.refreshRecords).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('已同步关联 GitLab 上下文');
  });

  it('keeps GitLab context sync as a local reload when current rows have no linkage', async () => {
    const deps = setup();
    const state = useReviewDataPageActions(deps);

    await state.handleSyncGitlabContext([record(8)]);

    expect(deps.syncGitlabContext).not.toHaveBeenCalled();
    expect(deps.refreshRecords).toHaveBeenCalledOnce();
    expect(deps.notifySuccess).toHaveBeenCalledWith('当前结果没有关联 GitLab 上下文，仅刷新本地列表');
  });

  it('confirms and deletes records, then refreshes the list', async () => {
    const deps = setup();
    const state = useReviewDataPageActions(deps);
    const row = { __raw: record(5) };

    await state.handleDeleteRecord(row);

    expect(deps.confirm).toHaveBeenCalledWith('确认删除评审“review-5”吗？', '删除评审');
    expect(deps.deleteRecord).toHaveBeenCalledWith(5);
    expect(deps.refreshRecords).toHaveBeenCalledTimes(1);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审记录已删除');
  });

  it('confirms and deletes problem items, then triggers linked refresh', async () => {
    const deps = setup();
    const state = useReviewDataPageActions(deps);

    await state.handleDeleteProblemItem(3, 9);

    expect(deps.confirm).toHaveBeenCalledWith('确认删除这条评审问题吗？', '删除评审问题');
    expect(deps.deleteProblemItem).toHaveBeenCalledWith(3, 9);
    expect(deps.refreshAfterProblemItemMutation).toHaveBeenCalledWith(3);
    expect(deps.notifySuccess).toHaveBeenCalledWith('评审问题已删除');
  });

  it('ignores cancel and reports real failures', async () => {
    const cancelDeps = setup();
    cancelDeps.confirm.mockRejectedValue('cancel');
    const cancelState = useReviewDataPageActions(cancelDeps);

    await cancelState.handleDeleteRecord({ __raw: record(8) });
    expect(cancelDeps.notifyError).not.toHaveBeenCalled();

    const errorDeps = setup();
    errorDeps.confirm.mockResolvedValue(true);
    errorDeps.deleteProblemItem.mockRejectedValue(new Error('delete failed'));
    const errorState = useReviewDataPageActions(errorDeps);

    await errorState.handleDeleteProblemItem(3, problemItem().id);
    expect(errorDeps.notifyError).toHaveBeenCalledWith('delete failed');
  });
});
