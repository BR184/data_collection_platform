package com.data.collection.platform.entity;

/**
 * 事实构建与投影任务的人工处置状态。
 *
 * <p>该状态与自动等待、自动重试正交：{@link #NONE} 表示任务仍完全由自动路径管理；
 * 其余取值表示已在任务行上留下需要维护人员参与的决定，自动派发不得再消费该行。
 */
public enum FactManualDisposition {
  /** 无人工处置，任务由自动路径管理。 */
  NONE,
  /** 已撤销执行权并停止自动派发，等待维护人员决定继续或取消。 */
  REQUIRES_DECISION,
  /** 维护人员选择继续，工作已移交给新的可见任务。 */
  RESUMED,
  /** 维护人员取消了本次执行意图；未发布版本与待更新数据保持不变。 */
  CANCELLED;

  public boolean requiresDecision() {
    return this == REQUIRES_DECISION;
  }

  public boolean manual() {
    return this != NONE;
  }
}
