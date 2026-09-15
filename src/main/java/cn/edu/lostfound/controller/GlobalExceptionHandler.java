package cn.edu.lostfound.controller;

import cn.edu.lostfound.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message = "请求无效";
    if (ex instanceof MethodArgumentNotValidException validation) {
      message = validation.getBindingResult().getFieldErrors().stream()
          .findFirst()
          .map(error -> error.getField() + ": " + error.getDefaultMessage())
          .orElse("请求参数校验失败");
    } else if (status.is5xxServerError()) {
      log.error("请求处理失败", ex);
      message = "服务器错误，请稍后重试";
    }
    return super.handleExceptionInternal(ex, ApiResponse.fail(message), headers, status, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getMessage()));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ApiResponse<Void>> forbidden(SecurityException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> other(Exception ex) {
    log.error("请求处理失败", ex);
    return ResponseEntity.internalServerError().body(ApiResponse.fail("服务器错误，请稍后重试"));
  }
}
