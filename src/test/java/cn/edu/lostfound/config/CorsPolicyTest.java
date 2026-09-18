package cn.edu.lostfound.config;

import cn.edu.lostfound.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CorsPolicyTest {
  @Test void explicitOriginsAreTrimmedAndDeduplicatedWithoutCredentials() {
    assertThat(WebConfig.parseOrigins("http://127.0.0.1:5174, https://campus.example,https://campus.example"))
        .containsExactly("http://127.0.0.1:5174","https://campus.example");
    assertThat(WebConfig.parseOrigins(" ")).isEmpty();
  }
  @ParameterizedTest @ValueSource(strings={"*","null","https://*.example","http://campus.example:8080",
      "https://user:synthetic@example.com","https://example.com/path","https://example.com/",
      "https://example.com?secret=synthetic","https://example.com#fragment","http://localhost",
      "https://example.com:0","https://example.com:65536","https://example.com,","file:///local"})
  void unsafeConfigurationIsRejectedWithoutEchoingValues(String input) {
    assertThatThrownBy(()->WebConfig.parseOrigins(input)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret=synthetic").hasMessageNotContaining("user:synthetic");
  }

  @Configuration @EnableWebMvc static class TestConfiguration {
    @Bean AuthInterceptor authInterceptor() throws Exception {
      var auth=mock(AuthInterceptor.class);
      when(auth.preHandle(any(),any(),any())).thenReturn(true);
      return auth;
    }
    @Bean AccountRateLimitInterceptor rateLimiter() throws Exception {
      var limiter=mock(AccountRateLimitInterceptor.class);
      when(limiter.preHandle(any(),any(),any())).thenReturn(true);
      return limiter;
    }
    @Bean WebConfig webConfig(AuthInterceptor auth, AccountRateLimitInterceptor limiter) {
      return new WebConfig(auth,limiter,"http://127.0.0.1:15174,http://localhost:15174");
    }
    @Bean ProbeController probe(){return new ProbeController();}
  }
  @RestController static class ProbeController {
    int calls;
    @GetMapping("/api/cors-probe") public String get(){calls++;return "synthetic";}
    @PostMapping("/api/cors-probe") public String post(){calls++;return "synthetic";}
    @GetMapping("/api/health/live") public String live(){return "UP";}
    @GetMapping("/api/health/ready") public String ready(){return "UP";}
  }
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;
  @BeforeEach void setup() {
    context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
    context.register(TestConfiguration.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).build();
  }
  @AfterEach void close(){context.close();}
  @Test void allowedLocalFrontendPreflightIsExactNotWildcard() throws Exception {
    mvc.perform(options("/api/cors-probe").header("Origin","http://127.0.0.1:15174")
        .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","content-type,x-token"))
        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://127.0.0.1:15174"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    assertThat(context.getBean(ProbeController.class).calls).isZero();
  }
  @ParameterizedTest @ValueSource(strings={"https://unrelated.example","http://127.0.0.1:15175","null"})
  void unrelatedPreflightAndActualRequestsAreBlockedBeforeBusiness(String origin) throws Exception {
    mvc.perform(options("/api/cors-probe").header("Origin",origin).header("Access-Control-Request-Method","POST"))
        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(post("/api/cors-probe").header("Origin",origin)).andExpect(status().isForbidden());
    assertThat(context.getBean(ProbeController.class).calls).isZero();
  }
  @Test void normalAndAllowedOriginRequestsRemainUsable() throws Exception {
    mvc.perform(get("/api/cors-probe")).andExpect(status().isOk());
    mvc.perform(post("/api/cors-probe").header("Origin","http://127.0.0.1:15174"))
        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://127.0.0.1:15174"));
    assertThat(context.getBean(ProbeController.class).calls).isEqualTo(2);
  }
  @Test void healthProbesBypassAuthenticationButNormalApiDoesNot() throws Exception {
    AuthInterceptor auth=context.getBean(AuthInterceptor.class);
    mvc.perform(get("/api/health/live")).andExpect(status().isOk()).andExpect(content().string("UP"));
    mvc.perform(get("/api/health/ready")).andExpect(status().isOk()).andExpect(content().string("UP"));
    verify(auth,never()).preHandle(any(),any(),any());

    mvc.perform(get("/api/cors-probe")).andExpect(status().isOk());
    verify(auth).preHandle(any(),any(),any());
  }
  @Test void unneededMethodsAndHeadersAreNotAllowed() throws Exception {
    mvc.perform(options("/api/cors-probe").header("Origin","http://127.0.0.1:15174").header("Access-Control-Request-Method","PATCH"))
        .andExpect(status().isForbidden());
    mvc.perform(options("/api/cors-probe").header("Origin","http://127.0.0.1:15174").header("Access-Control-Request-Method","POST")
        .header("Access-Control-Request-Headers","x-unneeded-header")).andExpect(status().isForbidden());
  }
}
