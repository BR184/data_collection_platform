package com.data.collection.platform.service;

/** 同域候选正在由另一运行或全量模式占用；调用者让位，不消耗构建失败重试预算。 */
public class FactCandidateBusyException extends IllegalStateException {
  public FactCandidateBusyException(String message) {
    super(message);
  }
}
