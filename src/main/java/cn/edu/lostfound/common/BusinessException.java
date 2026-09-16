package cn.edu.lostfound.common;

/** Safe, client-facing failure. Never put credentials or internal evidence in its message. */
public class BusinessException extends RuntimeException {
  private final int httpStatus;
  private final String errorCode;

  public BusinessException(int httpStatus, String errorCode, String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
  }

  public int getHttpStatus() { return httpStatus; }
  public String getErrorCode() { return errorCode; }
}
