package com.data.collection.platform.bi.infrastructure;

/** 同一页面读取期间平台发布了新来源版本时中止旧新数据拼接。 */
public final class BiSourceVersionChangedException extends RuntimeException {
  public BiSourceVersionChangedException(String pageKey) {
    super(pageKey + " 页面读取期间来源版本发生变化，请重新刷新整页");
  }
}
