const messageTranslations: Record<string, string> = {
  'Refresh requested': '已开始刷新最新数据',
  'Refresh completed': '刷新已完成',
  'Refresh failed; showing latest persisted data': '刷新未完成，已展示当前可用数据',
  'Refresh has not been requested': '尚未请求刷新',
  'No completed mirror sync timestamp is available': '暂无已完成的镜像同步时间',
  'Showing latest persisted data': '已展示当前可用数据',
};

export function toUserMessage(message: unknown, fallback = '') {
  const text = typeof message === 'string' ? message.trim() : '';
  if (!text) {
    return fallback;
  }
  return messageTranslations[text] ?? text;
}

/**
 * 从未知异常中提取可展示的消息。
 *
 * @param error 捕获到的任意异常值
 * @param fallback 异常不包含有效消息时使用的兜底文案
 * @returns 异常消息或兜底文案
 */
export function getErrorMessage(error: unknown, fallback = ''): string {
  if (typeof error === 'string') {
    return error.trim() || fallback;
  }
  if (error instanceof Error) {
    return error.message.trim() || fallback;
  }
  if (typeof error === 'object' && error !== null) {
    const candidate = error as { message?: unknown };
    if (typeof candidate.message === 'string') {
      return candidate.message.trim() || fallback;
    }
  }
  return fallback;
}
