package com.data.collection.platform.service.sync;

/** 权威范围批次的租约在提交副作用前已经转移。 */
public class SyncAuthoritativeScopeLeaseLostException extends RuntimeException {
  public SyncAuthoritativeScopeLeaseLostException(long runId) {
    super("权威范围批次租约已失效，runId=" + runId);
  }
}
