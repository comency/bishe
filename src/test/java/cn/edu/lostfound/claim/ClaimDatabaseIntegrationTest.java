package cn.edu.lostfound.claim;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.*;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.service.*;
import cn.edu.lostfound.verification.VerificationService;
import static cn.edu.lostfound.verification.VerificationDtos.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named="RUN_CLAIM_DB_TESTS",matches="true")
class ClaimDatabaseIntegrationTest {
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired ItemService items;
  @Autowired ClaimService claims;
  @Autowired ClaimStore store;
  @Autowired ClaimApi claimApi;
  @Autowired VerificationService verification;
  @Autowired JdbcTemplate jdbc;
  @Autowired CampusProperties campus;
  @Autowired PlatformTransactionManager transactions;
  @SpyBean AuditApi audit;
  long owner,applicant,other,admin,itemId;
  @BeforeEach void setup(){
    assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("lost_found_test");assertThat(campus.testMode()).isTrue();
    admin=users.findByUsername("admin").orElseThrow().getId();owner=user();applicant=user();other=user();
    var r=new ItemDtos.Create();r.title="Synthetic claim item";r.description="Synthetic only";r.type="FOUND";
    itemId=id(items.create(owner,r));items.review(itemId,admin,new ItemDtos.Review("APPROVED",0L,null,null));
  }
  @AfterEach void clear(){UserContext.clear();}
  long user(){
    String name="claimdb_"+UUID.randomUUID().toString().replace("-","");auth.register(new AuthDtos.Register(name,"Synthetic-password-only","Synthetic claim user"));
    long id=users.findByUsername(name).orElseThrow().getId();UserContext.set(id,"USER");var pending=verification.submit(new SubmitRequest(0L,"Synthetic Claimant",null,"No real personal data"));
    UserContext.set(admin,"ADMIN");verification.review(id,new ReviewRequest(pending.currentApplication().id(),pending.summary().version(),Decision.APPROVED,Method.IN_PERSON,"Synthetic only",LocalDate.now(campus.zoneId()).plusDays(2),null,null));UserContext.clear();return id;
  }
  long id(Map<String,Object> map){return ((Number)map.get("id")).longValue();}
  long apply(long actor){return id(claims.create(itemId,actor,new ClaimDtos.Create(itemVersion(),"PRIVATE_EVIDENCE","PRIVATE_APPLICANT_CONTACT")));}
  long itemVersion(){return jdbc.queryForObject("SELECT row_version FROM items WHERE id=?",Long.class,itemId);}
  Map<String,Object> accept(long id){return claims.accept(id,owner,new ClaimDtos.Accept(0L,"PRIVATE_PUBLISHER_CONTACT"));}
  ClaimStore.Row row(long id){return store.find(id).orElseThrow();}
  static void failure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
  void expire(long id){jdbc.update("UPDATE campus_verifications SET expires_at=? WHERE user_id=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5),id);}

  @Test void snapshotsArePrivateAndDuplicateApplicationIsPermanent(){
    long id=apply(applicant);assertThat(itemVersion()).isEqualTo(1);var detail=claims.get(id,applicant,false,1,10);
    assertThat(detail).containsEntry("counterpartContact",null).doesNotContainKeys("applicantContactSnapshot","internalNote");
    assertThat(claims.page(owner,"incoming",1,10,"",null,null,null).records().getFirst()).doesNotContainKeys("identification","counterpartContact");
    failure(()->claims.get(id,other,false,1,10),"NOT_ACCESSIBLE");
    claims.end(id,applicant,new ClaimDtos.End(0L,"Synthetic cancellation"),false);
    failure(()->apply(applicant),"CLAIM_EXISTS");
    var edit=new ItemDtos.Update();edit.title="Changed title";edit.description="Edited synthetic";edit.type="FOUND";edit.expectedVersion=itemVersion();items.update(itemId,owner,edit);
    assertThat(((Map<?,?>)claims.get(id,applicant,false,1,10).get("item")).get("title")).isEqualTo("Synthetic claim item");
  }
  @Test void batchClaimSummaryNeverReturnsAnotherApplicantsIdAcrossStates(){
    long accepted=apply(applicant),waiting=apply(other);accept(accepted);
    for(long actor:List.of(owner,applicant,other,admin)){
      var batch=claimApi.summariesForItems(List.of(itemId),actor).get(itemId);
      assertThat(batch.hasAcceptedClaim()).isTrue();assertThat(batch.myClaimId()).isEqualTo(claimApi.myClaim(itemId,actor));
      var page=items.page(actor,actor==admin?"admin":"public",1,10,"","",null,null,itemId).records().getFirst();
      Long expected=actor==applicant?Long.valueOf(accepted):actor==other?Long.valueOf(waiting):null;
      assertThat(page).containsEntry("hasAcceptedClaim",true).containsEntry("myClaimId",expected)
          .doesNotContainKeys("identification","evidence","applicantContactSnapshot","publisherContactSnapshot","internalNote");
    }
    claims.end(waiting,other,new ClaimDtos.End(0L,"Synthetic cancellation"),false);
    assertThat(claimApi.summariesForItems(List.of(itemId),other).get(itemId).myClaimId()).isEqualTo(waiting);
    claims.confirm(accepted,owner,true);claims.confirm(accepted,applicant,false);
    assertThat(claimApi.summariesForItems(List.of(itemId),owner)).isEmpty();
    assertThat(claimApi.summariesForItems(List.of(itemId),applicant).get(itemId)).isEqualTo(new ClaimApi.PageSummary(false,accepted));
    assertThat(items.page(owner,"mine",1,10,"","",null,null,itemId).records().getFirst()).containsEntry("hasAcceptedClaim",false).containsEntry("myClaimId",null);
  }
  @Test void batchClaimsAreRestrictedToRequestedItemsIncludingRejectedHistory(){
    long rejected=apply(applicant);claims.end(rejected,owner,new ClaimDtos.End(0L,"Synthetic rejection"),true);
    var create=new ItemDtos.Create();create.title="Another synthetic item";create.description="Synthetic";create.type="FOUND";
    long unrelated=id(items.create(owner,create));
    assertThat(claimApi.summariesForItems(List.of(unrelated),applicant)).isEmpty();
    assertThat(claimApi.summariesForItems(List.of(itemId,unrelated),applicant))
        .containsOnlyKeys(itemId).containsEntry(itemId,new ClaimApi.PageSummary(false,rejected));
  }
  @Test void activeClaimBlocksEditingAndOwnerClosure(){
    apply(applicant);var edit=new ItemDtos.Update();edit.title="Changed";edit.description="Synthetic";edit.type="FOUND";edit.expectedVersion=itemVersion();
    failure(()->items.update(itemId,owner,edit),"STATE_CONFLICT");
    failure(()->items.close(itemId,owner,new ItemDtos.Close(itemVersion(),"WITHDRAWN","Synthetic")),"STATE_CONFLICT");assertThat(itemVersion()).isEqualTo(1);
  }
  @Test void simultaneousAcceptanceHasExactlyOneWinner()throws Exception{
    long a=apply(applicant),b=apply(other);try(var pool=Executors.newFixedThreadPool(2)){
      var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
      for(long id:List.of(a,b))results.add(pool.submit(()->{start.await();try{accept(id);return true;}catch(BusinessException e){assertThat(e.getErrorCode()).isEqualTo("STATE_CONFLICT");return false;}}));
      start.countDown();assertThat(List.of(results.get(0).get(10,TimeUnit.SECONDS),results.get(1).get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
    }
    assertThat(itemVersion()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM claims WHERE item_id=? AND status='ACCEPTED'",Long.class,itemId)).isEqualTo(1);
  }
  @Test void simultaneousConfirmationsCompleteAtomicallyAndRetriesAreReadOnly()throws Exception{
    long id=apply(applicant),loser=apply(other);accept(id);
    try(var pool=Executors.newFixedThreadPool(2)){
      var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return claims.confirm(id,owner,true);});var b=pool.submit(()->{start.await();return claims.confirm(id,applicant,false);});start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
    }
    var before=row(id);assertThat(before.status()).isEqualTo("COMPLETED");assertThat(before.version()).isEqualTo(3);assertThat(row(loser).status()).isEqualTo("REJECTED");assertThat(itemVersion()).isEqualTo(3);
    long logs=jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE item_id=?",Long.class,itemId);
    expire(applicant);assertThat(claims.confirm(id,owner,true)).containsEntry("status","COMPLETED").containsEntry("counterpartContact",null);
    assertThat(row(id)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE item_id=?",Long.class,itemId)).isEqualTo(logs);
    assertThat(jdbc.queryForObject("SELECT close_reason FROM items WHERE id=?",String.class,itemId)).isEqualTo("RETURNED");
  }
  @Test void cancellationAfterAcceptanceReleasesOccupationWithoutChoosingAnother(){
    long id=apply(applicant),waiting=apply(other);accept(id);expire(applicant);
    claims.end(id,owner,new ClaimDtos.End(1L,"Cannot meet"),false);assertThat(itemVersion()).isEqualTo(3);assertThat(row(waiting).status()).isEqualTo("APPLIED");
    assertThat(claims.get(id,owner,false,1,10)).containsEntry("counterpartContact",null);failure(()->claims.confirm(id,owner,true),"STATE_CONFLICT");
    accept(waiting);assertThat(itemVersion()).isEqualTo(4);
  }
  @Test void invalidCounterpartBlocksProgressButNotReadingOrRejection(){
    long id=apply(applicant);expire(applicant);failure(()->accept(id),"COUNTERPART_INELIGIBLE");
    claims.end(id,owner,new ClaimDtos.End(0L,"Synthetic rejection"),true);assertThat(row(id).status()).isEqualTo("REJECTED");
  }
  @Test void initialApplicationDoesNotRequirePublisherEligibility(){
    expire(owner);long id=apply(applicant);assertThat(row(id).status()).isEqualTo("APPLIED");
  }
  @Test void duplicateFirstConfirmationDoesNotWriteAndSingleConfirmCannotCancelOrAdminClose(){
    long id=apply(applicant);accept(id);claims.confirm(id,applicant,false);var first=row(id);claims.confirm(id,applicant,false);assertThat(row(id)).isEqualTo(first);
    failure(()->claims.end(id,owner,new ClaimDtos.End(2L,"Synthetic"),false),"STATE_CONFLICT");
    failure(()->items.adminClose(itemId,admin,new ItemDtos.AdminClose(itemVersion(),"Synthetic")),"STATE_CONFLICT");
    expire(owner);failure(()->claims.confirm(id,applicant,false),"COUNTERPART_INELIGIBLE");assertThat(claims.get(id,applicant,false,1,10)).containsEntry("canConfirm",false).containsEntry("counterpartContact","PRIVATE_PUBLISHER_CONTACT");
  }
  @Test void resolveContinuePreservesFactsAndTerminateKeepsSingleConfirmation(){
    long id=apply(applicant),waiting=apply(other);accept(id);claims.confirm(id,owner,true);var handover=row(id).handedOverAt();expire(applicant);
    claims.resolve(id,admin,new ClaimDtos.Resolve(2L,"CONTINUE","PRIVATE_CONCLUSION","Wait for verification","PRIVATE_NOTE"));
    assertThat(row(id).status()).isEqualTo("ACCEPTED");assertThat(row(id).handedOverAt()).isEqualTo(handover);assertThat(itemVersion()).isEqualTo(2);
    var ordinary=claims.get(id,owner,false,1,10);assertThat(ordinary.toString()).doesNotContain("PRIVATE_CONCLUSION","PRIVATE_NOTE");
    failure(()->claims.resolve(id,admin,new ClaimDtos.Resolve(2L,"TERMINATE","Synthetic","Stop",null)),"VERSION_CONFLICT");
    var ended=claims.resolve(id,admin,new ClaimDtos.Resolve(3L,"TERMINATE","PRIVATE_CONCLUSION","Synthetic stop","PRIVATE_NOTE"));
    assertThat(ended).containsEntry("status","CANCELLED");assertThat(row(id).handedOverAt()).isEqualTo(handover);assertThat(row(id).receivedAt()).isNull();assertThat(row(waiting).status()).isEqualTo("REJECTED");
    assertThat(jdbc.queryForObject("SELECT close_reason FROM items WHERE id=?",String.class,itemId)).isEqualTo("ADMIN_REMOVED");
    assertThat(jdbc.queryForObject("SELECT exception_terminated FROM claims WHERE id=?",Boolean.class,id)).isTrue();
  }
  @Test void ordinaryAdminCloseCascadesZeroConfirmationClaims(){
    long id=apply(applicant),waiting=apply(other);accept(id);items.adminClose(itemId,admin,new ItemDtos.AdminClose(itemVersion(),"Synthetic takedown"));
    assertThat(row(id).status()).isEqualTo("CANCELLED");assertThat(row(waiting).status()).isEqualTo("REJECTED");
  }
  @Test void auditFailureRollsBackCompletionItemAndOtherClaims(){
    long id=apply(applicant),waiting=apply(other);accept(id);claims.confirm(id,owner,true);
    AuditApi target=AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("Synthetic audit failure")).when(target).item(eq("ITEM_CLOSED"),anyLong(),anyBoolean(),anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyLong(),anyString(),nullable(String.class));
    assertThatThrownBy(()->claims.confirm(id,applicant,false)).isInstanceOf(IllegalStateException.class);
    assertThat(row(id).status()).isEqualTo("ACCEPTED");assertThat(row(id).receivedAt()).isNull();assertThat(row(waiting).status()).isEqualTo("APPLIED");assertThat(itemVersion()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE claim_id=? AND event_type='CLAIM_COMPLETED'",Long.class,id)).isZero();
  }
  @Test void counterpartExpiryAtCommitRollsBackAcceptanceAndAudit(){
    long id=apply(applicant);AuditApi target=AopTestUtils.getUltimateTargetObject(audit);
    doAnswer(invocation->{expire(applicant);return invocation.callRealMethod();}).when(target).claim(eq("CLAIM_ACCEPTED"),anyLong(),anyBoolean(),anyLong(),anyLong(),anyString(),anyString(),anyLong(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class));
    failure(()->accept(id),"COUNTERPART_INELIGIBLE");assertThat(row(id).status()).isEqualTo("APPLIED");assertThat(itemVersion()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_logs WHERE claim_id=? AND event_type='CLAIM_ACCEPTED'",Long.class,id)).isZero();
  }
  @Test void databaseRejectsSecondExclusiveClaimAndSelfClaim(){
    long id=apply(applicant),otherId=apply(other);accept(id);
    assertThatThrownBy(()->jdbc.update("UPDATE claims SET status='ACCEPTED',accepted_at=UTC_TIMESTAMP(6),publisher_contact_snapshot='Synthetic' WHERE id=?",otherId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(()->jdbc.update("UPDATE claims SET applicant_id=publisher_id WHERE id=?",otherId))
      .isInstanceOfSatisfying(org.springframework.jdbc.UncategorizedSQLException.class,e->assertThat(e.getSQLException().getErrorCode()).isEqualTo(3819));
  }
  @Test void databaseEnforcesAllTwentyStatusConfirmationCombinations(){
    long id=apply(applicant);var tx=new TransactionTemplate(transactions);int legal=0;
    for(String status:List.of("APPLIED","ACCEPTED","REJECTED","CANCELLED","COMPLETED"))for(int mask=0;mask<4;mask++){
      boolean h=(mask&1)!=0,r=(mask&2)!=0,exceptional=status.equals("CANCELLED")&&(h!=r);
      boolean valid=switch(status){case "APPLIED","REJECTED"->mask==0;case "ACCEPTED","CANCELLED"->mask!=3;default->mask==3;};
      var time=LocalDateTime.now(ZoneOffset.UTC);boolean accepted=Set.of("ACCEPTED","COMPLETED").contains(status)||status.equals("CANCELLED")&&mask!=0;
      boolean ended=Set.of("REJECTED","CANCELLED","COMPLETED").contains(status),reason=Set.of("REJECTED","CANCELLED").contains(status);
      final int bits=mask;
      var update=(org.assertj.core.api.ThrowableAssert.ThrowingCallable)()->tx.execute(s->{s.setRollbackOnly();jdbc.update("UPDATE claims SET status=?,accepted_at=?,publisher_contact_snapshot=?,handed_over_at=?,received_at=?,completed_at=?,ended_at=?,end_reason_code=?,end_reason=?,exception_terminated=? WHERE id=?",status,accepted?time:null,accepted?"Synthetic":null,h?time:null,r?time:null,status.equals("COMPLETED")?time:null,ended?time:null,reason?"ADMIN_REMOVED":null,reason?"Synthetic":null,exceptional,id);return null;});
      if(valid){assertThatCode(update).as("%s mask %d",status,bits).doesNotThrowAnyException();legal++;}else assertThatThrownBy(update).as("%s mask %d",status,bits)
        .isInstanceOfSatisfying(org.springframework.jdbc.UncategorizedSQLException.class,e->assertThat(e.getSQLException().getErrorCode()).isEqualTo(3819));
    }assertThat(legal).isEqualTo(9);
  }
}
