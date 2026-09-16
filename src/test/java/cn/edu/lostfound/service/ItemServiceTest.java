package cn.edu.lostfound.service;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.claim.ClaimApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.*;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.media.MediaService;
import cn.edu.lostfound.repository.*;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemServiceTest {
  ItemRepository repository=mock(ItemRepository.class);
  VerificationApi verification=mock(VerificationApi.class);
  AuditApi audit=mock(AuditApi.class);
  MediaService media=mock(MediaService.class);
  ClaimApi claims=mock(ClaimApi.class);
  AccountApi accounts=mock(AccountApi.class);
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  CampusProperties campus=mock(CampusProperties.class);
  ItemService service;
  Item item;
  @BeforeEach void setup(){
    when(campus.zoneId()).thenReturn(ZoneId.of("Asia/Shanghai"));
    service=new ItemService(repository,mock(UserRepository.class),verification,accounts,media,audit,claims,jdbc,Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"),ZoneOffset.UTC),campus,"Asia/Shanghai");
    User owner=new User("owner","unused","测试发布者","USER");ReflectionTestUtils.setField(owner,"id",1L);
    item=new Item(owner,"校园水杯","蓝色水杯","LOST",null,null,null);ReflectionTestUtils.setField(item,"id",10L);
    when(repository.lockById(10L)).thenReturn(Optional.of(item));when(repository.findById(10L)).thenReturn(Optional.of(item));
    when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
  }
  ItemDtos.Update edit(){var r=new ItemDtos.Update();r.title="找到水杯";r.description="蓝色水杯";r.type="FOUND";r.expectedVersion=0L;return r;}
  @ParameterizedTest @ValueSource(strings={"PENDING","APPROVED","REJECTED"})
  void editsAlwaysInvalidateReview(String status){
    item.setStatus(status);service.update(10,1,edit());
    assertThat(item.getStatus()).isEqualTo("PENDING");assertThat(item.getContentVersion()).isEqualTo(2);assertThat(item.getType()).isEqualTo("FOUND");
    verify(verification).lockEligible(1L);verify(media).bind(10,1,null);
    verify(audit).item("ITEM_EDITED",1L,false,1L,10L,status,"PENDING",0,2,null,null);
  }
  @Test void closedCannotBeEdited(){item.close("WITHDRAWN","测试",Instant.now());assertConflict(()->service.update(10,1,edit()),"STATE_CONFLICT");verifyNoInteractions(audit);}
  @Test void staleVersionCannotWrite(){var r=edit();r.expectedVersion=4L;assertConflict(()->service.update(10,1,r),"VERSION_CONFLICT");verifyNoInteractions(media,audit);}
  @Test void ownerOnlyEvenForAdministrator(){assertConflict(()->service.update(10,2,edit()),"NOT_ACCESSIBLE");}
  @Test void futureDateRejected(){var r=edit();r.occurredAt=LocalDate.of(2026,9,17);assertThatThrownBy(()->service.update(10,1,r)).isInstanceOf(IllegalArgumentException.class);}
  @Test void reviewRequiresPendingAndVersion(){item.setStatus("APPROVED");assertConflict(()->service.review(10,2,new ItemDtos.Review("APPROVED",0L,null,null)),"STATE_CONFLICT");}
  @Test void rejectionRequiresReason(){assertThatThrownBy(()->service.review(10,2,new ItemDtos.Review("REJECTED",0L," ",null))).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(audit);}
  @Test void approvalDisallowsRejectionReason(){assertThatThrownBy(()->service.review(10,2,new ItemDtos.Review("APPROVED",0L,"reason",null))).isInstanceOf(IllegalArgumentException.class);}
  @Test void closedReviewUsesUnifiedClosure(){service.review(10,2,new ItemDtos.Review("CLOSED",0L,"测试下架","管理员内部"));assertThat(item.getCloseReason()).isEqualTo("ADMIN_REMOVED");verify(audit).item("ITEM_CLOSED",2L,true,1L,10L,"PENDING","CLOSED",0,1,"测试下架","管理员内部");}
  @Test void foundCannotBeClosedAsFoundByOwner(){item.update("x","y","FOUND",null,null,null);assertThatThrownBy(()->service.close(10,1,new ItemDtos.Close(0L,"FOUND_BY_OWNER","测试"))).isInstanceOf(IllegalArgumentException.class);}
  @Test void ordinaryCloseCannotMarkReturned(){assertThatThrownBy(()->service.close(10,1,new ItemDtos.Close(0L,"RETURNED","测试"))).isInstanceOf(IllegalArgumentException.class);}
  @Test void privateDetailIsHidden(){assertConflict(()->service.get(10,2,false,1,10),"NOT_ACCESSIBLE");}
  @ParameterizedTest @ValueSource(strings={"PENDING","REJECTED","CLOSED"})
  void privateMatchingDoesNotGrantAdminBypass(String state){item.setStatus(state);assertConflict(()->service.matches(10L,2L,true),"NOT_ACCESSIBLE");verify(repository,never()).search(any(),any());}
  @Test void matchesPreserveNullAndFilterZero(){
    var owner=item.getPublisher();var match=new Item(owner,"水杯","蓝色水杯","FOUND",null,null,null);ReflectionTestUtils.setField(match,"id",20L);
    var unrelated=new Item(owner,"abc","xyz","FOUND",null,null,null);ReflectionTestUtils.setField(unrelated,"id",21L);
    when(repository.search("","FOUND")).thenReturn(List.of(unrelated,match));var result=service.matches(10L,1L,false);
    assertThat(result).hasSize(1);assertThat(result.get(0).get("location")).isNull();assertThat((double)result.get(0).get("score")).isBetween(0.01,100.0);
  }
  @Test void invalidPageSizeRejected(){assertThatThrownBy(()->service.page(1,"public",1,51,"","","","",null)).isInstanceOf(IllegalArgumentException.class);}
  @ParameterizedTest @ValueSource(strings={"public","mine","admin"})
  void pagesBatchOnlySelectedIdsAfterAuthorizationAndKeepSummaryContract(String scope){
    var second=new Item(item.getPublisher(),"Another","Private description","FOUND",null,null,null);
    ReflectionTestUtils.setField(second,"id",20L);
    when(repository.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(List.of(second,item)));
    var image=new MediaService.Meta(51L,"image/png",10,1,1,"/api/uploads/images/51","BOUND",Instant.EPOCH,null);
    when(media.imagesForItems(List.of(20L,10L))).thenReturn(Map.of(20L,List.of(image)));
    when(claims.summariesForItems(List.of(20L,10L),1)).thenReturn(Map.of(20L,new ClaimApi.PageSummary(true,70L)));
    var page=service.page(1,scope,1,10,"","","","",null);
    assertThat(page.records()).extracting(m->m.get("id")).containsExactly(20L,10L);
    assertThat(page.records().getFirst()).containsEntry("images",List.of(image)).containsEntry("hasAcceptedClaim",true).containsEntry("myClaimId",70L);
    assertThat(page.records().getLast()).containsEntry("images",List.of()).containsEntry("hasAcceptedClaim",false).containsEntry("myClaimId",null);
    for(var record:page.records())assertThat(record).doesNotContainKeys("description","contact","identification","internalNote","reviewReason");
    var order=inOrder(accounts,verification,repository,media,claims);
    if(scope.equals("admin"))order.verify(accounts).requireAdministrator(1L);else order.verify(verification).requireEligible(1L);
    order.verify(repository).findAll(any(Specification.class),any(Pageable.class));
    order.verify(media).imagesForItems(List.of(20L,10L));order.verify(claims).summariesForItems(List.of(20L,10L),1);
    verifyNoMoreInteractions(media,claims);
  }
  @Test void emptyPageKeepsTotalWithoutSummaryQueries(){
    when(repository.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(List.of(),PageRequest.of(2,10),20));
    var page=service.page(1,"public",3,10,"","","","",null);
    assertThat(page.total()).isEqualTo(20);assertThat(page.records()).isEmpty();verifyNoInteractions(media,claims);
  }
  @Test void fiftyItemsStillUseOneBatchPerSummaryProvider(){
    var entries=new ArrayList<Item>();
    for(long id=1;id<=50;id++){var entry=new Item(item.getPublisher(),"Synthetic","Private","FOUND",null,null,null);ReflectionTestUtils.setField(entry,"id",id);entries.add(entry);}
    when(repository.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(entries));
    assertThat(service.page(1,"public",1,50,"","","","",null).records()).hasSize(50);
    var ids=entries.stream().map(Item::getId).toList();verify(media).imagesForItems(ids);verify(claims).summariesForItems(ids,1);verifyNoMoreInteractions(media,claims);
  }
  @Test void deniedPageDoesNotReadItemsOrSummaries(){
    doThrow(new BusinessException(403,"VERIFICATION_REQUIRED","Synthetic")).when(verification).requireEligible(1L);
    assertConflict(()->service.page(1,"public",1,10,"","","","",null),"VERIFICATION_REQUIRED");
    verifyNoInteractions(repository,media,claims);
  }
  @Test void legacyMissingTimesRemainUnknownInsteadOfCrashingOrInventingDates(){
    ReflectionTestUtils.setField(item,"createdAt",null);ReflectionTestUtils.setField(item,"updatedAt",null);
    var result=service.get(10,1,false,1,10);
    assertThat(result).containsEntry("createdAt",null).containsEntry("updatedAt",null);
  }
  @Test void legacyMissingUpdatedTimeDoesNotFallBackToCreationTime(){
    ReflectionTestUtils.setField(item,"createdAt",LocalDateTime.of(2026,1,2,3,4,5));ReflectionTestUtils.setField(item,"updatedAt",null);
    var result=service.get(10,1,false,1,10);
    assertThat(result).containsEntry("createdAt",Instant.parse("2026-01-01T19:04:05Z")).containsEntry("updatedAt",null);
  }
  static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
}
