package cn.edu.lostfound.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

public record ApiResponse<T>(int code, String message, T data,
    @JsonInclude(JsonInclude.Include.NON_NULL) String errorCode,
    @JsonInclude(JsonInclude.Include.NON_NULL) String traceId) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "success", data, null, null);
  }
  public static <T> ApiResponse<T> fail(String message) {
    return fail("VALIDATION_ERROR", message);
  }
  public static <T> ApiResponse<T> fail(String errorCode, String message) {
    return new ApiResponse<>(-1, message, null, errorCode, UUID.randomUUID().toString());
  }
}
