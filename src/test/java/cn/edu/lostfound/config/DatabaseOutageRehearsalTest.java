package cn.edu.lostfound.config;

import cn.edu.lostfound.LostFoundApplication;
import cn.edu.lostfound.controller.GlobalExceptionHandler;
import cn.edu.lostfound.identity.AccountController;
import cn.edu.lostfound.identity.AccountWorkflow;
import cn.edu.lostfound.identity.AccountDtos;
import cn.edu.lostfound.security.PrivateResponseFilter;
import cn.edu.lostfound.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real MySQL + production controller/service/advice through in-process MVC. NO Redis/HTTP auth claim. */
@EnabledIfEnvironmentVariable(named="RUN_DB_OUTAGE_REHEARSAL",matches="true")
class DatabaseOutageRehearsalTest {
  private final ObjectMapper mapper=new ObjectMapper();
  private final List<Map<String,Object>> requests=new ArrayList<>();

  @Test void realConnectionLossFailsClosedAndPoolRecoversWithoutApplicationRestart()throws Exception {
    String prefix=System.getenv("REHEARSAL_SCHEMA_PREFIX"),password=System.getenv("REHEARSAL_DB_PASSWORD");
    assertThat(prefix).matches("rehearsal_[0-9]{14}_[a-f0-9]{8}_");assertThat(password).matches("[a-f0-9]{48}");
    Path directory=Path.of(System.getenv("REHEARSAL_DIRECTORY")).toRealPath();
    assertThat(directory.startsWith(Path.of(".local/database-rehearsal").toRealPath())).isTrue();
    String schema=prefix+"outage",suffix="/"+schema+"?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=1000&socketTimeout=2000";
    var direct=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13307"+suffix,"rehearsal_runner",password);
    var jdbc=new JdbcTemplate(direct);var identity=jdbc.queryForMap("SELECT @@port p,@@server_uuid u,@@datadir d");
    assertThat(((Number)identity.get("p")).intValue()).isEqualTo(13307);assertThat(identity.get("u")).isEqualTo(System.getenv("REHEARSAL_SERVER_UUID"));
    assertThat(Path.of(identity.get("d").toString()).toRealPath()).isEqualTo(directory.resolve("data").toRealPath());
    assertThat(jdbc.queryForList("SHOW TABLES",String.class)).isEmpty();
    Flyway.configure().dataSource(direct).locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false).load().migrate();
    jdbc.update("INSERT INTO users(id,username,password,nickname,role,is_test,created_at,updated_at) VALUES(1,'outage_synthetic','not-a-real-hash','Before outage','USER',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    jdbc.update("INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at) VALUES(1,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    boolean completed=false;String failureType=null;int relayPort=0,forwarded=0,cutSockets=0;
    try(var relay=new OwnedRelay()){
      relayPort=relay.port();Map<String,Object> config=new LinkedHashMap<>();
      config.put("spring.datasource.url","jdbc:mysql://127.0.0.1:"+relayPort+suffix);
      config.put("spring.datasource.username","rehearsal_runner");config.put("spring.datasource.password",password);
      config.put("spring.datasource.hikari.maximum-pool-size",2);config.put("spring.datasource.hikari.minimum-idle",0);
      config.put("spring.datasource.hikari.connection-timeout",1000);config.put("spring.datasource.hikari.validation-timeout",500);
      config.put("spring.flyway.enabled",false);config.put("spring.sql.init.mode","never");
      config.put("spring.jpa.hibernate.ddl-auto","validate");config.put("spring.jpa.generate-ddl",false);
      config.put("spring.data.redis.host","127.0.0.1");config.put("spring.data.redis.port",16380);
      config.put("app.campus.test-mode",true);config.put("app.campus.campus-id","TEST_CAMPUS");config.put("app.admin-password","");
      config.put("app.media.root",directory.resolve("outage-media").toString());config.put("app.media.cleanup-enabled",false);config.put("ai.enabled",false);
      var application=new SpringApplication(LostFoundApplication.class);application.setWebApplicationType(WebApplicationType.NONE);
      application.addInitializers(context->{context.getEnvironment().setActiveProfiles("outagerehearsal");context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-outage",config));});
      try(var context=application.run()){
        var mvc=MockMvcBuilders.standaloneSetup(context.getBean(AccountController.class))
          .setControllerAdvice(context.getBean(GlobalExceptionHandler.class)).addFilters(context.getBean(PrivateResponseFilter.class))
          .setMessageConverters(new MappingJackson2HttpMessageConverter(context.getBean(ObjectMapper.class))).build();
        assertProfile(request(mvc,"before",false),"Before outage",0);
        cutSockets=relay.cut();assertThat(cutSockets).isPositive();
        assertUnavailable(request(mvc,"disconnected-read",false));
        assertUnavailable(request(mvc,"disconnected-write",true));
        assertThat(jdbc.queryForMap("SELECT nickname,row_version AS version FROM users WHERE id=1"))
          .containsEntry("nickname","Before outage").containsEntry("version",0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM business_logs",Long.class)).isZero();
        relay.restore();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);MvcResult recovered;
        do {recovered=request(mvc,"recovery-read",false);if(recovered.getResponse().getStatus()==200)break;assertUnavailable(recovered);Thread.sleep(200);}
        while(System.nanoTime()<until);
        assertProfile(recovered,"Before outage",0);
        assertProfile(request(mvc,"recovered-write",true),"After recovery",1);
        assertProfile(request(mvc,"confirmed-read",false),"After recovery",1);
        assertThat(jdbc.queryForMap("SELECT nickname,row_version AS version FROM users WHERE id=1"))
          .containsEntry("nickname","After recovery").containsEntry("version",1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class)).isEqualTo(1L);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        long interruptedAt=System.nanoTime();
        Throwable interrupted=catchThrowable(()->transaction.execute(status->{
          // Real service flushes its UPDATE, then the owned socket is cut before outer COMMIT.
          context.getBean(AccountWorkflow.class).update(1L,new AccountDtos.UpdateMe("Must roll back",null,1L));
          assertThat(relay.cut()).isPositive();return null;
        }));
        long interruptedMs=(System.nanoTime()-interruptedAt)/1_000_000;
        assertThat(interrupted).isInstanceOf(org.springframework.dao.RecoverableDataAccessException.class);
        assertThat(interruptedMs).isLessThan(6000);
        requests.add(Map.of("phase","flushed-update-cut-before-commit","exceptionType",interrupted.getClass().getSimpleName(),"elapsedMs",interruptedMs,"networkHttp",false));
        // The global generic envelope does not tell a caller that an uncertain write succeeded.
        var envelope=context.getBean(GlobalExceptionHandler.class).other((Exception)interrupted);
        assertThat(envelope.getStatusCode().value()).isEqualTo(500);assertThat(envelope.getBody().errorCode()).isEqualTo("SERVICE_ERROR");
        assertThat(envelope.getBody().traceId()).isNotBlank();assertThat(envelope.getBody().data()).isNull();
        assertThat(mapper.writeValueAsString(envelope.getBody())).doesNotContain("jdbc:","rehearsal_","SQL","Must roll back",password);
        assertThat(jdbc.queryForMap("SELECT nickname,row_version AS version FROM users WHERE id=1"))
          .containsEntry("nickname","After recovery").containsEntry("version",1L);
        relay.restore();until=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        do {recovered=request(mvc,"recovery-after-precommit-cut",false);if(recovered.getResponse().getStatus()==200)break;assertUnavailable(recovered);Thread.sleep(200);}
        while(System.nanoTime()<until);
        assertProfile(recovered,"After recovery",1);
        completed=true;
      } finally {forwarded=relay.forwarded.get();}
    }catch(Throwable error){failureType=error.getClass().getSimpleName();throw error;}
    finally {
      UserContext.clear();Map<String,Object> evidence=new LinkedHashMap<>();
      evidence.put("at",Instant.now().toString());evidence.put("completed",completed);evidence.put("failureType",failureType);
      evidence.put("schemaPrefix",prefix);evidence.put("upstreamPort",13307);evidence.put("relayPort",relayPort);
      evidence.put("forwardedConnections",forwarded);evidence.put("cutSocketCount",cutSockets);evidence.put("requests",requests);
      evidence.put("limits",List.of("In-process MVC with manually assigned synthetic user; no Redis, network HTTP, login or authorization-interceptor verification","Real MySQL bytes relayed unchanged; cut only owned TCP sockets, not the server or existing databases","Short test-only pool/driver timeouts, not production recovery SLA","Precommit cut uses an outer test transaction; not a lost-COMMIT-acknowledgement or automatic retry guarantee","No dead server, restart or network blackhole claim"));
      mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("database-outage.json").toFile(),evidence);
    }
  }
  private MvcResult request(MockMvc mvc,String phase,boolean write)throws Exception {
    long start=System.nanoTime();UserContext.set(1L,"USER");
    try {
      var builder=write?put("/api/users/me").contentType("application/json").content("{\"nickname\":\"After recovery\",\"contact\":null,\"expectedVersion\":0}"):get("/api/users/me");
      var result=mvc.perform(builder).andReturn();long elapsed=(System.nanoTime()-start)/1_000_000;
      requests.add(Map.of("phase",phase,"status",result.getResponse().getStatus(),"elapsedMs",elapsed,
        "exceptionType",result.getResolvedException()==null?"none":result.getResolvedException().getClass().getSimpleName()));
      assertThat(elapsed).as("bounded test-only connection timeout").isLessThan(6000);
      assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");return result;
    } finally {UserContext.clear();}
  }
  private void assertUnavailable(MvcResult result)throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    String body=result.getResponse().getContentAsString();var parsed=mapper.readTree(body);
    assertThat(parsed.path("errorCode").asText()).isEqualTo("SERVICE_UNAVAILABLE");assertThat(parsed.path("traceId").asText()).isNotBlank();
    assertThat(parsed.path("data").isNull()).isTrue();assertThat(body).doesNotContain("jdbc:","13307","rehearsal_","SQL","Before outage","not-a-real-hash",System.getenv("REHEARSAL_DB_PASSWORD"));
  }
  private void assertProfile(MvcResult result,String nickname,long version)throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(200);var body=mapper.readTree(result.getResponse().getContentAsString());
    assertThat(body.path("code").asInt()).isZero();assertThat(body.path("data").path("nickname").asText()).isEqualTo(nickname);
    assertThat(body.path("data").path("version").asLong()).isEqualTo(version);assertThat(body.path("data").has("password")).isFalse();
  }
  /** No protocol parser, no fabricated SQL/results, no external destination or payload logging. */
  private static final class OwnedRelay implements AutoCloseable {
    private final ServerSocket listener=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();private boolean accepting=true;
    private final AtomicInteger forwarded=new AtomicInteger();
    OwnedRelay()throws IOException {workers.submit(()->{while(!listener.isClosed())try{connect(listener.accept());}catch(IOException ignored){if(listener.isClosed())return;}});}
    int port(){return listener.getLocalPort();}
    private synchronized void connect(Socket incoming)throws IOException {
      if(!accepting){incoming.close();return;}Socket upstream=new Socket();
      try {upstream.connect(new InetSocketAddress("127.0.0.1",13307),1000);sockets.add(incoming);sockets.add(upstream);forwarded.incrementAndGet();
        workers.submit(()->copy(incoming,upstream));workers.submit(()->copy(upstream,incoming));
      }catch(IOException failure){closeSocket(incoming);closeSocket(upstream);throw failure;}
    }
    private void copy(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());}catch(IOException ignored){}finally{closeSocket(from);closeSocket(to);sockets.remove(from);sockets.remove(to);}}
    synchronized int cut(){accepting=false;int count=sockets.size();for(Socket socket:sockets)closeSocket(socket);return count;}
    synchronized void restore(){accepting=true;}
    private static void closeSocket(Socket socket){try{socket.close();}catch(IOException ignored){}}
    @Override public void close()throws Exception {cut();listener.close();workers.shutdownNow();assertThat(workers.awaitTermination(5,TimeUnit.SECONDS)).as("owned relay workers stopped").isTrue();}
  }
}
