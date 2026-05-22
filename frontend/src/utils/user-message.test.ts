import { describe, expect, it } from 'vitest';
import { toUserMessage } from './user-message';

describe('toUserMessage', () => {
  it('translates backend default refresh messages for user-facing notifications', () => {
    expect(toUserMessage('Refresh requested')).toBe('已开始刷新最新数据');
    expect(toUserMessage('Refresh failed; showing latest persisted data')).toBe('刷新未完成，已展示当前可用数据');
  });

  it('keeps existing Chinese business messages unchanged', () => {
    expect(toUserMessage('镜像同步中')).toBe('镜像同步中');
  });
});
