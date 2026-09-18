package cn.edu.lostfound.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTest {
  @Test void validSameOriginConfigurationPasses() {
    assertDoesNotThrow(() -> ProductionConfiguration.validate(valid()));
  }

  @Test void validExplicitHttpsOriginPasses() {
    MockEnvironment environment = valid().withProperty("app.web.allowed-origins", "https://lost.example.edu");
    assertDoesNotThrow(() -> ProductionConfiguration.validate(environment));
  }

  @Test void unsafeProductionOverridesAreRejected() {
    rejects(e -> e.addActiveProfile("integration"));
    rejects(e -> e.withProperty("app.campus.test-mode", "true"));
    rejects(e -> e.withProperty("server.address", "0.0.0.0"));
    rejects(e -> e.withProperty("server.port", "0"));
    rejects(e -> e.withProperty("server.port", "not-a-port"));
    rejects(e -> e.withProperty("server.forward-headers-strategy", "framework"));
    rejects(e -> e.withProperty("server.error.include-message", "always"));
    rejects(e -> e.withProperty("server.error.include-binding-errors", "on_param"));
    rejects(e -> e.withProperty("server.error.include-stacktrace", "always"));
    rejects(e -> e.withProperty("app.proxy.trusted-addresses", "192.0.2.10"));
    rejects(e -> e.withProperty("app.proxy.trusted-addresses", "localhost"));
    rejects(e -> e.withProperty("ai.enabled", "true"));
    rejects(e -> e.withProperty("app.session-ttl-hours", "25"));
    rejects(e -> e.withProperty("app.rate-limit.login-per-minute", "0"));
    rejects(e -> e.withProperty("app.rate-limit.register-per-minute", "61"));
    rejects(e -> e.withProperty("app.rate-limit.verification-per-minute", "1000"));
    rejects(e -> e.withProperty("spring.jpa.hibernate.ddl-auto", "update"));
    rejects(e -> e.withProperty("spring.jpa.open-in-view", "true"));
    rejects(e -> e.withProperty("spring.jpa.show-sql", "true"));
    rejects(e -> e.withProperty("spring.jpa.generate-ddl", "true"));
    rejects(e -> e.withProperty("spring.flyway.enabled", "false"));
    rejects(e -> e.withProperty("spring.flyway.baseline-on-migrate", "true"));
    rejects(e -> e.withProperty("spring.flyway.clean-disabled", "false"));
    rejects(e -> e.withProperty("spring.sql.init.mode", "always"));
    rejects(e -> e.withProperty("spring.data.redis.port", "0"));
    rejects(e -> e.withProperty("spring.data.redis.database", "64"));
    rejects(e -> e.withProperty("app.media.cleanup-enabled", "false"));
    rejects(e -> e.withProperty("app.media.root", ".local/media"));
    rejects(e -> e.withProperty("app.web.allowed-origins", "http://127.0.0.1:5174"));
    rejects(e -> e.withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://alternate/db"));
    rejects(e -> e.withProperty("spring.data.redis.url", "redis://alternate"));
    rejects(e -> e.withProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "update"));
  }

  @Test void requiredSecretsCannotBeBlank() {
    rejects(e -> e.withProperty("spring.datasource.password", " "));
    rejects(e -> e.withProperty("spring.data.redis.password", ""));
  }

  @Test void profileGuardRunsDuringBeanFactoryPostProcessing() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("production");
      context.getEnvironment().getPropertySources().addFirst(valid().getPropertySources().get("mockProperties"));
      context.register(ProductionConfiguration.class);
      assertDoesNotThrow(context::refresh);
    }
  }

  private static void rejects(Consumer<MockEnvironment> mutation) {
    MockEnvironment environment = valid();
    mutation.accept(environment);
    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> ProductionConfiguration.validate(environment));
    assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank());
  }

  private static MockEnvironment valid() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("app.campus.test-mode", "false")
        .withProperty("server.address", "127.0.0.1")
        .withProperty("server.port", "8080")
        .withProperty("server.forward-headers-strategy", "none")
        .withProperty("server.error.include-message", "never")
        .withProperty("server.error.include-binding-errors", "never")
        .withProperty("server.error.include-stacktrace", "never")
        .withProperty("app.proxy.trusted-addresses", "127.0.0.1")
        .withProperty("ai.enabled", "false")
        .withProperty("app.session-ttl-hours", "24")
        .withProperty("app.rate-limit.login-per-minute", "60")
        .withProperty("app.rate-limit.register-per-minute", "30")
        .withProperty("app.rate-limit.verification-per-minute", "12")
        .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
        .withProperty("spring.jpa.open-in-view", "false")
        .withProperty("spring.jpa.show-sql", "false")
        .withProperty("spring.jpa.generate-ddl", "false")
        .withProperty("spring.flyway.enabled", "true")
        .withProperty("spring.flyway.baseline-on-migrate", "false")
        .withProperty("spring.flyway.clean-disabled", "true")
        .withProperty("spring.sql.init.mode", "never")
        .withProperty("spring.datasource.url", "jdbc:mysql://db.example.invalid:3306/lost_found")
        .withProperty("spring.datasource.username", "lost_found_app")
        .withProperty("spring.datasource.password", "synthetic-db-secret")
        .withProperty("spring.data.redis.host", "redis.example.invalid")
        .withProperty("spring.data.redis.port", "6379")
        .withProperty("spring.data.redis.database", "0")
        .withProperty("spring.data.redis.password", "synthetic-redis-secret")
        .withProperty("app.media.cleanup-enabled", "true")
        .withProperty("app.media.root", Path.of(System.getProperty("java.io.tmpdir"), "campus-media").toAbsolutePath().toString())
        .withProperty("app.web.allowed-origins", "");
    environment.setActiveProfiles("production");
    return environment;
  }
}
