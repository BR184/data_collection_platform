package com.data.collection.platform.common.exception;

import com.data.collection.platform.common.response.ResultCode;

/** 表单结构版本、请求标识或记录版本冲突；REST 层固定映射为 409/A0409。 */
public class FormConflictException extends BizException {

  public FormConflictException(String message) {
    super(ResultCode.CONFLICT, message);
  }
}
