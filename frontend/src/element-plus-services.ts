import { ElMessage as ElementMessage } from 'element-plus/es/components/message/index';
import { ElNotification as ElementNotification } from 'element-plus/es/components/notification/index';
import { isRecentAuthRequiredMessage } from './api-client/auth-required-message';
import { toUserMessage } from './utils/user-message';

export { ElMessageBox } from 'element-plus/es/components/message-box/index';
export { ElLoading } from 'element-plus/es/components/loading/index';

type MessageInput = string | {
  message?: unknown;
  showClose?: boolean;
  duration?: number;
  [key: string]: unknown;
};

type MessageLevel = 'success' | 'warning' | 'info' | 'error';

const messageDuration: Record<MessageLevel, number> = {
  success: 1800,
  info: 1800,
  warning: 3200,
  error: 3200,
};

function buildMessageOptions(input: MessageInput, level: MessageLevel) {
  const base = typeof input === 'string' ? { message: input } : input;
  return {
    ...base,
    message: toUserMessage(base.message),
    showClose: base.showClose ?? true,
    duration: base.duration ?? messageDuration[level],
  };
}

export const ElMessage = {
  success(input: MessageInput) {
    return ElementMessage.success(buildMessageOptions(input, 'success') as never);
  },
  warning(input: MessageInput) {
    return ElementMessage.warning(buildMessageOptions(input, 'warning') as never);
  },
  info(input: MessageInput) {
    return ElementMessage.info(buildMessageOptions(input, 'info') as never);
  },
  error(input: MessageInput) {
    const options = buildMessageOptions(input, 'error');
    if (isRecentAuthRequiredMessage(options.message)) {
      return undefined;
    }
    return ElementMessage.error(options as never);
  },
  closeAll(type?: Parameters<typeof ElementMessage.closeAll>[0]) {
    return ElementMessage.closeAll(type);
  },
};

type NotificationInput = string | {
  title?: string;
  message?: unknown;
  duration?: number;
  showClose?: boolean;
  customClass?: string;
  offset?: number;
  position?: string;
  onClose?: (...args: unknown[]) => void;
  [key: string]: unknown;
};

const platformNotificationOffset = 56;
type NotificationHandle = ReturnType<typeof ElementNotification.warning>;
type PlatformNotificationOptions = ReturnType<typeof buildNotificationOptions>;
const activeNotifications = new Map<string, NotificationHandle>();
let notificationOffsetRefreshScheduled = false;

function buildNotificationOptions(input: NotificationInput, level: MessageLevel) {
  const base = typeof input === 'string' ? { message: input } : input;
  const customClass = [
    'platform-notification',
    typeof base.customClass === 'string' ? base.customClass : '',
  ].filter(Boolean).join(' ');
  return {
    ...base,
    message: toUserMessage(base.message),
    showClose: base.showClose ?? true,
    duration: base.duration ?? (level === 'warning' || level === 'error' ? 4500 : 2600),
    position: base.position ?? 'top-right',
    offset: typeof base.offset === 'number' ? base.offset : platformNotificationOffset,
    customClass,
  };
}

function showPlatformNotification(level: MessageLevel, input: NotificationInput) {
  const options = buildNotificationOptions(input, level);
  const signature = notificationSignature(level, options);
  const activeNotification = activeNotifications.get(signature);
  if (activeNotification) {
    scheduleNotificationOffsetRefresh();
    return activeNotification;
  }

  const userOnClose = options.onClose;
  const finalOptions = {
    ...options,
    onClose: (...args: unknown[]) => {
      activeNotifications.delete(signature);
      scheduleNotificationOffsetRefresh();
      userOnClose?.(...args);
    },
  };
  const notification = ElementNotification[level](finalOptions as never);
  activeNotifications.set(signature, notification);
  scheduleNotificationOffsetRefresh();
  return notification;
}

function notificationSignature(level: MessageLevel, options: PlatformNotificationOptions) {
  return [
    level,
    options.position,
    normalizeNotificationText(options.title),
    normalizeNotificationText(options.message),
  ].join('\u0000');
}

function normalizeNotificationText(value: unknown) {
  return typeof value === 'string' ? value : JSON.stringify(value ?? '');
}

function scheduleNotificationOffsetRefresh() {
  if (notificationOffsetRefreshScheduled || typeof window === 'undefined') {
    return;
  }
  notificationOffsetRefreshScheduled = true;
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      notificationOffsetRefreshScheduled = false;
      const notificationApi = ElementNotification as unknown as {
        updateOffsets?: (position?: string) => void;
      };
      notificationApi.updateOffsets?.('top-right');
      notificationApi.updateOffsets?.('top-left');
      notificationApi.updateOffsets?.('bottom-right');
      notificationApi.updateOffsets?.('bottom-left');
    });
  });
}

export const ElNotification = {
  success(input: NotificationInput) {
    return showPlatformNotification('success', input);
  },
  warning(input: NotificationInput) {
    return showPlatformNotification('warning', input);
  },
  info(input: NotificationInput) {
    return showPlatformNotification('info', input);
  },
  error(input: NotificationInput) {
    return showPlatformNotification('error', input);
  },
  closeAll() {
    activeNotifications.clear();
    return ElementNotification.closeAll();
  },
};
