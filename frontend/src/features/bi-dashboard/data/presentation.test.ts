import { describe, expect, it } from 'vitest';
import {
  pageErrorMessage,
  responseMatchesPage,
  sectionPresentation,
  systemTestTargetLabel,
} from './presentation';
import type { BiPageResponse } from './types';

function response(pageKey: BiPageResponse<unknown>['pageKey']): BiPageResponse<unknown> {
  return {
    pageKey,
    status: 'READY',
    sourceVersion: 'source-1',
    snapshotId: 'snapshot-1',
    ruleVersion: 'rule-1',
    generatedAt: '2026-08-04T00:00:00Z',
    sections: [],
    traces: [],
    data: {},
  };
}

describe('BI page response presentation', () => {
  it('rejects a stale response when the route has switched to another stage', () => {
    expect(responseMatchesPage(response('system-test'), 'coding')).toBe(false);
  });

  it('accepts the response owned by the active stage', () => {
    expect(responseMatchesPage(response('coding'), 'coding')).toBe(true);
  });

  it('distinguishes system-test severity and priority targets by stable key', () => {
    expect(systemTestTargetLabel('level-one', '一级缺陷')).toBe('一级缺陷修复率（严重程度）');
    expect(systemTestTargetLabel('p1', 'P1')).toBe('P1修复率（优先级）');
    expect(systemTestTargetLabel('p2', 'P2')).toBe('P2修复率（优先级）');
  });

  it('presents a page-level failure as an error for every business section', () => {
    const failed: BiPageResponse<unknown> = {
      ...response('coding'),
      status: 'ERROR',
      sections: [{
        key: 'page',
        label: '页面数据',
        status: 'ERROR',
        message: 'BI 页面数据加载失败，请稍后重试',
      }],
      data: null,
    };

    expect(pageErrorMessage(failed)).toBe('BI 页面数据加载失败，请稍后重试');
    expect(sectionPresentation(failed, 'submission-trend')).toEqual({
      status: 'ERROR',
      message: 'BI 页面数据加载失败，请稍后重试',
    });
  });

  it('does not classify a healthy page as a page-level failure', () => {
    expect(pageErrorMessage(response('coding'))).toBeNull();
  });
});
