package cn.edu.lostfound.service;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.AuthDtos;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.verification.VerificationApi;
import cn.edu.lostfound.verification.VerificationDtos.VerificationSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthServiceTest {
  private final UserRepository users = mock(UserRepository.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final VerificationApi verification = mock(VerificationApi.class);
  private final CampusProperties campus = new CampusProperties("TEST_CAMPUS", null,
      "Asia/Shanghai", true, "Synthetic test instructions", null);
  private final Clock clock = Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC);
  private final AuthService auth = new AuthService(users, encoder, redis, verification, campus, clock, 24);

  @ParameterizedTest
  @ValueSource(strings = {"admin", "ADMIN", "Admin", "admin "})
  void administratorUsernameCannotBeClaimedThroughRegistration(String username) {
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register(username, "password123", "同学")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("该用户名不可注册");
    verifyNoInteractions(users, redis, verification);
  }

  @Test
  void registrationRejectsPasswordOver72Utf8Bytes() {
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register("student", "密".repeat(25), "同学")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 个 UTF-8 字节");
    verifyNoInteractions(users, redis, verification);
  }

  @Test
  void passwordAtUtf8LimitCanRegisterAndLoginWithCurrentQualification() {
    String password = "密".repeat(24);
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
      User saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 7L);
      return saved;
    });
    auth.register(new AuthDtos.Register("student", password, "同学"));
    ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
    var order = inOrder(users, verification);
    order.verify(users).existsByUsername("student");
    order.verify(users).saveAndFlush(captured.capture());
    order.verify(verification).initialize(7L);
    User user = captured.getValue();
    assertThat(user.isTest()).isTrue();
    assertThat(user.getRole()).isEqualTo("USER");
    assertThat(user.getVersion()).isZero();
    assertThat(user.getPassword()).isNotEqualTo(password);
    assertThat(encoder.matches(password, user.getPassword())).isTrue();
    when(users.findByUsername("student")).thenReturn(Optional.of(user));
    VerificationSummary summary = mock(VerificationSummary.class);
    when(verification.summary(7L)).thenReturn(summary);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);

    Map<String, Object> result = auth.login(new AuthDtos.Login("student", password));

    assertThat(result).containsEntry("userId", 7L).containsEntry("role", "USER")
        .containsEntry("nickname", "同学").containsEntry("verification", summary);
    assertThat(result.get("token")).asString().matches("[a-f0-9]{32}");
    // Redis stores only the locator, never a role or qualification authorization snapshot.
    verify(values).set("session:" + result.get("token"), "7", Duration.ofHours(24));
  }

  @Test
  void duplicateUsernameReturnsConflictWithoutWriting() {
    when(users.existsByUsername("student")).thenReturn(true);
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register("student", "password123", "同学")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(409);
          assertThat(error.getErrorCode()).isEqualTo("USERNAME_EXISTS");
        });
    verify(users, never()).saveAndFlush(any());
    verifyNoInteractions(redis, verification);
  }

  @Test
  void concurrentDuplicateConstraintHasSameSafeConflict() {
    when(users.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("synthetic internal SQL detail"));
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register("student", "password123", "同学")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(409);
          assertThat(error.getErrorCode()).isEqualTo("USERNAME_EXISTS");
          assertThat(error.getMessage()).doesNotContain("SQL");
        });
    verifyNoInteractions(redis, verification);
  }

  @Test
  void loginRejectsOverlongPasswordWithoutCreatingSession() {
    assertInvalidCredentials(() -> auth.login(new AuthDtos.Login("student", "密".repeat(24) + "suffix")));
    verifyNoInteractions(users, redis, verification);
  }

  @Test
  void missingAccountAndIncorrectPasswordReturnIdenticalSafeCredentialErrors() {
    assertInvalidCredentials(() -> auth.login(new AuthDtos.Login("missing", "password123")));
    User user = new User("student", encoder.encode("password123"), "同学", "USER");
    user.initializeEnvironment(true, clock.instant());
    when(users.findByUsername("student")).thenReturn(Optional.of(user));
    assertInvalidCredentials(() -> auth.login(new AuthDtos.Login("student", "incorrect-password")));
    verifyNoInteractions(redis, verification);
  }

  @Test
  void missingQualificationCannotCreateSession() {
    User user = new User("student", encoder.encode("password123"), "同学", "USER");
    user.initializeEnvironment(true, clock.instant());
    ReflectionTestUtils.setField(user, "id", 7L);
    when(users.findByUsername("student")).thenReturn(Optional.of(user));
    when(verification.summary(7L)).thenThrow(new BusinessException(503, "SERVICE_UNAVAILABLE", "资格记录不可用"));

    assertThatThrownBy(() -> auth.login(new AuthDtos.Login("student", "password123")))
        .isInstanceOf(BusinessException.class);

    verifyNoInteractions(redis);
  }

  @Test
  void correctPasswordCannotCreateSessionForAnAccountFromAnotherEnvironment() {
    User user = new User("student", encoder.encode("password123"), "同学", "ADMIN");
    user.initializeEnvironment(false, clock.instant());
    ReflectionTestUtils.setField(user, "id", 7L);
    when(users.findByUsername("student")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> auth.login(new AuthDtos.Login("student", "password123")))
        .isInstanceOfSatisfying(BusinessException.class, error -> {
          assertThat(error.getHttpStatus()).isEqualTo(403);
          assertThat(error.getErrorCode()).isEqualTo("FORBIDDEN");
        });
    verifyNoInteractions(verification, redis);
  }

  @Test
  void failedQualificationInitializationFailsRegistrationInsteadOfReportingSuccess() {
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
      User saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 7L);
      return saved;
    });
    doThrow(new BusinessException(503, "SERVICE_UNAVAILABLE", "资格存储不可用"))
        .when(verification).initialize(7L);

    assertThatThrownBy(() -> auth.register(new AuthDtos.Register("student", "password123", "同学")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(redis);
  }

  @Test
  void logoutDeletesOnlyThePresentedSessionAndAbsentTokenIsIdempotent() {
    auth.logout(null);
    auth.logout(" ");
    verifyNoInteractions(redis);
    auth.logout("synthetic-token");
    verify(redis).delete("session:synthetic-token");
    verifyNoInteractions(users, verification);
  }

  private void assertInvalidCredentials(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, error -> {
      assertThat(error.getHttpStatus()).isEqualTo(401);
      assertThat(error.getErrorCode()).isEqualTo("INVALID_CREDENTIALS");
      assertThat(error.getMessage()).isEqualTo("用户名或密码错误");
    });
  }
}
