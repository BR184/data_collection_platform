package com.data.collection.platform.entity.sync;

public enum SyncRunStatus {
  SUBMITTED,
  QUEUED,
  RUNNING,
  RETRYING,
  CANCELLING,
  SUCCESS,
  PARTIAL_SUCCESS,
  FAILED,
  CANCELLED,
  TIMEOUT,
  MERGED
}
