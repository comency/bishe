package cn.edu.lostfound.identity;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.AuthDtos;
import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.service.AuthService;
import cn.edu.lostfound.service.ItemService;
import cn.edu.lostfound.verification.VerificationApi;
import cn.edu.lostfound.verification.VerificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.edu.lostfound.verification.VerificationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit opt-in, real MySQL integration tests; never clears a schema or Redis.
 * Set RUN_IDENTITY_DB_TESTS=true and the TEST_DB_* / TEST_ADMIN_PASSWORD environment.
 * Only new synthetic accounts are changed. Their identifiers are printed for traceability.
 * Time advances only inside this test ApplicationContext, never the backend or OS clock.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "RUN_IDENTITY_DB_TESTS", matches = "true")
@Import(IdentityDatabaseIntegrationTest.TestClockConfiguration.class)
class IdentityDatabaseIntegrationTest {
  private static final Instant BASE_TIME = Instant.parse("2026-09-16T02:00:00Z");
  private static final String SYNTHETIC_PASSWORD = "Synthetic-test-password-only";

  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired VerificationService service;
  @Autowired ItemService items;
  @Autowired JdbcTemplate jdbc;
  @Autowired CampusProperties campus;
  @Autowired PlatformTransactionManager transactions;
  @Autowired MutableTestClock clock;
  @SpyBean VerificationApi verification;
  @SpyBean AuditApi audit;
  private Long adminId;
  private ExecutorService workers;

  @BeforeEach
  void requireIsolatedSyntheticDatabase() {
    clock.set(BASE_TIME);
    assertThat(campus.testMode()).as("test-only campus configuration").isTrue();
    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("lost_found_test");
    assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(13306);
    var administrator = users.findByUsername("admin").orElseThrow();
    assertThat(administrator.getRole()).isEqualTo("ADMIN");
    adminId = administrator.getId();
    workers = Executors.newFixedThreadPool(2);
    UserContext.clear();
  }

  @AfterEach
  void restoreTestClockAndThreadContext() throws InterruptedException {
    try {
      if (workers != null) {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("all test transactions completed").isTrue();
      }
    } finally {
      clock.set(BASE_TIME);
      UserContext.clear();
    }
  }

