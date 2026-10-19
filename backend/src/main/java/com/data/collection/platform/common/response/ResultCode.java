package com.data.collection.platform.common.response;

public enum ResultCode {
  SUCCESS("00000", "成功"),
  UNAUTHORIZED("A0301", "未登录"),
  FORBIDDEN("A0303", "无权限"),
  BAD_REQUEST("A0400", "请求参数错误"),
  NOT_FOUND("A0404", "资源不存在"),
  CONFLICT("A0409", "数据已被他人修改"),
  READ_VIEW_UNAVAILABLE("READ_VIEW_UNAVAILABLE", "尚无完整产出"),
  READ_VIEW_REBIND_REQUIRED("READ_VIEW_REBIND_REQUIRED", "页面读取身份需要更新"),
  BIZ_ERROR("B0001", "业务处理失败"),
  SYSTEM_ERROR("C0001", "系统异常");

  private final String code;
  private final String message;

  ResultCode(String code, String message) {
    this.code = code;
    this.message = message;
  }

  public String getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }
}
