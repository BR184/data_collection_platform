package com.data.collection.platform.common.exception;

import com.data.collection.platform.common.response.ApiResponse;
import com.data.collection.platform.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Slf4j
public class GlobalRestExceptionHandler {
  @ExceptionHandler(BizException.class)
  public ApiResponse<Void> handleBizException(BizException exception) {
    return ApiResponse.fail(exception.getResultCode(), exception.getMessage());
  }

  @ExceptionHandler({
      MissingServletRequestParameterException.class,
      HttpMessageNotReadableException.class,
      MaxUploadSizeExceededException.class
  })
  public ApiResponse<Void> handleBadRequest(Exception exception) {
    return ApiResponse.fail(ResultCode.BAD_REQUEST, sanitize(exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("；"));
    return ApiResponse.fail(ResultCode.BAD_REQUEST, message.isBlank() ? "请求参数错误" : message);
  }

  @ExceptionHandler(BindException.class)
  public ApiResponse<Void> handleBindException(BindException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining("；"));
    return ApiResponse.fail(ResultCode.BAD_REQUEST, message.isBlank() ? "请求参数错误" : message);
  }

  @ExceptionHandler(DataAccessException.class)
  public ApiResponse<Void> handleDataAccessException(
      DataAccessException exception, HttpServletRequest request) {
    log.error(
        "Unhandled database exception, method={}, uri={}",
        request.getMethod(),
        request.getRequestURI(),
        exception);
    return ApiResponse.fail(ResultCode.SYSTEM_ERROR, "数据库操作失败，请稍后重试");
  }

  @ExceptionHandler(Exception.class)
  public ApiResponse<Void> handleException(Exception exception, HttpServletRequest request) {
    log.error(
        "Unhandled REST exception, method={}, uri={}",
        request.getMethod(),
        request.getRequestURI(),
        exception);
    return ApiResponse.fail(ResultCode.SYSTEM_ERROR, "系统异常，请稍后重试");
  }

  private String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "请求参数错误";
    }
    return value.length() > 160 ? value.substring(0, 160) : value;
  }
}
