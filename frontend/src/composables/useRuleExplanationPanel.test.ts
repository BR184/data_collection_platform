import { describe, expect, it, vi } from 'vitest';
import { useRuleExplanationPanel } from './useRuleExplanationPanel';

function createRuleExplanation(title = '规则说明') {
  return {
    boardKey: 'test-board',
    supported: true,
    title,
    version: 'v1',
    scopeDescription: 'scope',
    summary: 'summary',
    flowSteps: [],
    metricDefinitions: [],
    unsupportedReason: null,
  };
}

describe('useRuleExplanationPanel', () => {
  it('loads rule explanation on first open and only loads once while cached', async () => {
    const load = vi.fn(async () => createRuleExplanation('首次加载'));
    const fallback = vi.fn((reason: string) => ({
      ...createRuleExplanation('fallback'),
      supported: false,
      unsupportedReason: reason,
    }));

    const panel = useRuleExplanationPanel({ load, fallback });

    await panel.openRuleExplanation();
    await panel.openRuleExplanation();

    expect(panel.ruleExplanationVisible.value).toBe(true);
    expect(load).toHaveBeenCalledOnce();
    expect(panel.ruleExplanation.value?.title).toBe('首次加载');
    expect(panel.ruleExplanationLoading.value).toBe(false);
    expect(fallback).not.toHaveBeenCalled();
  });

  it('uses fallback payload when loading fails and supports reset', async () => {
    const load = vi.fn(async () => {
      throw new Error('boom');
    });
    const fallback = vi.fn((reason: string) => ({
      ...createRuleExplanation('加载失败'),
      supported: false,
      unsupportedReason: reason,
    }));

    const panel = useRuleExplanationPanel({ load, fallback });

    await panel.loadRuleExplanation();

    expect(load).toHaveBeenCalledOnce();
    expect(fallback).toHaveBeenCalledOnce();
    expect(panel.ruleExplanation.value?.supported).toBe(false);
    expect(panel.ruleExplanation.value?.unsupportedReason).toBeTruthy();

    panel.resetRuleExplanation();
    expect(panel.ruleExplanation.value).toBeNull();
  });
});
