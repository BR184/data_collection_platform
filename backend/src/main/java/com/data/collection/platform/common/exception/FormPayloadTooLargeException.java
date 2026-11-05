package com.data.collection.platform.common.exception;

/** 表单请求体超过服务端上限；REST 层固定映射为 413。 */
public class FormPayloadTooLargeException extends RuntimeException {

  public FormPayloadTooLargeException(String message) {
    super(message);
  }
}
