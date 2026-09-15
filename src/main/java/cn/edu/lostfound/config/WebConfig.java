package cn.edu.lostfound.config;

import cn.edu.lostfound.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor authInterceptor;
  public WebConfig(AuthInterceptor authInterceptor) { this.authInterceptor = authInterceptor; }
  @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns("/api/auth/**", "/error"); }
  @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/**").allowedOrigins("*").allowedMethods("*").allowedHeaders("*"); }
}