  @Test
  void registrationAndQualificationCommitOrRollbackTogether() {
    String username = uniqueUsername();
    System.out.println("IDENTITY_DB_RECORD_CANDIDATE username=" + username);
    VerificationApi qualificationSpy = AopTestUtils.getUltimateTargetObject(verification);
    doThrow(new IllegalStateException("Synthetic qualification initialization failure"))
        .when(qualificationSpy).initialize(anyLong());

    assertThatThrownBy(() -> auth.register(new AuthDtos.Register(username, SYNTHETIC_PASSWORD, "Synthetic DB User")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("SELECT COUNT(*) FROM users WHERE username=?", username)).isZero();
    doCallRealMethod().when(qualificationSpy).initialize(anyLong());
    auth.register(new AuthDtos.Register(username, SYNTHETIC_PASSWORD, "Synthetic DB User"));
    Long userId = users.findByUsername(username).orElseThrow().getId();
    assertThat(count("SELECT COUNT(*) FROM campus_verifications WHERE user_id=?", userId)).isEqualTo(1);
    assertThat(verification.summary(userId).status()).isEqualTo("UNVERIFIED");
    assertThat(verification.summary(userId).version()).isZero();
  }

  @Test
  void auditFailureRollsBackBothApprovalAndApplicationThenRetryCommitsOnce() {
    Long userId = newUser();
    MyVerification pending = submit(userId, 0);
    var review = approveRequest(pending, today());
    AuditApi auditSpy = AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("Synthetic audit storage failure"))
        .when(auditSpy).verification(eq("VERIFICATION_APPROVED"), eq(adminId), eq(true), eq(userId),
            eq(pending.currentApplication().id()), eq("PENDING"), eq("VERIFIED"), eq(2L), isNull());

    assertThatThrownBy(() -> asAdmin(() -> service.review(userId, review)))
        .isInstanceOf(IllegalStateException.class);

    var unchanged = asAdmin(() -> service.detail(userId, 1, 10));
    assertThat(unchanged.summary().status()).isEqualTo("PENDING");
    assertThat(unchanged.summary().version()).isEqualTo(1);
    assertThat(unchanged.currentApplication().status()).isEqualTo("PENDING");
    assertThat(unchanged.currentApplication().reviewedAt()).isNull();
    assertThat(unchanged.currentApplication().reviewerId()).isNull();
    assertThat(logs(userId, "VERIFICATION_SUBMITTED")).isEqualTo(1);
    assertThat(logs(userId, "VERIFICATION_APPROVED")).isZero();

    doCallRealMethod().when(auditSpy).verification(eq("VERIFICATION_APPROVED"), eq(adminId), eq(true), eq(userId),
        eq(pending.currentApplication().id()), eq("PENDING"), eq("VERIFIED"), eq(2L), isNull());
    var approved = asAdmin(() -> service.review(userId, review));
    assertThat(approved.summary().status()).isEqualTo("VERIFIED");
    assertThat(approved.summary().version()).isEqualTo(2);
    assertThat(approved.currentApplication().reviewerId()).isEqualTo(adminId);
    assertThat(logs(userId, "VERIFICATION_APPROVED")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM verification_applications WHERE user_id=?", userId)).isEqualTo(1);
  }

  @Test
  void exactExpiryChangesEligibilityAndSqlFilteringWithoutRewritingHistory() {
    Long userId = verifiedUser();
    Instant expiry = verification.summary(userId).expiresAt().toInstant();
    clock.set(expiry.minusNanos(1));
    verification.requireEligible(userId);
    assertThat(verification.summary(userId).status()).isEqualTo("VERIFIED");
    clock.set(expiry);

    assertThat(verification.summary(userId).status()).isEqualTo("EXPIRED");
    assertIneligible(() -> verification.requireEligible(userId));
    var expired = asAdmin(() -> service.list(Status.EXPIRED, null, userId, 1, 10));
    assertThat(expired.total()).isEqualTo(1);
    assertThat(expired.records()).extracting(AdminSummary::userId).containsExactly(userId);
    assertThat(expired.records().getFirst().status()).isEqualTo("EXPIRED");
    assertThat(asAdmin(() -> service.list(Status.VERIFIED, null, userId, 1, 10)).total()).isZero();
    assertThat(jdbc.queryForObject("SELECT stored_status FROM campus_verifications WHERE user_id=?", String.class, userId))
        .as("expiry is derived, without a background status rewrite").isEqualTo("VERIFIED");
    var afterExpiry = asUser(userId, () -> service.mine(1, 10));
    assertThat(afterExpiry.currentApplication().status()).isEqualTo("VERIFIED");

    var resubmitted = submit(userId, 2);
    assertThat(resubmitted.summary().status()).isEqualTo("PENDING");
    assertThat(resubmitted.history().records()).extracting(Application::status).containsExactly("PENDING", "VERIFIED");
    var rejection = new ReviewRequest(resubmitted.currentApplication().id(), 3L, Decision.REJECTED,
        null, null, null, "Synthetic: please resubmit.", "Synthetic internal review note");
    asAdmin(() -> service.review(userId, rejection));
    var third = submit(userId, 4);
    assertThat(third.history().total()).isEqualTo(3);
    assertThat(third.history().records()).extracting(Application::status)
        .containsExactly("PENDING", "REJECTED", "VERIFIED");
  }

  @Test
  void concurrentDuplicateRegistrationCreatesOneAccountAndOneQualification() throws Exception {
    String username = uniqueUsername();
    System.out.println("IDENTITY_DB_RECORD_CANDIDATE username=" + username);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> registration = () -> outcome(() -> {
      ready.countDown(); await(start);
      auth.register(new AuthDtos.Register(username, SYNTHETIC_PASSWORD, "Synthetic DB User"));
      return null;
    });
    Future<String> first = workers.submit(registration);
    Future<String> second = workers.submit(registration);
    try { await(ready); } finally { start.countDown(); }

    assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder("OK", "409:USERNAME_EXISTS");
    Long userId = users.findByUsername(username).orElseThrow().getId();
    assertThat(count("SELECT COUNT(*) FROM users WHERE username=?", username)).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM campus_verifications WHERE user_id=?", userId)).isEqualTo(1);
  }

  @Test
  void concurrentReviewCommitsOneApprovalAndOneAuditRecord() throws Exception {
    Long userId = newUser();
    var pending = submit(userId, 0);
    var review = approveRequest(pending, today());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<String> action = () -> outcome(() -> {
      ready.countDown(); await(start);
      return asAdmin(() -> service.review(userId, review));
    });
    Future<String> first = workers.submit(action);
    Future<String> second = workers.submit(action);
    try { await(ready); } finally { start.countDown(); }

    assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
        .containsExactlyInAnyOrder("OK", "409:VERSION_CONFLICT");
    assertThat(verification.summary(userId).version()).isEqualTo(2);
    assertThat(logs(userId, "VERIFICATION_APPROVED")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM verification_applications WHERE user_id=?", userId)).isEqualTo(1);
  }

  @Test
  void revocationHoldingCoordinatorLockBlocksAndThenRejectsBusinessWrite() throws Exception {
    Long userId = verifiedUser();
    CountDownLatch revocationStaged = new CountDownLatch(1);
    CountDownLatch releaseRevocation = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    String title = "DB_REVOKE_FIRST_" + UUID.randomUUID();
    Future<String> revoke = workers.submit(() -> outcome(() -> asAdmin(() -> transaction().execute(status -> {
      service.revoke(userId, new VersionReasonRequest(2L, "Synthetic revoke-before-write race"));
      revocationStaged.countDown(); await(releaseRevocation);
      return null;
    }))));
    Future<String> write;
    try {
      await(revocationStaged);
      write = workers.submit(() -> outcome(() -> {
        writerStarted.countDown();
        return items.create(userId, item(title));
      }));
      await(writerStarted);
      assertThatThrownBy(() -> write.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    } finally {
      releaseRevocation.countDown();
    }

    assertThat(revoke.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
    assertThat(write.get(10, TimeUnit.SECONDS)).isEqualTo("403:VERIFICATION_REQUIRED");
    assertThat(count("SELECT COUNT(*) FROM items WHERE publisher_id=? AND title=?", userId, title)).isZero();
    assertThat(verification.summary(userId).status()).isEqualTo("REVOKED");
  }

  @Test
  void businessHoldingCoordinatorLockCommitsBeforeWaitingRevocation() throws Exception {
    Long userId = verifiedUser();
    CountDownLatch businessStaged = new CountDownLatch(1);
    CountDownLatch releaseBusiness = new CountDownLatch(1);
    CountDownLatch revokerStarted = new CountDownLatch(1);
    String title = "DB_WRITE_FIRST_" + UUID.randomUUID();
    Future<String> write = workers.submit(() -> outcome(() -> transaction().execute(status -> {
      items.create(userId, item(title));
      businessStaged.countDown(); await(releaseBusiness);
      return null;
    })));
    Future<String> revoke;
    try {
      await(businessStaged);
      revoke = workers.submit(() -> outcome(() -> {
        revokerStarted.countDown();
        return asAdmin(() -> service.revoke(userId, new VersionReasonRequest(2L, "Synthetic write-before-revoke race")));
      }));
      await(revokerStarted);
      assertThatThrownBy(() -> revoke.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    } finally {
      releaseBusiness.countDown();
    }

    assertThat(write.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
    assertThat(revoke.get(10, TimeUnit.SECONDS)).isEqualTo("OK");
    assertThat(count("SELECT COUNT(*) FROM items WHERE publisher_id=? AND title=?", userId, title)).isEqualTo(1);
    assertThat(verification.summary(userId).status()).isEqualTo("REVOKED");
    assertThat(logs(userId, "VERIFICATION_REVOKED")).isEqualTo(1);
  }

  @Test
  void qualificationExpiringDuringTransactionRollsBackStagedItemAtCommitBoundary() {
    Long userId = verifiedUser();
    Instant expiry = verification.summary(userId).expiresAt().toInstant();
    String title = "DB_COMMIT_EXPIRY_" + UUID.randomUUID();

    assertIneligible(() -> transaction().execute(status -> {
      items.create(userId, item(title));
      clock.set(expiry);
      return null;
    }));

    assertThat(count("SELECT COUNT(*) FROM items WHERE publisher_id=? AND title=?", userId, title)).isZero();
    assertThat(verification.summary(userId).status()).isEqualTo("EXPIRED");
  }

  private Long newUser() {
    String username = uniqueUsername();
    System.out.println("IDENTITY_DB_RECORD_CANDIDATE username=" + username);
    auth.register(new AuthDtos.Register(username, SYNTHETIC_PASSWORD, "Synthetic DB User"));
    Long id = users.findByUsername(username).orElseThrow().getId();
    System.out.println("IDENTITY_DB_RECORD userId=" + id);
    return id;
  }

  private Long verifiedUser() {
    Long id = newUser();
    var pending = submit(id, 0);
    asAdmin(() -> service.review(id, approveRequest(pending, today())));
    return id;
  }

  private MyVerification submit(Long userId, long expectedVersion) {
    return asUser(userId, () -> service.submit(new SubmitRequest(expectedVersion,
        "Synthetic DB Applicant", "TEST-ONLY", "Synthetic fixture, not actual campus evidence.")));
  }

  private ReviewRequest approveRequest(MyVerification pending, LocalDate validThrough) {
    return new ReviewRequest(pending.currentApplication().id(), pending.summary().version(), Decision.APPROVED,
        Method.IN_PERSON, "Synthetic DB evidence only; not a real identity.", validThrough, null,
        "Synthetic internal note");
  }

  private LocalDate today() { return clock.instant().atZone(campus.zoneId()).toLocalDate(); }
  private String uniqueUsername() { return "idb_" + UUID.randomUUID().toString().replace("-", ""); }
  private long count(String sql, Object... parameters) { return jdbc.queryForObject(sql, Long.class, parameters); }
  private long logs(Long userId, String event) {
    return count("SELECT COUNT(*) FROM business_logs WHERE subject_user_id=? AND event_type=?", userId, event);
  }
  private ItemDtos.Save item(String title) {
    return new ItemDtos.Save(title, "Synthetic database concurrency fixture.", "FOUND", "TEST_ONLY", "TEST_ONLY", today());
  }
  private TransactionTemplate transaction() {
    var template = new TransactionTemplate(transactions);
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(10);
    return template;
  }
  private <T> T asUser(Long userId, java.util.function.Supplier<T> action) {
    UserContext.set(userId, "USER");
    try { return action.get(); } finally { UserContext.clear(); }
  }
  private <T> T asAdmin(java.util.function.Supplier<T> action) {
    UserContext.set(adminId, "ADMIN");
    try { return action.get(); } finally { UserContext.clear(); }
  }
  private String outcome(Callable<?> action) throws Exception {
    try { action.call(); return "OK"; }
    catch (BusinessException failure) { return failure.getHttpStatus() + ":" + failure.getErrorCode(); }
    finally { UserContext.clear(); }
  }
  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic transaction coordination timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Synthetic transaction coordination interrupted");
    }
  }
  private void assertIneligible(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, failure -> {
      assertThat(failure.getHttpStatus()).isEqualTo(403);
      assertThat(failure.getErrorCode()).isEqualTo("VERIFICATION_REQUIRED");
    });
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {
    @Bean @Primary MutableTestClock identityTestClock() { return new MutableTestClock(); }
  }

  static final class MutableTestClock extends Clock {
    private final AtomicReference<Instant> value;
    private final ZoneId zone;
    MutableTestClock() { this(new AtomicReference<>(BASE_TIME), ZoneOffset.UTC); }
    private MutableTestClock(AtomicReference<Instant> value, ZoneId zone) { this.value = value; this.zone = zone; }
    void set(Instant instant) { value.set(instant); }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId newZone) { return new MutableTestClock(value, newZone); }
    @Override public Instant instant() { return value.get(); }
  }
}
