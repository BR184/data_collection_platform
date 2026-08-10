package com.data.collection.platform.bi.infrastructure;

/** CAT 网络、HTTP、业务信封或反序列化失败。 */
public final class BiCatUpstreamException extends RuntimeException {
  public BiCatUpstreamException(String message) {
    super(message);
  }

  public BiCatUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
