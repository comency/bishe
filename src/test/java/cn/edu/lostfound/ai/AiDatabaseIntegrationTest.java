package cn.edu.lostfound.ai;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.dto.*;
import cn.edu.lostfound.repository.UserRepository;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.service.*;
import cn.edu.lostfound.verification.VerificationService;
import static cn.edu.lostfound.verification.VerificationDtos.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real eligibility/transaction tests; generated text is a deliberately fake provider result. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named="RUN_AI_DB_TESTS",matches="true")
class AiDatabaseIntegrationTest {
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired VerificationService verification;
  @Autowired AiService ai;
  @Autowired JdbcTemplate jdbc;
  @Autowired CampusProperties campus;
  @MockBean LocalAiClient client;
  long user,admin;
  @BeforeEach void setup(){
    assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("lost_found_test");assertThat(campus.testMode()).isTrue();
    admin=users.findByUsername("admin").orElseThrow().getId();String name="aidb_"+UUID.randomUUID().toString().replace("-","");
    auth.register(new AuthDtos.Register(name,"Synthetic-ai-test-password","Synthetic AI tester"));user=users.findByUsername(name).orElseThrow().getId();
    UserContext.set(user,"USER");var pending=verification.submit(new SubmitRequest(0L,"Synthetic AI",null,"No real personal data"));
    UserContext.set(admin,"ADMIN");verification.review(user,new ReviewRequest(pending.currentApplication().id(),pending.summary().version(),Decision.APPROVED,Method.IN_PERSON,"Synthetic only",LocalDate.now(campus.zoneId()).plusDays(2),null,null));UserContext.clear();
  }
  @AfterEach void clear(){UserContext.clear();}
  @Test void revocationCanCommitDuringGenerationAndDiscardsProviderResult(){
    when(client.generate("Synthetic",false)).thenAnswer(call->{
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      try(var pool=Executors.newSingleThreadExecutor()){
        pool.submit(()->{try{UserContext.set(admin,"ADMIN");var detail=verification.detail(user,1,10);verification.revoke(user,new VersionReasonRequest(detail.summary().version(),"Synthetic revoke during generation"));}finally{UserContext.clear();}}).get(3,TimeUnit.SECONDS);
      }return new AiDtos.Result("Must be discarded","GENERATED",null);
    });
    assertThatThrownBy(()->ai.chat(user,"Synthetic")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERIFICATION_REQUIRED"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM items WHERE publisher_id=?",Long.class,user)).isZero();
  }
  @Test void expiryBeforeReturningEvenDisabledResultIsDenied(){
    when(client.generate("Synthetic",true)).thenAnswer(call->{jdbc.update("UPDATE campus_verifications SET expires_at=? WHERE user_id=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),user);return AiDtos.Result.unavailable("DISABLED");});
    assertThatThrownBy(()->ai.polish(user,"Synthetic")).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERIFICATION_REQUIRED"));
  }
}
