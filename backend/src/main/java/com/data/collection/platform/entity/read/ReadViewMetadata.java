package com.data.collection.platform.entity.read;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 页面租约与实时披露，响应时组装；不会写入全员共用的业务结果缓存。 */
public record ReadViewMetadata(UUID readContextId, String pageKey, String scopeKey, String sourceVersion,
    LocalDate businessDate, Instant leaseExpiresAt, Instant dataAsOf, long pendingUpdates,
    SourceState sourceState, String sourceStateDetail, boolean escalated) {
  /** 中性状态，不向普通用户暴露 FAILED/CANCELLED/BLOCKED 等生产术语。 */
  public enum SourceState { CURRENT, UPDATING, NEWER_AVAILABLE, ATTENTION }
}
