package com.data.collection.platform.service.sync;

/**
 * {@link SyncRunCompletionEvent} 的监听顺序契约。
 *
 * <p>运行终态事件同时驱动两件事：来源级事实发布的登记/提交，以及依赖事实收敛的后台编排评估
 * （延期标签写回）。后者必须在**事实发布登记已提交之后**才能读取收敛判据，否则镜像增量刚刚应用、
 * 目标尚未登记时会读到"未发布目标为 0"而误判为已收敛，进而基于落后于镜像的事实发出标签写回。
 *
 * <p>因此顺序不是优化而是正确性前提：登记方永远先于评估方。改动取值时必须同时评估两个订阅方。
 */
public final class SyncRunCompletionListenerOrder {

  /** 事实发布登记：必须先执行。 */
  public static final int FACT_PUBLICATION = 0;

  /** 事实收敛的消费方（延期标签写回编排）：必须在登记之后。 */
  public static final int FACT_CONVERGENCE_CONSUMER = 100;

  private SyncRunCompletionListenerOrder() {}
}
