import { ElMessage as ElementMessage } from 'element-plus/es/components/message/index';
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
    return ElementMessage.error(buildMessageOptions(input, 'error') as never);
  },
  closeAll(type?: Parameters<typeof ElementMessage.closeAll>[0]) {
    return ElementMessage.closeAll(type);
  },
};
