package com.data.collection.platform.common.exception;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(BizException.class)
  public ApiResponse<Void> handleBizException(BizException e) {
    log.warn("Business exception: {}", e.getMessage());
    return ApiResponse.fail(e.getResultCode(), e.getMessage());
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
  public ApiResponse<Void> handleBadRequest(Exception e) {
    log.warn("Bad request: {}", e.getMessage());
    return ApiResponse.fail(ResultCode.BAD_REQUEST, e.getMessage());
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.NOT_FOUND)
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ApiResponse<Void> handleNotFound(Exception e) {
    log.warn("Resource not found: {}", e.getMessage());
    return ApiResponse.fail(ResultCode.NOT_FOUND, "请求资源不存在");
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
    log.warn("HTTP method not supported: {}", e.getMessage());
    return ApiResponse.fail(ResultCode.BAD_REQUEST, "请求方法不支持");
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(Exception.class)
  public ApiResponse<Void> handleException(Exception e) {
    log.error("Unhandled exception", e);
    return ApiResponse.fail(ResultCode.SYSTEM_ERROR, "服务处理异常，请联系开发人员排查");
  }
}
