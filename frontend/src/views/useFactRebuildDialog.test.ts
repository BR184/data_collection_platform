import { nextTick } from 'vue';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FactRebuildSubmission } from '../types/api';
import { useFactRebuildDialog } from './useFactRebuildDialog';

const successfulResult: FactRebuildSubmission = {
  accepted: true,
  runId: 42,
  status: 'QUEUED',
  action: 'QUEUED',
  message: '同步已提交，等待调度器执行。',
};

describe('useFactRebuildDialog', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('requires the confirmation phrase and five-second wait before rebuilding', async () => {
    vi.useFakeTimers();
    const rebuildFacts = vi.fn(() => Promise.resolve(successfulResult));
    const dialog = useFactRebuildDialog({
      rebuildFacts,
      refreshRunStatus: vi.fn(() => Promise.resolve()),
      notifyError: vi.fn(),
      showResult: vi.fn(),
    });

    dialog.openFactRebuildDialog();
    dialog.factRebuildConfirmText.value = '重建全部事实层';
    await dialog.rebuildFacts();
    expect(rebuildFacts).not.toHaveBeenCalled();

    vi.advanceTimersByTime(5_000);
    await nextTick();
    expect(dialog.factRebuildReady.value).toBe(true);

    await dialog.rebuildFacts();
    expect(rebuildFacts).toHaveBeenCalledOnce();
  });

  it('restarts the confirmation wait each time the dialog opens', async () => {
    vi.useFakeTimers();
    const dialog = useFactRebuildDialog({
      rebuildFacts: vi.fn(() => Promise.resolve(successfulResult)),
      refreshRunStatus: vi.fn(() => Promise.resolve()),
      notifyError: vi.fn(),
      showResult: vi.fn(),
    });

    dialog.openFactRebuildDialog();
    vi.advanceTimersByTime(5_000);
    await nextTick();
    expect(dialog.factRebuildCountdownSeconds.value).toBe(0);

    dialog.closeFactRebuildDialog();
    dialog.openFactRebuildDialog();
    expect(dialog.factRebuildCountdownSeconds.value).toBe(5);
  });
});
