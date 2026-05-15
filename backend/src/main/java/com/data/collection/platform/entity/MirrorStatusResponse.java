package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record MirrorStatusResponse(
    GitlabSyncConfig config,
    Map<String, Object> currentTask,
    SyncStatus currentStatus,
    String currentMessage,
    LocalDateTime currentStartedAt,
    SyncProgress progress,
    List<Map<String, Object>> logs,
    String systemHookUrl,
    GitlabSystemHookRegistrationStatus systemHookRegistration) {

  public MirrorStatusResponse {
    logs = logs == null ? List.of() : List.copyOf(logs);
  }
}
