package com.data.collection.platform.common.exception;

import com.data.collection.platform.common.response.ResultCode;

/** 表单资源不存在；REST 层固定映射为 404/A0404。 */
public class FormResourceNotFoundException extends BizException {

  public FormResourceNotFoundException(String message) {
    super(ResultCode.NOT_FOUND, message);
  }
}
