package com.data.collection.platform.service;

import java.time.LocalDateTime;

record RealtimeWorkspaceRefreshProgress(
    Long mirrorRunId,
    String mirrorStatus,
    String factStatus,
    boolean factRefreshRequired,
    LocalDateTime startedAt,
    LocalDateTime finishedAt) {
}
