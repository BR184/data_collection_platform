package com.data.collection.platform.entity.sync;

public enum SyncRunType {
  FULL_SYNC,
  INCREMENTAL_SYNC,
  TABLE_REFRESH,
  SYSTEM_HOOK,
  FULL_COMPENSATION_SCAN,
  DELETE_RECONCILIATION,
  FACT_REFRESH
}
