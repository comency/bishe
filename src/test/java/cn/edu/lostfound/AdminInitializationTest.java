package cn.edu.lostfound;

import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.service.AuthService;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminInitializationTest {
  private final LostFoundApplication application = new LostFoundApplication();
  private final UserRepository users = mock(UserRepository.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final VerificationApi verification = mock(VerificationApi.class);
  private final CampusProperties campus = new CampusProperties("TEST_CAMPUS", null,
      "Asia/Shanghai", true, "Synthetic test instructions", null);
  private final AuthService auth = new AuthService(users, encoder, redis, verification, campus,
      Clock.fixed(Instant.parse("2026-09-16T01:00:00Z"), ZoneOffset.UTC), 24);

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void missingPasswordDoesNotCreateDefaultAdministrator(String password) throws Exception {
    application.initAdmin(auth, password).run();
    verifyNoInteractions(users, verification, redis);
  }

  @Test
  void explicitPasswordCreatesHashedAdministratorAndInitialQualification() throws Exception {
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
      User saved = invocation.getArgument(0);
      ReflectionTestUtils.setField(saved, "id", 1L);
      return saved;
    });
    application.initAdmin(auth, "test-admin-password").run();

    ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
    var order = inOrder(users, verification);
    order.verify(users).existsByUsername("admin");
    order.verify(users).saveAndFlush(captured.capture());
    order.verify(verification).initialize(1L);
    User administrator = captured.getValue();
    assertThat(administrator.getUsername()).isEqualTo("admin");
    assertThat(administrator.getRole()).isEqualTo("ADMIN");
    assertThat(administrator.isTest()).isTrue();
    assertThat(administrator.getPassword()).isNotEqualTo("test-admin-password");
    assertThat(encoder.matches("test-admin-password", administrator.getPassword())).isTrue();
    verifyNoInteractions(redis);
  }

  @Test
  void existingAdministratorAndItsQualificationAreNotOverwritten() throws Exception {
    when(users.existsByUsername("admin")).thenReturn(true);
    application.initAdmin(auth, "replacement-password").run();
    verify(users, never()).saveAndFlush(any());
    verifyNoInteractions(verification, redis);
  }

  @Test
  void weakPasswordFailsBeforeWritingAdministrator() {
    assertThatThrownBy(() -> application.initAdmin(auth, "short").run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6–64 个字符");
    verify(users, never()).saveAndFlush(any());
    verifyNoInteractions(verification, redis);
  }

  @Test
  void multibytePasswordOverBcryptLimitIsRejected() {
    assertThatThrownBy(() -> application.initAdmin(auth, "密".repeat(25)).run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 个 UTF-8 字节");
    verify(users, never()).saveAndFlush(any());
    verifyNoInteractions(verification, redis);
  }

  @Test
  void passwordOverCharacterLimitIsRejected() {
    assertThatThrownBy(() -> application.initAdmin(auth, "a".repeat(65)).run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6–64 个字符");
    verify(users, never()).saveAndFlush(any());
    verifyNoInteractions(verification, redis);
  }
}
