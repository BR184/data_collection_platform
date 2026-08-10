package com.data.collection.platform.entity.sync;

/** GitLab 来源表在日常快速增量中的唯一读取模式。 */
public enum IncrementalReadMode {
  UPDATED_AT,
  MONOTONIC_PRIMARY_KEY,
  RECONCILE_ONLY
}
