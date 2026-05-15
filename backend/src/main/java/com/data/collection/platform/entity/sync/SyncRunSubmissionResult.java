package com.data.collection.platform.entity.sync;

import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncSubmissionAction;
import com.data.collection.platform.entity.SyncType;
import java.time.LocalDateTime;

public record SyncRunSubmissionResult(
    Long runId,
    SyncType type,
    SyncStatus status,
    SyncSubmissionAction action,
    LocalDateTime submittedAt,
    String message) {}
