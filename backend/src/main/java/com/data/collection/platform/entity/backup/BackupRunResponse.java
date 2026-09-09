package com.data.collection.platform.entity.backup;

import java.time.Instant;

/** 一次备份运行的展示视图；FAILED 时 errorMessage 为页面展示的失败原因。 */
public record BackupRunResponse(
    long id,
    String triggerType,
    String status,
    String storageMode,
    String stage,
    String targetPath,
    String fileName,
    Long fileBytes,
    String sha256,
    String pgServerVersion,
    String flywayVersion,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    String errorMessage) {}
