package cn.edu.lostfound.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/** Configuration-only checks. Does not bootstrap Spring or connect to any service. */
class EnvironmentBaselineTest {
  private Map<String, Object> config(String name) {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource(name));
    var properties = yaml.getObject();
    var result = new java.util.HashMap<String, Object>();
    properties.forEach((key, value) -> result.put(key.toString(), value));
    return result;
  }

  private String resolve(Map<String, Object> values, String key) {
    var sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("test-only", values));
    return new PropertySourcesPropertyResolver(sources).getProperty(key);
  }

  @Test void defaultEndpointsDoNotUseMallServices() {
    var values = config("application.yml");
    assertThat(resolve(values, "spring.datasource.url"))
        .startsWith("jdbc:mysql://127.0.0.1:13306/lost_found?");
    assertThat(resolve(values, "spring.datasource.username")).isEqualTo("lost_found_app");
    assertThat(resolve(values, "spring.data.redis.host")).isEqualTo("127.0.0.1");
    assertThat(resolve(values, "spring.data.redis.port")).isEqualTo("16379");
    assertThat(resolve(values, "server.address")).isEqualTo("127.0.0.1");
    assertThat(resolve(values, "server.port")).isEqualTo("8080");
  }

  @Test void schemaChangesHaveOneExplicitVersionedOwner() {
    var values = config("application.yml");
    assertThat(resolve(values, "spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(resolve(values, "spring.flyway.enabled")).isEqualTo("true");
    assertThat(resolve(values, "spring.flyway.baseline-on-migrate")).isEqualTo("false");
    assertThat(resolve(values, "spring.flyway.clean-disabled")).isEqualTo("true");
    assertThat(new ClassPathResource("db/migration/V1__prototype_baseline.sql").exists()).isTrue();
  }

  @Test void integrationRequiresSeparateCredentialsAndEndpoints() {
    var values = config("application-integration.yml");
    assertThat(resolve(values, "spring.datasource.url"))
        .startsWith("jdbc:mysql://127.0.0.1:13306/lost_found_test?");
    assertThat(values.get("spring.datasource.username")).isEqualTo("${TEST_DB_USERNAME}");
    assertThat(values.get("spring.datasource.password")).isEqualTo("${TEST_DB_PASSWORD}");
    assertThat(resolve(values, "spring.data.redis.port")).isEqualTo("16380");
    assertThat(resolve(values, "server.address")).isEqualTo("127.0.0.1");
    assertThat(resolve(values, "server.port")).isEqualTo("18080");
  }

  @Test void aiAndStoredCredentialsRemainDisabledOrEmpty() {
    var values = config("application.yml");
    assertThat(resolve(values, "ai.enabled")).isEqualTo("false");
    assertThat(resolve(values, "spring.datasource.password")).isEmpty();
    assertThat(resolve(values, "app.admin-password")).isEmpty();
    assertThat(resolve(config("application-integration.yml"), "ai.enabled")).isEqualTo("false");
  }
  @Test void environmentOriginsAreExactAndDoNotIncludeMallPorts() {
    assertThat(resolve(config("application.yml"),"app.web.allowed-origins"))
        .isEqualTo("http://127.0.0.1:5174,http://localhost:5174");
    assertThat(resolve(config("application-integration.yml"),"app.web.allowed-origins"))
        .isEqualTo("http://127.0.0.1:15174,http://localhost:15174");
    assertThat(resolve(config("application-modeltrial.yml"),"app.web.allowed-origins"))
        .isEqualTo("http://127.0.0.1:15176,http://localhost:15176");
  }
}
