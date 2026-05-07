package com.data.collection.platform.entity;

import java.time.LocalDateTime;
import java.util.List;

public record GitlabSourceHealthResponse(
    Long configId,
    String name,
    String sourceInstance,
    boolean enabled,
    SyncStatus currentStatus,
    String currentMessage,
    LocalDateTime currentStartedAt,
    SyncStatus latestLogStatus,
    String latestLogMessage,
    LocalDateTime latestLogFinishedAt,
    int registeredMirrorTables,
    int existingMirrorTables,
    boolean factLayerLagging,
    String factLayerMessage,
    LocalDateTime latestFactUpdatedAt,
    long mergeRequestFactCount,
    long issueFactCount,
    long integrationTestFactCount,
    List<String> missingRequiredMirrorTables) {}
