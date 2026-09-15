package cn.edu.lostfound.service;

import cn.edu.lostfound.dto.AuthDtos;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AuthServiceTest {
  private final UserRepository users = mock(UserRepository.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final AuthService auth = new AuthService(users, encoder, redis, 24);

  @ParameterizedTest
  @ValueSource(strings = {"admin", "ADMIN", "Admin", "admin "})
  void administratorUsernameCannotBeClaimedThroughRegistration(String username) {
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register(username, "password123", "同学")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("该用户名不可注册");
    verifyNoInteractions(users, redis);
  }

  @Test
  void registrationRejectsPasswordOver72Utf8Bytes() {
    assertThatThrownBy(() -> auth.register(new AuthDtos.Register("student", "密".repeat(25), "同学")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("72 个 UTF-8 字节");
    verifyNoInteractions(users, redis);
  }

  @Test
  void passwordAtUtf8LimitCanRegisterAndLogin() {
    String password = "密".repeat(24);
    auth.register(new AuthDtos.Register("student", password, "同学"));
    ArgumentCaptor<User> captured = ArgumentCaptor.forClass(User.class);
    verify(users).save(captured.capture());
    User user = captured.getValue();
    ReflectionTestUtils.setField(user, "id", 7L);
    when(users.findByUsername("student")).thenReturn(Optional.of(user));
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);

    Map<String, Object> result = auth.login(new AuthDtos.Login("student", password));

    assertThat(result).containsEntry("userId", 7L).containsEntry("role", "USER");
    verify(values).set("session:" + result.get("token"), "7:USER", Duration.ofHours(24));
  }

  @Test
  void loginRejectsOverlongPasswordWithoutCreatingSession() {
    assertThatThrownBy(() -> auth.login(new AuthDtos.Login("student", "密".repeat(24) + "suffix")))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("用户名或密码错误");
    verifyNoInteractions(users, redis);
  }
}
