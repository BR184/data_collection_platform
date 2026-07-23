const AUTH_REQUIRED_MESSAGE_WINDOW_MS = 1_000;

interface RecentAuthRequiredMessage {
  text: string;
  expiresAt: number;
}

let recentAuthRequiredMessage: RecentAuthRequiredMessage | null = null;

/**
 * 记录已经由全局登录流程展示过的 401 提示，以便页面本地异常处理不再重复展示相同文案。
 */
export function rememberAuthRequiredMessage(message: unknown, now: number = Date.now()): void {
  const text = normalizeMessage(message);
  recentAuthRequiredMessage = text
    ? { text, expiresAt: now + AUTH_REQUIRED_MESSAGE_WINDOW_MS }
    : null;
}

/**
 * 判断错误文案是否刚刚由 401 全局登录流程展示过。
 */
export function isRecentAuthRequiredMessage(message: unknown, now: number = Date.now()): boolean {
  if (!recentAuthRequiredMessage) {
    return false;
  }
  if (recentAuthRequiredMessage.expiresAt < now) {
    recentAuthRequiredMessage = null;
    return false;
  }
  return recentAuthRequiredMessage.text === normalizeMessage(message);
}

function normalizeMessage(message: unknown): string {
  return typeof message === 'string' ? message.trim() : '';
}
