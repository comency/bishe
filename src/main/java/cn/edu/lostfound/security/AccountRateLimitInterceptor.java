package cn.edu.lostfound.security;

import cn.edu.lostfound.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Fixed-window counters are atomic and expire; no passwords, names or tokens in their keys. */
@Component
public class AccountRateLimitInterceptor implements HandlerInterceptor {
  private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>("""
      local hits = redis.call('INCR', KEYS[1])
      if hits == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
      return hits
      """, Long.class);
  private final StringRedisTemplate redis;
  private final int loginLimit;
  private final int registerLimit;
  private final int submitLimit;

  public AccountRateLimitInterceptor(StringRedisTemplate redis,
      @Value("${app.rate-limit.login-per-minute:60}") int loginLimit,
      @Value("${app.rate-limit.register-per-minute:30}") int registerLimit,
      @Value("${app.rate-limit.verification-per-minute:12}") int submitLimit) {
    this.redis=redis; this.loginLimit=loginLimit; this.registerLimit=registerLimit; this.submitLimit=submitLimit;
  }

  @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    if (!"POST".equals(request.getMethod())) return true;
    String path=request.getRequestURI().substring(request.getContextPath().length());
    int limit;
    String subject;
    if (path.equals("/api/auth/login")) { limit=loginLimit; subject=request.getRemoteAddr(); }
    else if (path.equals("/api/auth/register")) { limit=registerLimit; subject=request.getRemoteAddr(); }
    else if (path.equals("/api/verifications/me")) { limit=submitLimit; subject=String.valueOf(UserContext.id()); }
    else if (path.matches("/api/items/[1-9][0-9]*/claims")) { limit=submitLimit; subject=String.valueOf(UserContext.id()); path="/api/claims/create"; }
    else return true;
    String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(subject.getBytes(StandardCharsets.UTF_8)));
    Long hits=redis.execute(COUNT,List.of("accountRate:"+path+":"+digest),"60");
    if (hits==null) throw new BusinessException(503,"SERVICE_UNAVAILABLE","服务暂时不可用，请稍后重试");
    if (hits>limit) {
      response.setHeader("Retry-After","60");
      throw new BusinessException(429,"RATE_LIMITED","操作过于频繁，请在一分钟后重试");
    }
    return true;
  }
}
