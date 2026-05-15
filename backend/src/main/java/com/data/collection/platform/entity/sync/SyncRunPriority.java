package com.data.collection.platform.entity.sync;

public enum SyncRunPriority {
  FULL_SYNC(100),
  SYSTEM_HOOK(60),
  TABLE_REFRESH(40),
  COMPENSATION_SCAN(20),
  FACT_REFRESH(10);

  private final int value;

  SyncRunPriority(int value) {
    this.value = value;
  }

  public int value() {
    return value;
  }
}
