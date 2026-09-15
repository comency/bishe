package cn.edu.lostfound;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import java.nio.charset.StandardCharsets;

@SpringBootApplication
public class LostFoundApplication {
  public static void main(String[] args) { SpringApplication.run(LostFoundApplication.class, args); }
  @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean CommandLineRunner initAdmin(UserRepository users, BCryptPasswordEncoder encoder,
      @Value("${app.admin-password:}") String adminPassword) {
    return args -> {
      if (adminPassword == null || adminPassword.isBlank() || users.existsByUsername("admin")) {
        return;
      }
      if (adminPassword.length() < 6 || adminPassword.length() > 64
          || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
        throw new IllegalArgumentException("ADMIN_PASSWORD 必须为 6–64 个字符，且不超过 72 个 UTF-8 字节");
      }
      users.save(new User("admin", encoder.encode(adminPassword), "管理员", "ADMIN"));
    };
  }
}
