package cn.edu.lostfound;

import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminInitializationTest {
  private final LostFoundApplication application = new LostFoundApplication();
  private final UserRepository users = mock(UserRepository.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void missingPasswordDoesNotCreateDefaultAdministrator(String password) throws Exception {
    application.initAdmin(users, encoder, password).run();
    verifyNoInteractions(users);
  }

  @Test
  void explicitPasswordCreatesAdministratorWithHash() throws Exception {
    application.initAdmin(users, encoder, "test-admin-password").run();

    ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
    verify(users).save(captured.capture());
    User administrator = captured.getValue();
    assertThat(administrator.getUsername()).isEqualTo("admin");
    assertThat(administrator.getRole()).isEqualTo("ADMIN");
    assertThat(administrator.getPassword()).isNotEqualTo("test-admin-password");
    assertThat(encoder.matches("test-admin-password", administrator.getPassword())).isTrue();
  }

  @Test
  void existingAdministratorIsNotOverwritten() throws Exception {
    when(users.existsByUsername("admin")).thenReturn(true);
    application.initAdmin(users, encoder, "replacement-password").run();
    verify(users, never()).save(any());
  }

  @Test
  void weakPasswordFailsBeforeWritingAdministrator() {
    assertThatThrownBy(() -> application.initAdmin(users, encoder, "short").run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6–64 个字符");
    verify(users, never()).save(any());
  }

  @Test
  void multibytePasswordOverBcryptLimitIsRejected() {
    assertThatThrownBy(() -> application.initAdmin(users, encoder, "密".repeat(25)).run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 个 UTF-8 字节");
    verify(users, never()).save(any());
  }

  @Test
  void passwordOverCharacterLimitIsRejected() {
    assertThatThrownBy(() -> application.initAdmin(users, encoder, "a".repeat(65)).run())
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6–64 个字符");
    verify(users, never()).save(any());
  }
}
