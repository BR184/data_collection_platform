package com.data.collection.platform.entity.backup;

import java.time.Instant;

/** 备份执行状态：当前运行（运行中非空）、最近一次已结束运行与下次计划执行时刻。 */
public record BackupStatusResponse(
    boolean running,
    BackupRunResponse currentRun,
    BackupRunResponse lastCompleted,
    boolean enabled,
    String scheduleTime,
    Instant nextRunAt) {}
