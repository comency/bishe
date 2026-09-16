package cn.edu.lostfound.security;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.verification.VerificationApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
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
  private AccountApi accounts;
  private VerificationApi verification;
  private AuthInterceptor interceptor;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    UserContext.clear();
    redis = mock(StringRedisTemplate.class);
    values = mock(ValueOperations.class);
    accounts = mock(AccountApi.class);
    verification = mock(VerificationApi.class);
    interceptor = new AuthInterceptor(redis, mapper, accounts, verification);
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
    when(accounts.currentRole(41L)).thenReturn("USER");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertEquals(41L, UserContext.id());
    assertFalse(UserContext.admin());
    assertEquals(200, response.getStatus());
    assertEquals("", response.getContentAsString());
    verify(values).get("session:user-test-token");
    verify(verification).requireEligible(41L);
  }

  @Test
  void validAdministratorTokenSetsAdministratorContext() throws Exception {
    request.addHeader("X-Token", "admin-test-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:admin-test-token")).thenReturn("7:ADMIN");
    when(accounts.currentRole(7L)).thenReturn("ADMIN");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertEquals(7L, UserContext.id());
    assertTrue(UserContext.admin());
    verify(values).get("session:admin-test-token");
    verify(verification).requireEligible(7L);
  }

  @Test
  void oldAdministratorSnapshotCannotOverrideCurrentOrdinaryAccountRole() throws Exception {
    request.setRequestURI("/api/admin/verifications");
    session("7:ADMIN", "USER");

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertDeniedResponse(403, "FORBIDDEN");
    assertNull(UserContext.id());
    verify(accounts).currentRole(7L);
    verifyNoInteractions(verification);
  }

  @Test
  void currentAdministratorRoleDoesNotDependOnOldRedisRole() throws Exception {
    request.setRequestURI("/api/admin/verifications");
    session("7:USER", "ADMIN");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertTrue(UserContext.admin());
    assertEquals(7L, UserContext.id());
    verifyNoInteractions(verification);
  }

  @Test
  void administratorWithoutCampusQualificationCannotEnterParticipantBusiness() throws Exception {
    session("7", "ADMIN");
    doThrow(new BusinessException(403, "VERIFICATION_REQUIRED", "请先完成有效的校园身份核验"))
        .when(verification).requireEligible(7L);

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertDeniedResponse(403, "VERIFICATION_REQUIRED");
    assertNull(UserContext.id());
    assertFalse(UserContext.admin());
  }

  @Test
  void administratorMayManageWithoutCampusQualification() throws Exception {
    session("7", "ADMIN");
    request.setRequestURI("/api/admin/verifications/41/review");

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertTrue(UserContext.admin());
    verifyNoInteractions(verification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/users/me", "/api/verifications/me"})
  void selfAccountAndVerificationOnlyRequireLogin(String path) throws Exception {
    session("7", "USER");
    request.setRequestURI(path);

    assertTrue(interceptor.preHandle(request, response, new Object()));

    assertEquals(7L, UserContext.id());
    assertFalse(UserContext.admin());
    verifyNoInteractions(verification);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/items", "/api/ai/chat", "/api/uploads/images", "/api/claims/mine", "/api/unknown"})
  void allBusinessAndUnknownRoutesRequireCurrentQualification(String path) throws Exception {
    session("7", "USER");
    request.setRequestURI(path);
    doThrow(new BusinessException(403, "VERIFICATION_REQUIRED", "请先完成有效的校园身份核验"))
        .when(verification).requireEligible(7L);

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertDeniedResponse(403, "VERIFICATION_REQUIRED");
    assertNull(UserContext.id());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "garbage", "0", "-1", ":ADMIN", "9223372036854775808:USER"})
  void malformedSessionIsUnauthenticatedWithoutReadingAccount(String value) throws Exception {
    session(value, "ADMIN");

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertUnauthenticatedResponse();
    verifyNoInteractions(accounts, verification);
    assertNull(UserContext.id());
  }

  @Test
  void removedAccountCannotUseItsStillCachedSession() throws Exception {
    session("7:ADMIN", "ADMIN");
    when(accounts.currentRole(7L)).thenThrow(new BusinessException(401, "AUTH_REQUIRED", "请先登录"));

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertUnauthenticatedResponse();
    verifyNoInteractions(verification);
  }

  @Test
  void reusedTokenRechecksQualificationAndClearsPreviouslyAllowedContext() throws Exception {
    session("7", "USER");
    assertTrue(interceptor.preHandle(request, response, new Object()));
    doThrow(new BusinessException(403, "VERIFICATION_REQUIRED", "请先完成有效的校园身份核验"))
        .when(verification).requireEligible(7L);

    assertFalse(interceptor.preHandle(request, response, new Object()));

    assertDeniedResponse(403, "VERIFICATION_REQUIRED");
    verify(verification, times(2)).requireEligible(7L);
    assertNull(UserContext.id());
  }

  @Test
  void redisFailurePropagatesToUnavailableHandlerAndNeverAuthorizes() {
    UserContext.set(7L, "ADMIN");
    request.addHeader("X-Token", "synthetic-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:synthetic-token"))
        .thenThrow(new RedisConnectionFailureException("synthetic dependency detail"));

    assertThrows(RedisConnectionFailureException.class,
        () -> interceptor.preHandle(request, response, new Object()));

    assertNull(UserContext.id());
    verifyNoInteractions(accounts, verification);
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
    assertEquals("AUTH_REQUIRED", envelope.path("errorCode").asText());
    assertFalse(envelope.path("traceId").asText().isBlank());
  }

  private void session(String storedValue, String currentRole) {
    request.addHeader("X-Token", "synthetic-token");
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("session:synthetic-token")).thenReturn(storedValue);
    when(accounts.currentRole(7L)).thenReturn(currentRole);
  }

  private void assertDeniedResponse(int status, String errorCode) throws Exception {
    assertEquals(status, response.getStatus());
    assertEquals("no-store", response.getHeader("Cache-Control"));
    JsonNode envelope = mapper.readTree(response.getContentAsString());
    assertEquals(-1, envelope.path("code").asInt());
    assertEquals(errorCode, envelope.path("errorCode").asText());
    assertTrue(envelope.path("data").isNull());
    assertFalse(envelope.path("traceId").asText().isBlank());
  }
}
