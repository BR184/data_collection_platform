export interface DelayWritebackSwitchPrompt {
  title: string;
  message: string;
  confirmButtonText: string;
}

/**
 * 组装「延期标签写回」开关切换时的确认提示。
 *
 * 该开关是该功能的唯一控制：开启并保存后平台会真实调用 GitLab API 增删延期标签，
 * 因此开启方向必须明确告知外部影响。Token 在读取配置时会被脱敏为空白，前端无法据此判断
 * 是否已配置，只能在 Web 地址为空时补充「仍不会写回」的说明，避免出现"打开了却没生效"的误解。
 */
export function delayWritebackSwitchPrompt(
  enabled: boolean,
  webBaseUrlReady = true,
): DelayWritebackSwitchPrompt {
  if (enabled) {
    const missingBaseUrl = webBaseUrlReady ? '' : '当前未填写 GitLab Web 地址，保存后仍不会写回。';
    return {
      title: '开启延期标签写回',
      message:
        '开启并保存配置后，平台会真实调用 GitLab API 为本数据源议题写入或摘除「响应已延期」「解决已延期」标签。'
        + '请确认上方的 GitLab Web 地址与 Project Access Token 指向预期环境。'
        + missingBaseUrl,
      confirmButtonText: '确认开启',
    };
  }
  return {
    title: '关闭延期标签写回',
    message: '关闭并保存配置后，平台仍会监控延期事实，但不再调用 GitLab API 写标签，已排队的写回任务会被跳过。',
    confirmButtonText: '确认关闭',
  };
}
