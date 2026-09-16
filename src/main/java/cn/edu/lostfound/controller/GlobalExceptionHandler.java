package cn.edu.lostfound.controller;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String message="请求无效";
    if (ex instanceof MethodArgumentNotValidException validation) {
      message=validation.getBindingResult().getFieldErrors().stream().findFirst()
          .map(error -> error.getField()+": "+error.getDefaultMessage()).orElse("请求参数校验失败");
    } else if (status.is5xxServerError()) message="服务器错误，请稍后重试";
    return super.handleExceptionInternal(ex,
        ApiResponse.fail(status.value()==413 ? "PAYLOAD_TOO_LARGE" : status.is5xxServerError() ? "SERVICE_ERROR" : "VALIDATION_ERROR", message),
        headers, status, request);
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
    return ResponseEntity.status(ex.getHttpStatus()).body(ApiResponse.fail(ex.getErrorCode(), ex.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> bad(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", ex.getMessage()));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<Void>> validation(ConstraintViolationException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.fail("VALIDATION_ERROR", "请求参数不符合限制"));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ApiResponse<Void>> forbidden(SecurityException ex) {
    return ResponseEntity.status(403).body(ApiResponse.fail("FORBIDDEN", ex.getMessage()));
  }

  @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
  public ResponseEntity<ApiResponse<Void>> conflict(Exception ex) {
    return ResponseEntity.status(409).body(ApiResponse.fail("VERSION_CONFLICT", "数据已改变或正在处理，请刷新后重试"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiResponse<Void>> integrity(DataIntegrityViolationException ex) {
    return ResponseEntity.status(409).body(ApiResponse.fail("STATE_CONFLICT", "当前状态不允许该操作，请刷新后重试"));
  }

  @ExceptionHandler({DataAccessResourceFailureException.class, QueryTimeoutException.class,
      CannotCreateTransactionException.class, org.springframework.data.redis.RedisConnectionFailureException.class})
  public ResponseEntity<ApiResponse<Void>> unavailable(Exception ex) {
    var response=ApiResponse.<Void>fail("SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后重试");
    // Exception text/SQL may contain applicant data; log only a correlation ID and exception type.
    log.warn("Dependency unavailable traceId={} type={}", response.traceId(), ex.getClass().getSimpleName());
    return ResponseEntity.status(503).body(response);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> other(Exception ex) {
    var response=ApiResponse.<Void>fail("SERVICE_ERROR", "服务器错误，请稍后重试");
    log.error("Request failed traceId={} type={}", response.traceId(), ex.getClass().getSimpleName());
    return ResponseEntity.internalServerError().body(response);
  }
}
