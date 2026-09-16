package cn.edu.lostfound.service;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.AuthDtos;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.verification.VerificationApi;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates account and qualification creation in one database transaction. */
@Service
public class AuthService {
  private final UserRepository users;
  private final BCryptPasswordEncoder encoder;
  private final StringRedisTemplate redis;
  private final VerificationApi verification;
  private final CampusProperties campus;
  private final Clock clock;
  private final Duration ttl;

  public AuthService(UserRepository users, BCryptPasswordEncoder encoder, StringRedisTemplate redis,
      VerificationApi verification, CampusProperties campus, Clock clock,
      @Value("${app.session-ttl-hours:24}") long hours) {
    this.users=users; this.encoder=encoder; this.redis=redis; this.verification=verification;
    this.campus=campus; this.clock=clock; this.ttl=Duration.ofHours(hours);
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void register(AuthDtos.Register request) {
    if ("admin".equalsIgnoreCase(request.username().strip())) {
      throw new IllegalArgumentException("该用户名不可注册");
    }
    validateNewPassword(request.password());
    if (users.existsByUsername(request.username())) throw usernameExists();
    createAccount(request.username(), request.password(), request.nickname(), "USER");
  }

  @Transactional(isolation=Isolation.READ_COMMITTED)
  public void initializeAdmin(String password) {
    if (password==null || password.isBlank() || users.existsByUsername("admin")) return;
    validateNewPassword(password);
    createAccount("admin", password, "管理员", "ADMIN");
  }

  private void createAccount(String username, String password, String nickname, String role) {
    User user=new User(username, encoder.encode(password), nickname, role);
    user.initializeEnvironment(campus.testMode(), clock.instant());
    try {
      users.saveAndFlush(user);
    } catch (DataIntegrityViolationException duplicate) {
      // A unique key resolves concurrent registrations without leaking database messages.
      throw usernameExists();
    }
    verification.initialize(user.getId());
  }

  public Map<String,Object> login(AuthDtos.Login request) {
    if (exceedsBcryptLimit(request.password())) throw invalidCredentials();
    User user=users.findByUsername(request.username()).orElseThrow(AuthService::invalidCredentials);
    if (!encoder.matches(request.password(), user.getPassword())) throw invalidCredentials();
    if (user.isTest()!=campus.testMode()) {
      throw new BusinessException(403, "FORBIDDEN", "账号不属于当前运行环境，请联系支持人员");
    }
    var summary=verification.summary(user.getId());
    String token=UUID.randomUUID().toString().replace("-", "");
    // Only an account locator; old id:role sessions remain readable, never authoritative.
    redis.opsForValue().set("session:"+token, user.getId().toString(), ttl);
    return Map.of("token", token, "userId", user.getId(), "username", user.getUsername(),
        "nickname", Objects.requireNonNullElse(user.getNickname(), user.getUsername()),
        "role", user.getRole(), "verification", summary);
  }

  public void logout(String token) {
    if (token!=null && !token.isBlank()) redis.delete("session:"+token);
  }

  private static void validateNewPassword(String password) {
    if (password.length()<6 || password.length()>64) {
      throw new IllegalArgumentException("密码必须为 6–64 个字符");
    }
    if (exceedsBcryptLimit(password)) throw new IllegalArgumentException("密码不能超过 72 个 UTF-8 字节");
  }
  private static boolean exceedsBcryptLimit(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length>72;
  }
  private static BusinessException invalidCredentials() {
    return new BusinessException(401, "INVALID_CREDENTIALS", "用户名或密码错误");
  }
  private static BusinessException usernameExists() {
    return new BusinessException(409, "USERNAME_EXISTS", "用户名已存在");
  }
}
