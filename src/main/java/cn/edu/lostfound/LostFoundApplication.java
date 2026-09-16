package cn.edu.lostfound;

import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.service.AuthService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableConfigurationProperties(CampusProperties.class)
public class LostFoundApplication {
  public static void main(String[] args) { SpringApplication.run(LostFoundApplication.class, args); }
  @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
  @Bean Clock clock() { return Clock.systemUTC(); }
  @Bean CommandLineRunner initAdmin(AuthService auth, @Value("${app.admin-password:}") String password) {
    return args -> auth.initializeAdmin(password);
  }
}
