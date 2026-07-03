package com.data.collection.platform.service;

import java.time.LocalDateTime;

record RealtimeWorkspaceSyncMetadata(
    LocalDateTime lastSyncedAt,
    LocalDateTime taskStartedAt,
    LocalDateTime taskFinishedAt) {
}
