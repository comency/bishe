package cn.edu.lostfound.media;

import cn.edu.lostfound.audit.AuditApi;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Opt-in real MySQL checks. Only synthetic new accounts/items are modified. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named="RUN_ITEM_DB_TESTS",matches="true")
class ItemMediaDatabaseIntegrationTest {
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired ItemService items;
  @Autowired MediaService media;
  @Autowired VerificationService verification;
  @Autowired JdbcTemplate jdbc;
  @Autowired CampusProperties campus;
  @SpyBean AuditApi audit;
  long owner,admin;
  final byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jJ1kAAAAASUVORK5CYII=");
  @BeforeEach void setup(){
    assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("lost_found_test");assertThat(campus.testMode()).isTrue();
    admin=users.findByUsername("admin").orElseThrow().getId();
    String username="itemdb_"+UUID.randomUUID().toString().replace("-","");
    auth.register(new AuthDtos.Register(username,"Synthetic-password-only","Synthetic item DB user"));owner=users.findByUsername(username).orElseThrow().getId();
    UserContext.set(owner,"USER");var pending=verification.submit(new SubmitRequest(0L,"Synthetic Applicant",null,"No real personal data"));
    UserContext.set(admin,"ADMIN");verification.review(owner,new ReviewRequest(pending.currentApplication().id(),pending.summary().version(),Decision.APPROVED,Method.IN_PERSON,"Synthetic only",LocalDate.now(campus.zoneId()).plusDays(2),null,null));UserContext.clear();
    System.out.println("ITEM_DB_SYNTHETIC_USER="+owner);
  }
  @AfterEach void clear(){UserContext.clear();}
  MediaService.Meta upload(){return media.upload(owner,new MockMultipartFile("file","../../name.png","text/plain",png));}
  ItemDtos.Create create(Long...ids){var c=new ItemDtos.Create();c.title="Synthetic item";c.description="Synthetic item description";c.type="FOUND";c.setImageIds(List.of(ids));return c;}
  long id(Map<String,Object> item){return ((Number)item.get("id")).longValue();}
  @Test void auditFailureRollsBackItemAndImageBinding(){
    var picture=upload();
    AuditApi target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
    doThrow(new IllegalStateException("Synthetic audit failure")).when(target).item(eq("ITEM_CREATED"),eq(owner),anyBoolean(),anyLong(),anyLong(),nullable(String.class),anyString(),anyLong(),anyLong(),nullable(String.class),nullable(String.class));
    assertThatThrownBy(()->items.create(owner,create(picture.id()))).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE publisher_id=?",Long.class,owner)).isZero();
    assertThat(jdbc.queryForObject("SELECT lifecycle FROM media_files WHERE id=?",String.class,picture.id())).isEqualTo("TEMPORARY");
    assertThat(media.read(picture.id(),owner,false).bytes()).isEqualTo(png);
  }
  @Test void simultaneousBindingHasOneWinner()throws Exception {
    var picture=upload();
    try(var pool=Executors.newFixedThreadPool(2)){
      var start=new CountDownLatch(1);
      Callable<Boolean> task=()->{start.await();try{items.create(owner,create(picture.id()));return true;}catch(cn.edu.lostfound.common.BusinessException e){assertThat(e.getErrorCode()).isEqualTo("STATE_CONFLICT");return false;}};
      var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM item_images WHERE media_id=?",Long.class,picture.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE publisher_id=?",Long.class,owner)).isEqualTo(1);
  }
  @Test void cleanupNeverDeletesBoundImageAndRetriesPurging(){
    var bound=upload();items.create(owner,create(bound.id()));var expired=upload();
    jdbc.update("UPDATE media_files SET expires_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusDays(1),expired.id());
    media.cleanup();
    assertThat(jdbc.queryForObject("SELECT lifecycle FROM media_files WHERE id=?",String.class,expired.id())).isEqualTo("DELETED");
    assertThat(media.read(bound.id(),owner,false).bytes()).isEqualTo(png);
    var interrupted=upload();jdbc.update("UPDATE media_files SET lifecycle='PURGING' WHERE id=?",interrupted.id());media.cleanup();
    assertThat(jdbc.queryForObject("SELECT lifecycle FROM media_files WHERE id=?",String.class,interrupted.id())).isEqualTo("DELETED");
  }
  @Test void newUtcColumnsAndAuditVersionsAgree(){
    Instant before=Instant.now().minusSeconds(1);var item=items.create(owner,create());long itemId=id(item);
    var stored=jdbc.queryForObject("SELECT created_at_utc FROM items WHERE id=?",LocalDateTime.class,itemId);
    assertThat(stored.toInstant(ZoneOffset.UTC)).isAfter(before).isBefore(Instant.now().plusSeconds(1));
    items.review(itemId,admin,new ItemDtos.Review("APPROVED",0L,null,"Private item note"));
    assertThat(jdbc.queryForObject("SELECT row_version FROM items WHERE id=?",Long.class,itemId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT object_version FROM business_logs WHERE item_id=? AND event_type='ITEM_APPROVED'",Long.class,itemId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT reviewed_content_version FROM items WHERE id=?",Long.class,itemId)).isEqualTo(1);
  }
  @Test void decoderRejectsDimensionBombBeforeFullDecode(){
    // A PNG header declaring a huge raster; header validation must precede allocation.
    byte[] bomb=png.clone();bomb[16]=0x7f;bomb[17]=(byte)0xff;bomb[18]=(byte)0xff;bomb[19]=(byte)0xff;
    assertThatThrownBy(()->media.upload(owner,new MockMultipartFile("file",bomb))).isInstanceOf(IllegalArgumentException.class);
  }
}
