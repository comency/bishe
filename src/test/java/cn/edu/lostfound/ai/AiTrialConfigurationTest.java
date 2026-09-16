package cn.edu.lostfound.ai;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

class AiTrialConfigurationTest {
  private ApplicationContextRunner runner(String... profiles) {
    return new ApplicationContextRunner().withUserConfiguration(AiTrialConfiguration.class,AiTrialController.class)
        .withInitializer(context->context.getEnvironment().setActiveProfiles(profiles))
        .withPropertyValues("app.ai-trial.confirmed=true","app.campus.test-mode=true","app.campus.campus-id=TEST_CAMPUS",
            "server.address=127.0.0.1","server.port=18081",
            "spring.datasource.url=jdbc:mysql://127.0.0.1:13306/lost_found_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
            "spring.datasource.username=lost_found_test_app","spring.data.redis.host=127.0.0.1",
            "spring.data.redis.port=16380","spring.data.redis.database=0","spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=validate","spring.sql.init.mode=never","spring.jpa.generate-ddl=false",
            "app.media.root=.local/media-test","ai.enabled=true","ai.base-url=http://127.0.0.1:11434",
            "ai.model=qwen3:1.7b","ai.timeout-ms=20000","ai.context-tokens=4096","ai.output-tokens=512",
            "ai.minimum-free-bytes=4294967296","app.media.cleanup-enabled=false");
  }
  @Test void isolatedExplicitTrialAccepted(){runner("integration","modeltrial").run(context->{
    assertThat(context).hasNotFailed();assertThat(context).hasSingleBean(AiTrialController.class);
    assertThat(context.getBean(AiTrialController.class).proof().data().localTrial()).isTrue();
  });}
  @Test void normalIntegrationDoesNotRegisterTrialGuard(){
    runner("integration").withPropertyValues("ai.enabled=false","app.ai-trial.confirmed=false")
        .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean("localAiTrialGuard");assertThat(context).doesNotHaveBean(AiTrialController.class);});
  }
  @ParameterizedTest @ValueSource(strings={"modeltrial","dev,modeltrial","integration,modeltrial,dev"})
  void otherProfileCombinationsRejected(String profiles){runner(profiles.split(",")).run(context->assertThat(context).hasFailed());}
  @ParameterizedTest @ValueSource(strings={
      "app.ai-trial.confirmed=false","app.campus.test-mode=false","app.campus.campus-id=REAL_CAMPUS",
      "server.address=0.0.0.0","server.port=18080","spring.datasource.url=jdbc:mysql://127.0.0.1:13306/lost_found",
      "spring.datasource.username=lost_found_app","spring.data.redis.host=example.com","spring.data.redis.port=16379",
      "spring.data.redis.database=1","spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=update",
      "ai.enabled=false","ai.base-url=http://127.0.0.1:11435","ai.model=qwen3:4b","ai.timeout-ms=90000",
      "ai.context-tokens=8192","ai.output-tokens=1024","ai.minimum-free-bytes=1","app.media.cleanup-enabled=true",
      "spring.sql.init.mode=always","spring.jpa.generate-ddl=true","app.media.root=.local/media-dev",
      "spring.datasource.hikari.jdbc-url=jdbc:mysql://127.0.0.1:3306/other",
      "spring.datasource.hikari.jdbcUrl=jdbc:mysql://127.0.0.1:3306/other",
      "spring.datasource.hikari.username=other","spring.datasource.hikari.connection-init-sql=SELECT 1",
      "spring.datasource.type=other","spring.datasource.jndi-name=other",
      "spring.data.redis.url=redis://127.0.0.1:6379","spring.data.redis.cluster.nodes[0]=127.0.0.1:6379",
      "spring.data.redis.sentinel.master=other","SPRING_DATA_REDIS_URL=redis://127.0.0.1:6379",
      "spring.jpa.properties.hibernate.connection.url=jdbc:mysql://127.0.0.1:3306/other",
      "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:mysql://127.0.0.1:3306/other",
      "spring.jpa.properties.hibernate.hbm2ddl.auto=create"
  }) void unsafeOverridesFailBeforeOrdinaryBeans(String override){
    AtomicBoolean initialized=new AtomicBoolean();
    runner("integration","modeltrial").withBean("sideEffectBean",Object.class,()->{initialized.set(true);return new Object();})
        .withPropertyValues(override).run(context->{assertThat(context).hasFailed();assertThat(initialized).isFalse();});
  }
}
