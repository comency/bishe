package cn.edu.lostfound.ai;

import java.util.*;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;

/** Refuses accidental trial activation before ordinary beans (including datasource/Flyway) initialize. */
@Configuration(proxyBeanMethods=false)
@Profile("modeltrial")
public class AiTrialConfiguration {
  @Bean static BeanFactoryPostProcessor localAiTrialGuard(ConfigurableEnvironment environment) {
    return factory -> {
      var profiles=Set.copyOf(Arrays.asList(environment.getActiveProfiles()));
      if(!profiles.equals(Set.of("integration","modeltrial")))
        throw new IllegalStateException("AI trial requires only integration,modeltrial profiles");
      var expected=Map.ofEntries(
          Map.entry("app.ai-trial.confirmed","true"), Map.entry("app.campus.test-mode","true"),
          Map.entry("app.campus.campus-id","TEST_CAMPUS"),
          Map.entry("server.address","127.0.0.1"), Map.entry("server.port","18081"),
          Map.entry("app.web.allowed-origins","http://127.0.0.1:15176,http://localhost:15176"),
          Map.entry("spring.datasource.url","jdbc:mysql://127.0.0.1:13306/lost_found_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"),
          Map.entry("spring.datasource.username","lost_found_test_app"),
          Map.entry("spring.data.redis.host","127.0.0.1"), Map.entry("spring.data.redis.port","16380"),
          Map.entry("spring.data.redis.database","0"),
          Map.entry("spring.flyway.enabled","false"), Map.entry("spring.jpa.hibernate.ddl-auto","validate"),
          Map.entry("spring.sql.init.mode","never"), Map.entry("spring.jpa.generate-ddl","false"),
          Map.entry("ai.enabled","true"), Map.entry("ai.base-url","http://127.0.0.1:11434"),
          Map.entry("ai.model","qwen3:1.7b"), Map.entry("ai.timeout-ms","20000"),
          Map.entry("ai.context-tokens","4096"), Map.entry("ai.output-tokens","512"),
          Map.entry("ai.minimum-free-bytes","4294967296"), Map.entry("app.media.cleanup-enabled","false"));
      expected.forEach((key,value)->{
        if(!value.equals(environment.getProperty(key)))
          throw new IllegalStateException("Unsafe AI trial configuration: "+key);
      });
      // Pool-specific JDBC and Redis URL/cluster settings can override the apparently safe host/URL.
      // Inspect names only (including relaxed-binding aliases); never print property values.
      for(var source:environment.getPropertySources()) if(source instanceof EnumerablePropertySource<?> enumerable) {
        for(String name:enumerable.getPropertyNames()) {
          String key=name.toLowerCase(Locale.ROOT).replaceAll("[_.-]","");
          boolean alternatePool=key.startsWith("springdatasourcehikari") &&
              !Set.of("springdatasourcehikarimaximumpoolsize","springdatasourcehikariminimumidle").contains(key);
          boolean alternateDatasource=Set.of("springdatasourcetype","springdatasourcejndiname","springdatasourcedriverclassname").contains(key);
          boolean alternateRedis=key.equals("springdataredisurl") || key.startsWith("springdatarediscluster") || key.startsWith("springdataredissentinel");
          boolean alternateJpa=key.startsWith("springjpaproperties") && !key.equals("springjpapropertieshibernatejdbctimezone");
          if(alternatePool||alternateDatasource||alternateRedis||alternateJpa)
            throw new IllegalStateException("Alternate connection/schema settings are forbidden in AI trial: "+name);
        }
      }
      Path expectedMedia=Path.of(".local/media-test").toAbsolutePath().normalize();
      String configuredMedia=environment.getProperty("app.media.root","");
      if(configuredMedia.isBlank()||!Path.of(configuredMedia).toAbsolutePath().normalize().equals(expectedMedia))
        throw new IllegalStateException("Unsafe AI trial configuration: app.media.root");
    };
  }
}
