package com.data.collection.platform.service;

/** 当前投影执行身份已经过期、被回收或被其他领取替代。 */
public class ProjectionTaskLeaseLostException extends RuntimeException {
  public ProjectionTaskLeaseLostException(long taskId) {
    super("事实投影任务租约已失效：" + taskId);
  }
}
