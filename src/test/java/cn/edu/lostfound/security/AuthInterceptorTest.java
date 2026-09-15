package cn.edu.lostfound.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private StringRedisTemplate redis;
  private ValueOperations<String, String> values;
  private AuthInterceptor interceptor;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    UserContext.clear();
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    interceptor = new AuthInterceptor(redis, mapper);
    request = new MockHttpServletRequest("GET", "/api/items");
    response = new MockHttpServletResponse();
  }

  @AfterEach
  void clearThreadLocalState() {
    UserContext.clear();
  }

  @Test
  void missingTokenReturns401WithoutContactingRedis() throws Exception {
    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertUnauthenticatedResponse();
    verifyNoInteractions(redis, values);
    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
  }

  @Test
  void unknownOrExpiredTokenReturns401WithoutSettingContext() throws Exception {
    request.addHeader("X-Token", "expired-test-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:expired-test-token")).thenReturn(null);

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertUnauthenticatedResponse();
    verify(values).get("session:expired-test-token");
    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
  }

  @Test
  void validUserTokenSetsOnlyOrdinaryUserContext() throws Exception {
    request.addHeader("X-Token", "user-test-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:user-test-token")).thenReturn("41:USER");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertEquals(41L, UserContext.id());
    assertFalse(UserContext.admin());
    assertEquals(200, response.getStatus());
    assertEquals("", response.getContentAsString());
    verify(values).get("session:user-test-token");
  }

  @Test
  void validAdministratorTokenSetsAdministratorContext() throws Exception {
    request.addHeader("X-Token", "admin-test-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:admin-test-token")).thenReturn("7:ADMIN");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertEquals(7L, UserContext.id());
    assertTrue(UserContext.admin());
    verify(values).get("session:admin-test-token");
  }

  @Test
  void optionsPreflightDoesNotRequireTokenOrReadRedis() throws Exception {
    request.setMethod("OPTIONS");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    verifyNoInteractions(redis, values);
    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
    assertEquals("", response.getContentAsString());
  }

  @Test
  void completionRemovesUserAndAdministratorRoleFromThread() {
    UserContext.set(7L, "ADMIN");

    interceptor.afterCompletion(request, response, new Object(), null);

    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
    verifyNoInteractions(redis, values);
  }

  @Test
  void exceptionalCompletionAlsoRemovesThreadContext() {
    UserContext.set(7L, "ADMIN");

    interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("test failure"));

    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
    verifyNoInteractions(redis, values);
  }

  @Test
  void nextRequestCannotInheritCompletedAdministratorContext() throws Exception {
    UserContext.set(7L, "ADMIN");
    interceptor.afterCompletion(request, response, new Object(), null);

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertUnauthenticatedResponse();
    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
    verifyNoInteractions(redis, values);
  }

  private void assertUnauthenticatedResponse() throws Exception {
    assertEquals(401, response.getStatus());
    assertNotNull(response.getContentType());
    assertTrue(response.getContentType().startsWith("application/json"));
    assertEquals("UTF-8", response.getCharacterEncoding());
    JsonNode envelope = mapper.readTree(response.getContentAsString());
    assertEquals(-1, envelope.path("code").asInt());
    assertEquals("请先登录", envelope.path("message").asText());
    assertTrue(envelope.has("data"));
    assertTrue(envelope.path("data").isNull());
  }
}
