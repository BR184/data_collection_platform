package com.data.collection.platform.entity.sync;

public enum SyncRunType {
  FULL_SYNC,
  INCREMENTAL_SYNC,
  TABLE_REFRESH,
  SYSTEM_HOOK,
  COMPENSATION_SCAN,
  FULL_COMPENSATION_SCAN,
  FACT_REFRESH
}
