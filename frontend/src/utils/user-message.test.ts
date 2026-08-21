import { describe, expect, it } from 'vitest';
import { getErrorMessage, toUserMessage } from './user-message';

describe('toUserMessage', () => {
  it('translates backend default refresh messages for user-facing notifications', () => {
    expect(toUserMessage('Refresh requested')).toBe('已开始刷新最新数据');
    expect(toUserMessage('Refresh failed; showing latest persisted data')).toBe('刷新未完成，已展示当前可用数据');
  });

  it('keeps existing Chinese business messages unchanged', () => {
    expect(toUserMessage('镜像同步中')).toBe('镜像同步中');
  });
});

describe('getErrorMessage', () => {
  it('supports Error, string, and response-like object errors', () => {
    expect(getErrorMessage(new Error('请求失败'))).toBe('请求失败');
    expect(getErrorMessage('参数错误')).toBe('参数错误');
    expect(getErrorMessage({ message: '服务不可用' })).toBe('服务不可用');
  });

  it('uses fallback for empty or unknown errors', () => {
    expect(getErrorMessage(new Error('  '), '操作失败')).toBe('操作失败');
    expect(getErrorMessage({ code: 'UNKNOWN' }, '操作失败')).toBe('操作失败');
    expect(getErrorMessage(null, '操作失败')).toBe('操作失败');
  });
});
