package com.data.collection.platform.service.backup;

import java.time.Instant;

/** 一次备份运行的领域表示，对应 backup_runs 一行；仅用于状态与历史展示的读取侧。 */
public record BackupRun(
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
    String errorMessage) {

  public static final String STATUS_RUNNING = "RUNNING";
  public static final String STATUS_SUCCESS = "SUCCESS";
  public static final String STATUS_FAILED = "FAILED";
}
