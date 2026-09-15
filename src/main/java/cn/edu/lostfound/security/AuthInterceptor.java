package cn.edu.lostfound.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.edu.lostfound.dto.ApiResponse;
import jakarta.servlet.http.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component public class AuthInterceptor implements HandlerInterceptor {
  private final StringRedisTemplate redis; private final ObjectMapper mapper;
  public AuthInterceptor(StringRedisTemplate redis,ObjectMapper mapper){this.redis=redis;this.mapper=mapper;}
  public boolean preHandle(HttpServletRequest req,HttpServletResponse resp,Object handler)throws Exception {
    if("OPTIONS".equals(req.getMethod())) return true;
    String token=req.getHeader("X-Token"); String value=token==null?null:redis.opsForValue().get("session:"+token);
    if(value==null){resp.setStatus(401);resp.setContentType("application/json;charset=UTF-8");mapper.writeValue(resp.getWriter(),ApiResponse.fail("请先登录"));return false;}
    String[] parts=value.split(":",2);UserContext.set(Long.valueOf(parts[0]),parts[1]);return true;
  }
  public void afterCompletion(HttpServletRequest r,HttpServletResponse s,Object h,Exception e){UserContext.clear();}
}
