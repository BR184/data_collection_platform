import { computed, type Ref } from 'vue';
import type {
  GitlabSourceHealthResponse,
  GitlabSystemHookRegistrationStatus,
} from '../types/api';
import { syncStatusText, translateSyncMessage } from './mirror-settings-helpers';

export type MirrorSourceHealthTone = 'info' | 'warning' | 'danger' | 'success';

export interface MirrorSourceHealthPresentationDependencies {
  sourceHealth: Readonly<Ref<GitlabSourceHealthResponse[]>>;
  selectedConfigId: Readonly<Ref<number | undefined>>;
  isDockerMode: Readonly<Ref<boolean>>;
  systemHookRegistrationLoading: Readonly<Ref<boolean>>;
  systemHookRegistration: Readonly<Ref<GitlabSystemHookRegistrationStatus | null>>;
}

/**
 * 将镜像健康和 System Hook 响应转换为设置页展示状态，不修改来源响应。
 */
export function useMirrorSourceHealthPresentation(
  deps: MirrorSourceHealthPresentationDependencies,
) {
  const currentSourceHealth = computed(() => {
    const healthItems = Array.isArray(deps.sourceHealth.value) ? deps.sourceHealth.value : [];
    return healthItems.find((item) => item.configId === deps.selectedConfigId.value);
  });

  const currentFactLaggingDomains = computed(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return [];
    }
    const domains: string[] = [];
    if (health.mergeRequestFactLagging) {
      domains.push('代码走查事实');
    }
    if (health.issueFactLagging) {
      domains.push('系统测试/客户问题事实');
    }
    return domains;
  });

  const currentSourceHealthTone = computed<MirrorSourceHealthTone>(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return 'info';
    }
    if (
      health.missingRequiredMirrorTables.length > 0
      || health.latestLogStatus === 'FAILED'
      || health.latestLogStatus === 'TIMEOUT'
    ) {
      return 'danger';
    }
    if (
      health.factLayerLagging
      || health.latestLogStatus === 'PARTIAL_SUCCESS'
      || ['RUNNING', 'QUEUED', 'RETRYING'].includes(health.currentStatus)
    ) {
      return 'warning';
    }
    if (!health.enabled) {
      return 'info';
    }
    return 'success';
  });

  const currentSourceHealthText = computed(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return '暂无诊断';
    }
    if (!health.enabled) {
      return '已停用';
    }
    if (health.missingRequiredMirrorTables.length > 0) {
      return '镜像不完整';
    }
    if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
      return '同步异常';
    }
    if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
      return '部分表异常';
    }
    if (health.factLayerLagging) {
      return '事实层滞后';
    }
    if (['RUNNING', 'QUEUED', 'RETRYING'].includes(health.currentStatus)) {
      return '同步中';
    }
    return '健康';
  });

  const currentSourceHealthSummary = computed(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return '当前数据源还没有健康诊断结果。';
    }
    if (!health.enabled) {
      return '该数据源已停用，不会参与自动同步。';
    }
    if (health.missingRequiredMirrorTables.length > 0) {
      return '关键镜像表缺失，代码走查相关数据可能无法完整展示。';
    }
    if (health.factLayerLagging) {
      return '镜像数据已经更新，但部分展示或统计使用的事实层还没有刷新到最新。';
    }
    if (health.latestLogStatus === 'FAILED' || health.latestLogStatus === 'TIMEOUT') {
      return health.latestLogMessage || '最近一次同步未成功，请查看同步日志并重新触发。';
    }
    if (health.latestLogStatus === 'PARTIAL_SUCCESS') {
      return health.latestLogMessage || '最近一次同步部分表未成功，系统会继续按表级任务恢复。';
    }
    return '镜像表、事实层和最近同步状态未发现阻断问题。';
  });

  const currentSourceLatestSyncStatusText = computed(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return '-';
    }
    const rawStatus = health.latestLogStatus || health.currentStatus;
    return rawStatus ? syncStatusText(rawStatus) : '-';
  });

  const currentSourceHealthMessageText = computed(() => {
    const health = currentSourceHealth.value;
    if (!health) {
      return '';
    }
    return translateSyncMessage(health.latestLogMessage || health.currentMessage) || '';
  });

  const missingRequiredMirrorTablesPreview = computed(() => {
    const tables = currentSourceHealth.value?.missingRequiredMirrorTables ?? [];
    return {
      visible: tables.slice(0, 5),
      hiddenCount: Math.max(tables.length - 5, 0),
    };
  });

  const systemHookStatusTagType = computed(() => {
    if (!deps.isDockerMode.value || deps.systemHookRegistrationLoading.value) {
      return 'info';
    }
    if (deps.systemHookRegistration.value?.registered) {
      return 'success';
    }
    return deps.systemHookRegistration.value?.configured ? 'warning' : 'info';
  });

  const systemHookStatusLabel = computed(() => {
    if (deps.systemHookRegistrationLoading.value) {
      return '检测中';
    }
    if (!deps.isDockerMode.value) {
      return '需手动注册';
    }
    if (deps.systemHookRegistration.value?.registered) {
      return '已注册';
    }
    return deps.systemHookRegistration.value?.configured ? '未注册' : '未配置';
  });

  const systemHookStatusMessage = computed(() => {
    if (deps.systemHookRegistrationLoading.value) {
      return '正在异步检测 GitLab System Hook 状态，不影响页面其他信息加载。';
    }
    if (!deps.isDockerMode.value) {
      return '直连模式需在 GitLab 管理后台手动注册 System Hook，平台无法自动检测注册状态。';
    }
    return deps.systemHookRegistration.value?.message || '尚未检测 GitLab System Hook 状态。';
  });

  return {
    currentSourceHealth,
    currentFactLaggingDomains,
    currentSourceHealthTone,
    currentSourceHealthText,
    currentSourceHealthSummary,
    currentSourceLatestSyncStatusText,
    currentSourceHealthMessageText,
    missingRequiredMirrorTablesPreview,
    systemHookStatusTagType,
    systemHookStatusLabel,
    systemHookStatusMessage,
  };
}
