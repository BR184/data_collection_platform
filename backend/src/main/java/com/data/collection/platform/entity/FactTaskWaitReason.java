package com.data.collection.platform.entity;

/**
 * 事实构建任务进入等待时的持久化原因码。
 *
 * <p>等待中的任务保留 {@code QUEUED} 状态与未消耗的重试预算，靠该原因码与未来 {@code run_after}
 * 表达"暂时不能执行"；它不表示失败，也不消耗失败预算。
 */
public enum FactTaskWaitReason {
  /** 来源事实依赖代际尚未就绪，等待依赖收敛后自动继续。 */
  DEPENDENCY_SETTLING
}
