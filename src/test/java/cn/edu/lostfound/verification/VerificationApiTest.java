package cn.edu.lostfound.verification;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationApiTest {
  private final VerificationStore store=mock(VerificationStore.class);
  private final EntityManager em=mock(EntityManager.class);
  private final MutableClock clock=new MutableClock(Instant.parse("2026-09-30T15:59:59Z"));
  private VerificationApi api;
  private static final Instant EXPIRY=Instant.parse("2026-09-30T16:00:00Z");

  @BeforeEach void setup() { api=new VerificationApi(store,campus(),clock,em); }
  @AfterEach void cleanup() {
    if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
  }

  @Test void inclusiveCampusDateIsConvertedToExclusiveMidnightWithOffset() {
    when(store.find(7L)).thenReturn(Optional.of(qualification(7L,"VERIFIED",true,"TEST_CAMPUS")));
    var summary=api.summary(7L);
    assertThat(summary.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00+08:00"));
    assertThat(summary.validThrough()).isEqualTo(LocalDate.of(2026,9,30));
    assertThat(summary.status()).isEqualTo("VERIFIED");
    api.requireEligible(7L);
  }
  @Test void exactExpiryIsImmediatelyDeniedWithoutChangingStoredHistory() {
    var stored=qualification(7L,"VERIFIED",true,"TEST_CAMPUS");
    when(store.find(7L)).thenReturn(Optional.of(stored));
    clock.now=EXPIRY;
    assertThat(api.summary(7L).status()).isEqualTo("EXPIRED");
    assertThatThrownBy(()->api.requireEligible(7L)).isInstanceOfSatisfying(BusinessException.class,
        ex->assertThat(ex.getErrorCode()).isEqualTo("VERIFICATION_REQUIRED"));
    assertThat(stored.status()).isEqualTo("VERIFIED");
  }
  @Test void futureExpiryDoesNotAuthorizeRevokedOrPendingAccounts() {
    for(String status:new String[]{"UNVERIFIED","PENDING","REJECTED","REVOKED"}) {
      when(store.find(7L)).thenReturn(Optional.of(qualification(7L,status,true,"TEST_CAMPUS")));
      assertThatThrownBy(()->api.requireEligible(7L)).isInstanceOf(BusinessException.class);
    }
  }
  @Test void differentCampusAndRealEnvironmentCannotReuseSyntheticApproval() {
    when(store.find(7L)).thenReturn(Optional.of(qualification(7L,"VERIFIED",true,"OTHER_CAMPUS")));
    assertThatThrownBy(()->api.requireEligible(7L)).isInstanceOf(BusinessException.class);
    when(store.find(7L)).thenReturn(Optional.of(qualification(7L,"VERIFIED",false,"TEST_CAMPUS")));
    assertThatThrownBy(()->api.requireEligible(7L)).isInstanceOf(BusinessException.class);
  }
  @Test void missingCoordinatorIsNotSilentlyCreatedByAuthorization() {
    when(store.find(7L)).thenReturn(Optional.empty());
    assertThatThrownBy(()->api.requireEligible(7L)).isInstanceOfSatisfying(BusinessException.class,
        ex->assertThat(ex.getHttpStatus()).isEqualTo(403));
    verify(store,never()).initialize(any(),any(),any());
  }
  @Test void registrationCreatesOnlyUnverifiedCoordinatorUsingServerConfiguration() {
    api.initialize(7L);
    verify(store).initialize(7L,"TEST_CAMPUS",clock.instant());
  }
  @Test void expiryDuringFlushAbortsAtFinalCommitGuard() {
    when(store.lock(7L)).thenReturn(Optional.of(qualification(7L,"VERIFIED",true,"TEST_CAMPUS")));
    TransactionSynchronizationManager.initSynchronization();
    api.lockEligible(7L);
    doAnswer(invocation->{clock.now=EXPIRY;return null;}).when(em).flush();
    var guard=TransactionSynchronizationManager.getSynchronizations().getFirst();
    assertThatThrownBy(()->guard.beforeCommit(false)).isInstanceOf(BusinessException.class);
    verify(em).flush();
    verify(store,times(2)).lock(7L);
  }
  @Test void severalParticipantsShareOneFlushAndEachGetsACommitTimeCheck() {
    for(Long id:new Long[]{7L,9L}) when(store.lock(id)).thenReturn(Optional.of(qualification(id,"VERIFIED",true,"TEST_CAMPUS")));
    TransactionSynchronizationManager.initSynchronization();
    api.lockEligible(7L);api.lockEligible(9L);api.lockEligible(7L);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    TransactionSynchronizationManager.getSynchronizations().getFirst().beforeCommit(false);
    verify(em,times(1)).flush();
    verify(store,times(3)).lock(7L);
    verify(store,times(2)).lock(9L);
  }
  @Test void revocationObservedByCommitGuardCannotReuseEarlierEligibility() {
    when(store.lock(7L)).thenReturn(Optional.of(qualification(7L,"VERIFIED",true,"TEST_CAMPUS")),
        Optional.of(qualification(7L,"REVOKED",true,"TEST_CAMPUS")));
    TransactionSynchronizationManager.initSynchronization();
    api.lockEligible(7L);
    assertThatThrownBy(()->TransactionSynchronizationManager.getSynchronizations().getFirst().beforeCommit(false))
        .isInstanceOf(BusinessException.class);
  }
  @Test void profileLockDoesNotRequireAnApprovedStudentIdentity() {
    when(store.lock(7L)).thenReturn(Optional.of(qualification(7L,"UNVERIFIED",true,"TEST_CAMPUS")));
    api.lockForAccount(7L);
    verify(store).lock(7L);
    verifyNoInteractions(em);
  }
  static CampusProperties campus() {
    return new CampusProperties("TEST_CAMPUS","测试校园","Asia/Shanghai",true,"合成测试核验","测试管理员");
  }
  static VerificationStore.Qualification qualification(Long id,String status,boolean isTest,String campus) {
    return new VerificationStore.Qualification(id,campus,status,100L,1,EXPIRY,null,2,isTest,"student","USER","测试姓名");
  }
  static final class MutableClock extends Clock {
    Instant now;
    MutableClock(Instant now) { this.now=now; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
