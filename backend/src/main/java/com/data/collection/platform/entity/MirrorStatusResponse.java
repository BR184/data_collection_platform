package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record MirrorStatusResponse(
    GitlabSyncConfig config,
    MirrorStatusTaskView currentTask,
    SyncStatus currentStatus,
    String currentMessage,
    LocalDateTime currentStartedAt,
    SyncProgress progress,
    List<MirrorStatusLogView> logs,
    String systemHookUrl,
    GitlabWebhookRegistrationStatus systemHookRegistration,
    String webhookUrl,
    GitlabWebhookRegistrationStatus webhookRegistration) {
}
