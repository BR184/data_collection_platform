package com.data.collection.platform.entity.backup;

/** 备份触发结果：accepted=false 表示已有运行在执行（业务级拒绝，非异常）。 */
public record BackupTriggerResponse(boolean accepted, Long runId, String message) {}
