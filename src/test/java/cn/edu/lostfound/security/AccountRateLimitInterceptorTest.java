package cn.edu.lostfound.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.edu.lostfound.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountRateLimitInterceptorTest {
  @Test void trustedProxyClientAddressPartitionsLoginCounter() throws Exception {
    StringRedisTemplate redis = redisReturning(1L);
    var limiter = new AccountRateLimitInterceptor(redis, new ClientAddressResolver("127.0.0.1"), 60, 30, 12);
    var request = post("/api/auth/login", "127.0.0.1", "198.51.100.9");

    assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), new Object()));
    assertEquals("accountRate:/api/auth/login:" + digest("198.51.100.9"), capturedKey(redis));
  }

  @Test void untrustedPeerCannotChooseCounterSubject() throws Exception {
    StringRedisTemplate redis = redisReturning(1L);
    var limiter = new AccountRateLimitInterceptor(redis, new ClientAddressResolver("127.0.0.1"), 60, 30, 12);
    var request = post("/api/auth/register", "192.0.2.4", "198.51.100.9");

    assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), new Object()));
    assertEquals("accountRate:/api/auth/register:" + digest("192.0.2.4"), capturedKey(redis));
  }

  @Test void exceededLimitSetsRetryAfter() {
    StringRedisTemplate redis = redisReturning(2L);
    var limiter = new AccountRateLimitInterceptor(redis, new ClientAddressResolver(""), 1, 1, 1);
    var response = new MockHttpServletResponse();
    BusinessException failure = assertThrows(BusinessException.class,
        () -> limiter.preHandle(post("/api/auth/login", "192.0.2.4", null), response, new Object()));
    assertEquals(429, failure.getHttpStatus());
    assertEquals("RATE_LIMITED", failure.getErrorCode());
    assertEquals("60", response.getHeader("Retry-After"));
  }

  @Test void unrelatedOrReadRequestDoesNotTouchRedis() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    var limiter = new AccountRateLimitInterceptor(redis, new ClientAddressResolver(""), 1, 1, 1);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
    assertTrue(limiter.preHandle(request, new MockHttpServletResponse(), new Object()));
    verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static StringRedisTemplate redisReturning(Long result) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(DefaultRedisScript.class), anyList(), eq("60"))).thenReturn(result);
    return redis;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String capturedKey(StringRedisTemplate redis) {
    ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
    verify(redis).execute(any(DefaultRedisScript.class), keys.capture(), eq("60"));
    return keys.getValue().getFirst();
  }

  private static MockHttpServletRequest post(String path, String remote, String forwarded) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    request.setRemoteAddr(remote);
    if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
    return request;
  }

  private static String digest(String value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
