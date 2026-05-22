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
