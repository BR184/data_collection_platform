package com.data.collection.platform.service.sync;

import java.time.LocalDateTime;
import java.util.Map;

/** 一个已持久化并可独立租赁的权威关系范围。 */
public record SyncRunAuthoritativeScope(
    long id,
    long runId,
    String sourceInstance,
    String childTable,
    String relationKey,
    String scopeSignature,
    Map<String, Object> lookupScope,
    Long taskId,
    String leaseOwner,
    LocalDateTime leaseExpiresAt,
    int retryCount,
    int maxRetryCount) {
  public SyncRunAuthoritativeScope {
    lookupScope = lookupScope == null ? Map.of() : Map.copyOf(lookupScope);
  }
}
