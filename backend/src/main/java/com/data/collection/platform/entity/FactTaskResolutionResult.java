package com.data.collection.platform.entity;

/**
 * 人工处置命令的结果。
 *
 * @param taskId 被处置的任务
 * @param kind 任务类型
 * @param originalRunId 任务原运行编号（字符串形式，事实任务与投影任务共用）
 * @param newRunId 继续时新建立的 {@code FACT_REFRESH} 运行编号；取消时为 {@code null}
 * @param disposition 处置后的持久状态
 * @param remainingPendingUpdates 该来源（事实任务限定到本事实族）仍有待发布的根数量
 */
public record FactTaskResolutionResult(
    long taskId,
    FactTaskKind kind,
    String originalRunId,
    String newRunId,
    FactManualDisposition disposition,
    long remainingPendingUpdates) {}
