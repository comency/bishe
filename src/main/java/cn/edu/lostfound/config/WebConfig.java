package cn.edu.lostfound.config;

import cn.edu.lostfound.security.AuthInterceptor;
import cn.edu.lostfound.security.AccountRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  private final AccountRateLimitInterceptor rateLimiter;
  public WebConfig(AuthInterceptor authInterceptor, AccountRateLimitInterceptor rateLimiter) { this.authInterceptor = authInterceptor; this.rateLimiter = rateLimiter; }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/public/config", "/error");
    registry.addInterceptor(rateLimiter).addPathPatterns("/api/auth/login", "/api/auth/register", "/api/verifications/me");
  }
  @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/**").allowedOrigins("*").allowedMethods("*").allowedHeaders("*"); }
}
