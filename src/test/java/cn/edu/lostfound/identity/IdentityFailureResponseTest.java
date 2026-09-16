package cn.edu.lostfound.identity;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityFailureResponseTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void redisFailureReturns503WithoutLeakingDependencyDetails() {
    var response = handler.unavailable(new RedisConnectionFailureException("synthetic-secret-connection-detail"));
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    assertThat(response.getBody().message()).doesNotContain("synthetic-secret");
    assertThat(response.getBody().traceId()).isNotBlank();
    assertThat(response.getBody().data()).isNull();
  }

  @Test
  void databaseVersionFailureHasRefreshableConflictEnvelope() {
    var response = handler.conflict(new OptimisticLockingFailureException("synthetic SQL details"));
    assertThat(response.getStatusCode().value()).isEqualTo(409);
    assertThat(response.getBody().errorCode()).isEqualTo("VERSION_CONFLICT");
    assertThat(response.getBody().message()).doesNotContain("SQL");
    assertThat(response.getBody().traceId()).isNotBlank();
  }

  @Test
  void invalidCredentialsUse401AndMachineReadableErrorCode() {
    var response = handler.business(new BusinessException(401, "INVALID_CREDENTIALS", "用户名或密码错误"));
    assertThat(response.getStatusCode().value()).isEqualTo(401);
    assertThat(response.getBody().code()).isEqualTo(-1);
    assertThat(response.getBody().errorCode()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(response.getBody().data()).isNull();
    assertThat(response.getBody().traceId()).isNotBlank();
  }
}
