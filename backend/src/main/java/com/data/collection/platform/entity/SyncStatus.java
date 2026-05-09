package com.data.collection.platform.entity;

public enum SyncStatus {
  IDLE,
  PENDING,
  QUEUED,
  RUNNING,
  RETRYING,
  CANCELLING,
  CANCELLED,
  SUCCESS,
  PARTIAL_SUCCESS,
  FAILED,
  TIMEOUT
}
