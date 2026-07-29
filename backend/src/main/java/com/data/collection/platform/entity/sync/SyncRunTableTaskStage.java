package com.data.collection.platform.entity.sync;

/** 表任务在完整镜像同步中的可恢复执行阶段。 */
public enum SyncRunTableTaskStage {
  SCAN,
  RECONCILE
}
