package com.data.collection.platform.service;

/** 事实构建任务已失去执行权（父运行授权或任务自身租约），当前执行者必须停止后续写入。 */
public class FactTaskLeaseLostException extends RuntimeException {
  public FactTaskLeaseLostException(long taskId, String reason) {
    super("事实构建任务执行权已失效（" + reason + "）：" + taskId);
  }
}
