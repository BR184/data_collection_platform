import { describe, expect, it, vi } from 'vitest';
import { useMirrorPurgeDialog } from './useMirrorPurgeDialog';
import type { MirrorPurgeResult, MirrorPurgeScope } from '../types/api';

function createResult(scope: MirrorPurgeScope = 'MIRROR_DATA_ONLY'): MirrorPurgeResult {
  return {
    scope,
    droppedMirrorTables: 3,
    droppedTableNames: ['ods_gitlab_issues'],
    truncatedTables: 2,
    truncatedTableNames: ['sys_table_registry'],
    syncTimestampsReset: true,
  };
}

function createDeferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => {
    resolve = nextResolve;
  });
  return { promise, resolve };
}

function setup() {
  return {
    purgeMirrorData: vi.fn<(scope: MirrorPurgeScope) => Promise<MirrorPurgeResult>>(() =>
      Promise.resolve(createResult()),
    ),
    loadStatus: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    notifyError: vi.fn<(message: string) => void>(),
    showPurgeSummary: vi.fn<(result: MirrorPurgeResult) => Promise<void>>(() => Promise.resolve()),
  };
}

describe('useMirrorPurgeDialog', () => {
  it('opens with the default scope and matches the confirmation phrase', () => {
    const deps = setup();
    const dialog = useMirrorPurgeDialog(deps);

    dialog.purgeScope.value = 'MIRROR_DATA_EXCLUDING_CURRENT_WHITELIST';
    dialog.purgeConfirmText.value = 'old';
    dialog.openPurgeDialog();

    expect(dialog.purgeDialogVisible.value).toBe(true);
    expect(dialog.purgeScope.value).toBe('MIRROR_DATA_ONLY');
    expect(dialog.purgeDialogCopy.value.confirmText).toBe('删除镜像数据');
    expect(dialog.purgeConfirmMatched.value).toBe(false);

    dialog.purgeConfirmText.value = '删除镜像数据';

    expect(dialog.purgeConfirmMatched.value).toBe(true);
  });

  it('locks the dialog while purging and clears it after a successful purge', async () => {
    const deps = setup();
    const deferred = createDeferred<MirrorPurgeResult>();
    deps.purgeMirrorData.mockReturnValueOnce(deferred.promise);
    const dialog = useMirrorPurgeDialog(deps);
    const done = vi.fn();

    dialog.openPurgeDialog();
    dialog.purgeConfirmText.value = '删除镜像数据';
    const purgePromise = dialog.purgeMirrorData();

    expect(dialog.isPurging.value).toBe(true);
    expect(dialog.purgeProgressText.value).toBe('正在删除本地镜像数据，请勿关闭页面或重复操作。');

    dialog.closePurgeDialog();
    dialog.handlePurgeDialogBeforeClose(done);

    expect(dialog.purgeDialogVisible.value).toBe(true);
    expect(done).not.toHaveBeenCalled();

    deferred.resolve(createResult());
    await purgePromise;

    expect(deps.purgeMirrorData).toHaveBeenCalledWith('MIRROR_DATA_ONLY');
    expect(deps.loadStatus).toHaveBeenCalledOnce();
    expect(deps.showPurgeSummary).toHaveBeenCalledWith(createResult());
    expect(dialog.isPurging.value).toBe(false);
    expect(dialog.purgeDialogVisible.value).toBe(false);
    expect(dialog.purgeConfirmText.value).toBe('');
  });

  it('keeps the dialog open and reports errors when purge fails', async () => {
    const deps = setup();
    deps.purgeMirrorData.mockRejectedValueOnce(new Error('删除失败'));
    const dialog = useMirrorPurgeDialog(deps);

    dialog.openPurgeDialog();
    dialog.purgeConfirmText.value = '删除镜像数据';
    await dialog.purgeMirrorData();

    expect(deps.notifyError).toHaveBeenCalledWith('删除失败');
    expect(dialog.isPurging.value).toBe(false);
    expect(dialog.purgeDialogVisible.value).toBe(true);
    expect(dialog.purgeConfirmText.value).toBe('删除镜像数据');
    expect(deps.showPurgeSummary).not.toHaveBeenCalled();
  });
});
