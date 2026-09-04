package com.data.collection.platform.common.exception;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 平台唯一的 REST 异常映射入口。历史上存在两个重叠的 {@link RestControllerAdvice}，
 * 因注册顺序不确定，数据库异常会被通用兜底误报为"服务处理异常"；现合并为单一权威映射，
 * 让异常类别、日志与用户文案一一对应。
 */
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
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("；"));
    return ApiResponse.fail(ResultCode.BAD_REQUEST, message.isBlank() ? "请求参数错误" : message);
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler(BindException.class)
  public ApiResponse<Void> handleBindException(BindException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("；"));
    return ApiResponse.fail(ResultCode.BAD_REQUEST, message.isBlank() ? "请求参数错误" : message);
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class})
  public ApiResponse<Void> handleBadRequest(Exception e) {
    log.warn("Bad request: {}", e.getMessage());
    return ApiResponse.fail(ResultCode.BAD_REQUEST, e.getMessage());
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    HttpMessageNotReadableException.class,
    MaxUploadSizeExceededException.class
  })
  public ApiResponse<Void> handleMalformedRequest(Exception e) {
    log.warn("Malformed request: {}", e.getMessage());
    return ApiResponse.fail(ResultCode.BAD_REQUEST, sanitize(e.getMessage()));
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
  @ExceptionHandler(DataAccessException.class)
  public ApiResponse<Void> handleDataAccessException(DataAccessException e, HttpServletRequest request) {
    log.error(
        "Unhandled database exception, method={}, uri={}",
        request.getMethod(),
        request.getRequestURI(),
        e);
    return ApiResponse.fail(ResultCode.SYSTEM_ERROR, "数据库操作失败，请稍后重试");
  }

  @ResponseBody
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  @ExceptionHandler(Exception.class)
  public ApiResponse<Void> handleException(Exception e) {
    log.error("Unhandled exception", e);
    return ApiResponse.fail(ResultCode.SYSTEM_ERROR, "服务处理异常，请联系开发人员排查");
  }

  private String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "请求参数错误";
    }
    return value.length() > 160 ? value.substring(0, 160) : value;
  }
}
