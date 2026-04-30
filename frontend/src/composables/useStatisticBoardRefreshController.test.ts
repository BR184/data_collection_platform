import { ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { useStatisticBoardRefreshController } from './useStatisticBoardRefreshController';

describe('useStatisticBoardRefreshController', () => {
  it('loads board and rule explanation when refreshing', async () => {
    const loading = ref(false);
    const detailVisible = ref(false);
    const loadBoard = vi.fn(() => Promise.resolve());
    const loadRuleExplanation = vi.fn(() => Promise.resolve());
    const loadDetail = vi.fn(() => Promise.resolve());
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard,
      loadRuleExplanation,
      loadDetail,
    });

    await controller.refreshBoard();

    expect(loadBoard).toHaveBeenCalledTimes(1);
    expect(loadRuleExplanation).toHaveBeenCalledTimes(1);
    expect(loadDetail).not.toHaveBeenCalled();
    expect(loading.value).toBe(false);
  });

  it('reloads detail when detail dialog is visible', async () => {
    const loading = ref(false);
    const detailVisible = ref(true);
    const loadBoard = vi.fn(() => Promise.resolve());
    const loadRuleExplanation = vi.fn(() => Promise.resolve());
    const loadDetail = vi.fn(() => Promise.resolve());
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard,
      loadRuleExplanation,
      loadDetail,
    });

    await controller.refreshBoard();

    expect(loadDetail).toHaveBeenCalledTimes(1);
  });

  it('clears loading when refresh fails', async () => {
    const loading = ref(false);
    const detailVisible = ref(true);
    const error = new Error('load failed');
    const controller = useStatisticBoardRefreshController({
      loading,
      detailVisible,
      loadBoard: vi.fn(() => Promise.reject(error)),
      loadRuleExplanation: vi.fn(() => Promise.resolve()),
      loadDetail: vi.fn(() => Promise.resolve()),
    });

    await expect(controller.refreshBoard()).rejects.toThrow('load failed');

    expect(loading.value).toBe(false);
  });
});
