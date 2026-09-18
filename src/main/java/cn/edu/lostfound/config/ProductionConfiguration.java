package cn.edu.lostfound.config;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("production")
public class ProductionConfiguration {
  private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");
  private static final Set<String> LOOPBACK_PROXY_LITERALS = Set.of("127.0.0.1", "::1");

  @Bean
  static BeanFactoryPostProcessor productionConfigurationGuard(ConfigurableEnvironment environment) {
    validate(environment);
    return beanFactory -> { };
  }

  static void validate(Environment environment) {
    require(Set.copyOf(Arrays.asList(environment.getActiveProfiles())).equals(Set.of("production")),
        "Production profile cannot be combined with other profiles");
    require(!environment.getProperty("app.campus.test-mode", Boolean.class, true),
        "Production profile refuses test campus mode");
    require(LOOPBACK.contains(required(environment, "server.address").toLowerCase()),
        "Production server must bind to an explicit loopback address behind the HTTPS proxy");
    require("none".equalsIgnoreCase(required(environment, "server.forward-headers-strategy")),
        "Forwarded headers remain disabled until a trusted proxy policy is approved");
    String trustedProxy = required(environment, "app.proxy.trusted-addresses");
    require(Arrays.stream(trustedProxy.split(",", -1)).map(String::trim).allMatch(LOOPBACK_PROXY_LITERALS::contains),
        "Production trusted proxies must be loopback IP literals on the same host");
    require(!environment.getProperty("ai.enabled", Boolean.class, false),
        "Production AI remains disabled until its release gate is approved");
    require("validate".equalsIgnoreCase(required(environment, "spring.jpa.hibernate.ddl-auto")),
        "Production Hibernate schema mode must remain validate");
    require(!environment.getProperty("spring.jpa.open-in-view", Boolean.class, true),
        "Production open-in-view must remain disabled");
    require(!environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true),
        "Production Flyway baseline-on-migrate must remain disabled");
    require(environment.getProperty("spring.flyway.clean-disabled", Boolean.class, false),
        "Production Flyway clean must remain disabled");
    require(required(environment, "spring.datasource.url").startsWith("jdbc:mysql://"),
        "Production datasource must use an explicit MySQL JDBC URL");
    required(environment, "spring.datasource.username");
    required(environment, "spring.datasource.password");
    required(environment, "spring.data.redis.host");
    required(environment, "spring.data.redis.password");
    require(environment.getProperty("app.media.cleanup-enabled", Boolean.class, false),
        "Production temporary media cleanup must be enabled");
    requireAbsoluteMediaRoot(required(environment, "app.media.root"));
    requireHttpsOrigins(environment.getProperty("app.web.allowed-origins", ""));
    rejectAlternateConnectionProperties(environment);
  }

  private static String required(Environment environment, String name) {
    String value = environment.getProperty(name);
    require(value != null && !value.isBlank(), "Missing required production property: " + name);
    return value.trim();
  }

  private static void requireAbsoluteMediaRoot(String configured) {
    try {
      require(Path.of(configured).isAbsolute(), "Production media root must be an absolute path");
    } catch (InvalidPathException failure) {
      throw new IllegalStateException("Production media root is invalid", failure);
    }
  }

  private static void requireHttpsOrigins(String configured) {
    for (String origin : WebConfig.parseOrigins(configured)) {
      require("https".equals(URI.create(origin).getScheme()),
          "Production CORS origins must use HTTPS; leave empty for same-origin only");
    }
  }

  private static void rejectAlternateConnectionProperties(Environment environment) {
    if (!(environment instanceof ConfigurableEnvironment configurable)) return;
    for (var source : configurable.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
      for (String name : enumerable.getPropertyNames()) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[_.-]", "");
        boolean alternatePool = key.startsWith("springdatasourcehikari") &&
            !Set.of("springdatasourcehikarimaximumpoolsize", "springdatasourcehikariminimumidle").contains(key);
        boolean alternateDatasource = Set.of("springdatasourcetype", "springdatasourcejndiname",
            "springdatasourcedriverclassname").contains(key);
        boolean alternateRedis = key.equals("springdataredisurl") || key.startsWith("springdatarediscluster") ||
            key.startsWith("springdataredissentinel");
        boolean alternateJpa = key.startsWith("springjpaproperties") &&
            !key.equals("springjpapropertieshibernatejdbctimezone");
        require(!(alternatePool || alternateDatasource || alternateRedis || alternateJpa),
            "Alternate production connection/schema property is forbidden: " + name);
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
