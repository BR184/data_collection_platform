package com.data.collection.platform.common.response;

public class ApiResponse<T> {
  private boolean success;
  private String code;
  private String message;
  private T data;
  private com.data.collection.platform.entity.read.ReadViewMetadata readView;

  public static <T> ApiResponse<T> success(T data) {
    ApiResponse<T> response = new ApiResponse<>();
    response.success = true;
    response.code = ResultCode.SUCCESS.getCode();
    response.message = ResultCode.SUCCESS.getMessage();
    response.data = data;
    return response;
  }

  public static <T> ApiResponse<T> success(String message, T data) {
    ApiResponse<T> response = success(data);
    response.message = message;
    return response;
  }

  public static <T> ApiResponse<T> fail(ResultCode resultCode, String message) {
    ApiResponse<T> response = new ApiResponse<>();
    response.success = false;
    response.code = resultCode.getCode();
    response.message = message;
    response.data = null;
    return response;
  }

  public boolean isSuccess() {
    return success;
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }

  /** 当前请求的读取身份及实时披露，不进入共享结果缓存。 */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public com.data.collection.platform.entity.read.ReadViewMetadata getReadView() { return readView; }

  /** 为已物化业务结果附带该请求的页面身份；普通控制面响应不设置此字段。 */
  public ApiResponse<T> withReadView(com.data.collection.platform.entity.read.ReadViewMetadata metadata) {
    this.readView = metadata;
    return this;
  }
}
