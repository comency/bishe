package cn.edu.lostfound.verification;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static cn.edu.lostfound.verification.VerificationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VerificationServiceTest {
  private final VerificationStore store=mock(VerificationStore.class);
  private final AuditApi audit=mock(AuditApi.class);
  private final Instant now=Instant.parse("2026-09-16T01:00:00Z");
  private final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
  private VerificationService service;
  private VerificationStore.Qualification pending;

  @BeforeEach void setup() {
    var campus=VerificationApiTest.campus();
    var api=new VerificationApi(store,campus,clock,mock(EntityManager.class));
    service=new VerificationService(store,api,audit,campus,clock);
    UserContext.set(1L,"ADMIN");
    var admin=new VerificationStore.Qualification(1L,"TEST_CAMPUS","UNVERIFIED",null,0,null,null,0,true,"admin","ADMIN",null);
    pending=q("PENDING",1L,100L,null);
    when(store.find(1L)).thenReturn(Optional.of(admin));when(store.lock(1L)).thenReturn(Optional.of(admin));
    when(store.find(2L)).thenReturn(Optional.of(pending));when(store.lock(2L)).thenReturn(Optional.of(pending));
    when(store.application(100L)).thenReturn(Optional.of(application("PENDING")));
    when(store.history(2L,1,10)).thenReturn(List.of(application("PENDING")));
    when(store.historyCount(2L)).thenReturn(1L);
  }
  @AfterEach void cleanup() { UserContext.clear(); }

  @Test void approvalChecksCurrentApplicationAndLocksAdminAndSubjectInAscendingOrder() {
    service.review(2L,approve(1L,100L,LocalDate.of(2026,9,30)));
    var order=inOrder(store,audit);
    order.verify(store).lock(1L);order.verify(store).lock(2L);
    order.verify(store).reviewApplication(eq(100L),eq(1L),any(),eq(now));
    order.verify(store).transition(pending,"VERIFIED",Instant.parse("2026-09-30T16:00:00Z"),null,now);
    order.verify(audit).verification("VERIFICATION_APPROVED",1L,true,2L,100L,"PENDING","VERIFIED",2L,null);
  }
  @Test void oldVersionAndWrongApplicationCannotOverwriteANewReview() {
    assertError(()->service.review(2L,approve(0L,100L,LocalDate.of(2026,9,30))),"VERSION_CONFLICT");
    assertError(()->service.review(2L,approve(1L,99L,LocalDate.of(2026,9,30))),"STATE_CONFLICT");
    verify(store,never()).reviewApplication(any(),any(),any(),any());verifyNoInteractions(audit);
  }
  @Test void selfReviewAndSelfReopenAreForbiddenEvenForAdmin() {
    assertError(()->service.review(1L,approve(0L,100L,LocalDate.of(2026,9,30))),"FORBIDDEN");
    assertError(()->service.reopen(1L,new VersionReasonRequest(0L,"核对原因")),"FORBIDDEN");
    verify(store,never()).lock(any());verifyNoInteractions(audit);
  }
  @Test void previousDayCannotProduceAnAlreadyExpiredApproval() {
    assertError(()->service.review(2L,approve(1L,100L,LocalDate.of(2026,9,15))),"VALIDATION_ERROR");
    verify(store,never()).reviewApplication(any(),any(),any(),any());
  }
  @Test void approvalRequiresEvidenceAndRejectionRejectsApprovalFields() {
    assertError(()->service.review(2L,new ReviewRequest(100L,1L,Decision.APPROVED,Method.IN_PERSON,"  ",
        LocalDate.of(2026,9,30),null,null)),"VALIDATION_ERROR");
    assertError(()->service.review(2L,new ReviewRequest(100L,1L,Decision.REJECTED,Method.ROSTER,null,null,"补充材料",null)),
        "VALIDATION_ERROR");
    verifyNoInteractions(audit);
  }
  @Test void rejectionReasonIsPublicButInternalNotesNeverEnterTheAuditEvent() {
    var request=new ReviewRequest(100L,1L,Decision.REJECTED,null,null,null,"请补充当前在校证明","私密管理备注");
    service.review(2L,request);
    verify(store).transition(pending,"REJECTED",null,"请补充当前在校证明",now);
    verify(audit).verification("VERIFICATION_REJECTED",1L,true,2L,100L,"PENDING","REJECTED",2L,"请补充当前在校证明");
  }
  @Test void pendingAndRevokedAccountsCannotSubmitAnotherApplication() {
    UserContext.set(2L,"USER");
    assertError(()->service.submit(new SubmitRequest(1L,"测试人",null,null)),"STATE_CONFLICT");
    when(store.lock(2L)).thenReturn(Optional.of(q("REVOKED",1L,100L,null)));
    assertError(()->service.submit(new SubmitRequest(1L,"测试人",null,null)),"STATE_CONFLICT");
    verify(store,never()).insertApplication(any(),any(),any());
  }
  @Test void expiredAccountCreatesANewApplicationWithoutRewritingTheOldApproval() {
    UserContext.set(2L,"USER");
    var expired=q("VERIFIED",2L,100L,now);
    when(store.lock(2L)).thenReturn(Optional.of(expired));
    when(store.insertApplication(eq(expired),any(),eq(now))).thenReturn(101L);
    service.submit(new SubmitRequest(2L,"测试申请人",null,null));
    verify(store).submit(expired,101L,now);
    verify(audit).verification("VERIFICATION_SUBMITTED",2L,false,2L,101L,"EXPIRED","PENDING",3L,null);
    verify(store,never()).reviewApplication(any(),any(),any(),any());
  }
  @Test void revokeRetainsOldApprovalExpiryAndReopenOnlyReturnsToUnverified() {
    Instant expiry=Instant.parse("2026-09-30T16:00:00Z");
    var verified=q("VERIFIED",2L,100L,expiry);
    when(store.lock(2L)).thenReturn(Optional.of(verified));
    service.revoke(2L,new VersionReasonRequest(2L,"核验依据失效"));
    verify(store).transition(verified,"REVOKED",expiry,"核验依据失效",now);
    var revoked=q("REVOKED",3L,100L,expiry);
    when(store.lock(2L)).thenReturn(Optional.of(revoked));
    service.reopen(2L,new VersionReasonRequest(3L,"允许重新核验"));
    verify(store).transition(revoked,"UNVERIFIED",null,"允许重新核验",now);
    verify(store,never()).reviewApplication(any(),any(),any(),any());
  }
  @Test void expiredQualificationCannotBeRevokedAsIfStillEffective() {
    when(store.lock(2L)).thenReturn(Optional.of(q("VERIFIED",2L,100L,now)));
    assertError(()->service.revoke(2L,new VersionReasonRequest(2L,"撤销")),"STATE_CONFLICT");
    verifyNoInteractions(audit);
  }
  @Test void applicantProjectionExcludesEvidenceMethodReviewerAndInternalNotes() throws Exception {
    UserContext.set(2L,"USER");
    when(store.application(100L)).thenReturn(Optional.of(application("VERIFIED")));
    when(store.history(2L,1,10)).thenReturn(List.of(application("VERIFIED")));
    String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(service.mine(1,10));
    assertThat(json).contains("studentNumber","realName","测试申请人");
    assertThat(json).doesNotContain("evidenceSummary","method","reviewerId","internalNote","内部核验依据","管理员内部备注");
  }
  @Test void listCalculatesExpiredStatusUsingTheSameTimeAsDatabaseFiltering() {
    var expired=q("VERIFIED",2L,100L,now);
    when(store.list("EXPIRED","测试",2L,now,2,5)).thenReturn(List.of(expired));
    when(store.listCount("EXPIRED","测试",2L,now)).thenReturn(6L);
    var result=service.list(Status.EXPIRED,"测试",2L,2,5);
    assertThat(result.records()).singleElement().extracting(AdminSummary::status).isEqualTo("EXPIRED");
    assertThat(result.total()).isEqualTo(6);assertThat(result.page()).isEqualTo(2);
  }
  @Test void invalidPaginationAndNonAdminCannotReadPrivateRecords() {
    assertError(()->service.list(null,null,null,0,10),"VALIDATION_ERROR");
    UserContext.set(2L,"USER");
    assertError(()->service.detail(2L,1,10),"FORBIDDEN");
    assertError(()->service.list(null,null,null,1,10),"FORBIDDEN");
  }
  @Test void administratorFromAnotherEnvironmentCannotReadVerificationMaterials() {
    var otherEnvironment=new VerificationStore.Qualification(1L,"TEST_CAMPUS","UNVERIFIED",null,0,
        null,null,0,false,"admin","ADMIN",null);
    when(store.find(1L)).thenReturn(Optional.of(otherEnvironment));
    assertError(()->service.detail(2L,1,10),"VERIFICATION_REQUIRED");
    assertError(()->service.list(null,null,null,1,10),"VERIFICATION_REQUIRED");
    verify(store,never()).history(anyLong(),anyInt(),anyInt());
  }
  @Test void auditFailurePropagatesToTheOuterTransactionInsteadOfReturningSuccess() {
    doThrow(new IllegalStateException("audit unavailable")).when(audit).verification(anyString(),anyLong(),anyBoolean(),
        anyLong(),anyLong(),anyString(),anyString(),anyLong(),isNull());
    assertThatThrownBy(()->service.review(2L,approve(1L,100L,LocalDate.of(2026,9,30))))
        .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
  }
  private VerificationStore.Qualification q(String status,long version,Long applicationId,Instant expiry) {
    return new VerificationStore.Qualification(2L,"TEST_CAMPUS",status,applicationId,1,expiry,null,version,true,"student","USER","测试申请人");
  }
  private VerificationStore.ApplicationRow application(String status) {
    return new VerificationStore.ApplicationRow(100L,2L,1,status,"测试申请人","TEST-001","合成申请说明",now,null,
        null,null,"IN_PERSON","内部核验依据","管理员内部备注",1L,true);
  }
  private ReviewRequest approve(long version,Long applicationId,LocalDate validThrough) {
    return new ReviewRequest(applicationId,version,Decision.APPROVED,Method.IN_PERSON,"合成核验依据",validThrough,null,"内部备注");
  }
  private void assertError(Runnable action,String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
        ex->assertThat(ex.getErrorCode()).isEqualTo(code));
  }
}
