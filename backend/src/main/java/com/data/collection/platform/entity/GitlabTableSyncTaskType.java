package com.data.collection.platform.entity;

public enum GitlabTableSyncTaskType {
  COMPENSATION_INCREMENTAL,
  DAILY_VERIFY,
  MANUAL_REFRESH,
  FULL_REPAIR,
  SHARD_REPAIR,
  DELETE_RECONCILE
}
