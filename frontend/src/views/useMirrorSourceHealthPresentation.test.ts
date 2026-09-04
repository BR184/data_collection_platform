import { describe, expect, it } from 'vitest';
import { ref } from 'vue';
import type {
  GitlabSourceHealthResponse,
  GitlabSystemHookRegistrationStatus,
} from '../types/api';
import { useMirrorSourceHealthPresentation } from './useMirrorSourceHealthPresentation';

function createHealth(overrides: Partial<GitlabSourceHealthResponse> = {}): GitlabSourceHealthResponse {
  return {
    configId: 1,
    name: 'GitLab default source',
    sourceInstance: 'default',
    enabled: true,
    currentStatus: 'IDLE',
    currentMessage: null,
    latestLogStatus: null,
    latestLogMessage: null,
    latestLogFinishedAt: null,
    registeredMirrorTables: 10,
    existingMirrorTables: 10,
    factLayerLagging: false,
    factLayerMessage: null,
    latestFactUpdatedAt: null,
    mergeRequestFactLagging: false,
    issueFactLagging: false,
    mergeRequestFactCount: 10,
    issueFactCount: 10,
    missingRequiredMirrorTables: [],
    ...overrides,
  };
}

function createRegistration(
  overrides: Partial<GitlabSystemHookRegistrationStatus> = {},
): GitlabSystemHookRegistrationStatus {
  return {
    supported: true,
    configured: false,
    registered: false,
    projectId: null,
    systemHookUrl: 'http://localhost:18080/api/gitlab-sync/system-hook',
    message: '未检测',
    hooks: [],
    ...overrides,
  };
}

describe('useMirrorSourceHealthPresentation', () => {
  it('selects the current source and maps lagging facts and missing tables', () => {
    const sourceHealth = ref([
      createHealth({
        configId: 2,
        name: 'Other source',
      }),
      createHealth({
        missingRequiredMirrorTables: ['issues', 'merge_requests', 'notes', 'labels', 'users', 'projects'],
        mergeRequestFactLagging: true,
        issueFactLagging: true,
      }),
    ]);
    const presentation = useMirrorSourceHealthPresentation({
      sourceHealth,
      selectedConfigId: ref(1),
      isDockerMode: ref(true),
      systemHookRegistrationLoading: ref(false),
      systemHookRegistration: ref(createRegistration()),
    });

    expect(presentation.currentSourceHealth.value?.configId).toBe(1);
    expect(presentation.currentFactLaggingDomains.value).toEqual(['代码走查事实', '系统测试/客户问题事实']);
    expect(presentation.currentSourceHealthTone.value).toBe('danger');
    expect(presentation.currentSourceHealthText.value).toBe('镜像不完整');
    expect(presentation.missingRequiredMirrorTablesPreview.value).toEqual({
      visible: ['issues', 'merge_requests', 'notes', 'labels', 'users'],
      hiddenCount: 1,
    });
  });

  it('keeps health status priority and translates the latest message', () => {
    const health = ref([
      createHealth({
        latestLogStatus: 'FAILED',
        latestLogMessage: 'Sync completed successfully',
        currentStatus: 'RUNNING',
      }),
    ]);
    const presentation = useMirrorSourceHealthPresentation({
      sourceHealth: health,
      selectedConfigId: ref(1),
      isDockerMode: ref(true),
      systemHookRegistrationLoading: ref(false),
      systemHookRegistration: ref(createRegistration()),
    });

    expect(presentation.currentSourceHealthTone.value).toBe('danger');
    expect(presentation.currentSourceHealthText.value).toBe('同步异常');
    expect(presentation.currentSourceLatestSyncStatusText.value).toBe('需要处理');
    expect(presentation.currentSourceHealthMessageText.value).toBe('同步已完成');
    expect(presentation.currentSourceHealthSummary.value).toBe('Sync completed successfully');
  });

  it('renders System Hook status for loading, direct, configured, and registered states', () => {
    const isDockerMode = ref(true);
    const loading = ref(true);
    const registration = ref<GitlabSystemHookRegistrationStatus | null>(null);
    const presentation = useMirrorSourceHealthPresentation({
      sourceHealth: ref([]),
      selectedConfigId: ref(1),
      isDockerMode,
      systemHookRegistrationLoading: loading,
      systemHookRegistration: registration,
    });

    expect(presentation.systemHookStatusTagType.value).toBe('info');
    expect(presentation.systemHookStatusLabel.value).toBe('检测中');

    loading.value = false;
    isDockerMode.value = false;
    expect(presentation.systemHookStatusLabel.value).toBe('需手动注册');
    expect(presentation.systemHookStatusMessage.value).toContain('直连模式');

    isDockerMode.value = true;
    registration.value = createRegistration({ configured: true, message: '未注册' });
    expect(presentation.systemHookStatusTagType.value).toBe('warning');
    expect(presentation.systemHookStatusLabel.value).toBe('未注册');

    registration.value = createRegistration({ configured: true, registered: true, message: '已注册' });
    expect(presentation.systemHookStatusTagType.value).toBe('success');
    expect(presentation.systemHookStatusLabel.value).toBe('已注册');
    expect(presentation.systemHookStatusMessage.value).toBe('已注册');
  });
});
