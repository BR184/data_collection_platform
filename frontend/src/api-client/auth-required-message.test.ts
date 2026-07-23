import { afterEach, describe, expect, it } from 'vitest';
import { isRecentAuthRequiredMessage, rememberAuthRequiredMessage } from './auth-required-message';

describe('auth required message state', () => {
  afterEach(() => {
    rememberAuthRequiredMessage('', 0);
  });

  it('recognizes the same message immediately after the global 401 handler displays it', () => {
    rememberAuthRequiredMessage('请先登录', 100);

    expect(isRecentAuthRequiredMessage('请先登录', 101)).toBe(true);
  });

  it('does not suppress a different business error', () => {
    rememberAuthRequiredMessage('请先登录', 100);

    expect(isRecentAuthRequiredMessage('刷新最新数据失败', 101)).toBe(false);
  });

  it('expires the duplicate suppression window', () => {
    rememberAuthRequiredMessage('请先登录', 100);

    expect(isRecentAuthRequiredMessage('请先登录', 1_101)).toBe(false);
  });
});
