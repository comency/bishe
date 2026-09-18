package cn.edu.lostfound.config;

import cn.edu.lostfound.security.AuthInterceptor;
import cn.edu.lostfound.security.AccountRateLimitInterceptor;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  private final AccountRateLimitInterceptor rateLimiter;
  private final String[] allowedOrigins;
  public WebConfig(AuthInterceptor authInterceptor, AccountRateLimitInterceptor rateLimiter,
      @Value("${app.web.allowed-origins:http://127.0.0.1:5174,http://localhost:5174}") String origins) {
    this.authInterceptor = authInterceptor; this.rateLimiter = rateLimiter; this.allowedOrigins=parseOrigins(origins);
  }
  static String[] parseOrigins(String configured) {
    if(configured.isBlank())return new String[0];
    String[] origins=configured.split(",",-1);
    if(origins.length>8)throw new IllegalArgumentException("At most eight explicit CORS origins are allowed");
    for(int i=0;i<origins.length;i++) {
      origins[i]=origins[i].trim();
      try {
        URI origin=URI.create(origins[i]);String host=origin.getHost();
        boolean https="https".equals(origin.getScheme());
        boolean localHttp="http".equals(origin.getScheme()) && ("127.0.0.1".equals(host)||"localhost".equals(host)) && origin.getPort()>0;
        if(host==null||(!https&&!localHttp)||origin.getRawUserInfo()!=null||origin.getRawQuery()!=null||origin.getRawFragment()!=null||
            !origin.getRawPath().isEmpty()||origin.getPort()==0||origin.getPort()>65535)
          throw new IllegalArgumentException();
      } catch(IllegalArgumentException failure){throw new IllegalArgumentException("CORS origins require explicit HTTPS origins or loopback development origins; no wildcards, credentials, paths or query strings");}
    }
    return Arrays.stream(origins).distinct().toArray(String[]::new);
  }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns("/api/auth/register", "/api/auth/login", "/api/auth/logout", "/api/public/config", "/api/health/live", "/api/health/ready", "/error");
    registry.addInterceptor(rateLimiter).addPathPatterns("/api/auth/login", "/api/auth/register", "/api/verifications/me", "/api/items/*/claims", "/api/ai/polish", "/api/ai/chat");
  }
  @Override public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**").allowedOrigins(allowedOrigins)
        .allowedMethods("GET","HEAD","POST","PUT","DELETE","OPTIONS")
        .allowedHeaders("Content-Type","X-Token","Accept")
        .exposedHeaders("Retry-After").allowCredentials(false).maxAge(0);
  }
}
