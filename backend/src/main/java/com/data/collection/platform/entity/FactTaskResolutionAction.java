package com.data.collection.platform.entity;

/** 人工对待处理任务采取的动作。 */
public enum FactTaskResolutionAction {
  /** 继续：把选中意图移交给可见的新事实刷新运行。 */
  RESUME,
  /** 取消：只取消本次执行意图，不改版本头、不推进发布水位。 */
  CANCEL
}
