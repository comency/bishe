package cn.edu.lostfound.security;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.dto.ApiResponse;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.verification.VerificationApi;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final AccountApi accounts;
  private final VerificationApi verification;
  public AuthInterceptor(StringRedisTemplate redis, ObjectMapper mapper, AccountApi accounts, VerificationApi verification) {
    this.redis=redis; this.mapper=mapper; this.accounts=accounts; this.verification=verification;
  }
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    UserContext.clear();
    if ("OPTIONS".equals(request.getMethod())) return true;
    response.setHeader("Cache-Control", "no-store");
    String token=request.getHeader("X-Token");
    String value=token==null || token.isBlank() ? null : redis.opsForValue().get("session:"+token);
    try {
      if (value==null) throw new BusinessException(401, "AUTH_REQUIRED", "请先登录");
      Long userId;
      try {
        userId=Long.valueOf(value.split(":",2)[0]);
        if (userId<=0) throw new NumberFormatException();
      } catch (NumberFormatException malformed) {
        throw new BusinessException(401, "AUTH_REQUIRED", "请先登录");
      }
      String role=accounts.currentRole(userId);
      String path=request.getServletPath();
      if (path.isEmpty()) path=request.getRequestURI().substring(request.getContextPath().length());
      if (path.startsWith("/api/admin/")) {
        if (!"ADMIN".equals(role)) throw new BusinessException(403, "FORBIDDEN", "仅管理员可操作");
      } else if (!path.equals("/api/users/me") && !path.equals("/api/verifications/me")) {
        // Unknown business endpoints fail closed. ADMIN participants must also be eligible.
        verification.requireEligible(userId);
      }
      UserContext.set(userId,role);
      return true;
    } catch (BusinessException denied) {
      response.setStatus(denied.getHttpStatus());
      response.setContentType("application/json;charset=UTF-8");
      mapper.writeValue(response.getWriter(), ApiResponse.fail(denied.getErrorCode(), denied.getMessage()));
      return false;
    }
  }
  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
    UserContext.clear();
  }
}
